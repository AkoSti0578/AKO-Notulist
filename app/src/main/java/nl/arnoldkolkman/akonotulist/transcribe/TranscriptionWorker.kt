package nl.arnoldkolkman.akonotulist.transcribe

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.arnoldkolkman.akonotulist.AppContainer
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.audio.AudioFiles
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.core.LanguageGuess
import nl.arnoldkolkman.akonotulist.summary.LanguageFix
import nl.arnoldkolkman.akonotulist.summary.Translator
import nl.arnoldkolkman.akonotulist.core.Notifications
import nl.arnoldkolkman.akonotulist.data.ChunkEntity
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import nl.arnoldkolkman.akonotulist.data.SegmentEntity
import nl.arnoldkolkman.akonotulist.data.SpeakerEntity
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What we persist per finished chunk (so a retry never pays for the same audio twice). */
@Serializable
data class StoredChunk(val actualStartMs: Long, val actualDurationMs: Long, val transcript: ChunkTranscript)

private val storeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** Enqueues/cancels transcription work. Network-constrained with exponential backoff. */
class TranscriptionScheduler(private val context: Context, private val c: AppContainer) {

    suspend fun enqueue(recordingId: Long, fresh: Boolean = false) {
        val rec = c.db.recordings().get(recordingId) ?: return
        if (rec.audioPath == null) return
        val s = c.settings.current()
        if (fresh) {
            c.db.chunks().deleteFor(recordingId)
        }
        c.db.recordings().upsert(
            rec.copy(
                status = RecordingStatus.QUEUED,
                progress = 0,
                statusDetail = null,
                errorMessage = null,
                transcriptionModel = if (fresh || rec.transcriptionModel == null) s.transcriptionModel else rec.transcriptionModel,
                language = s.language,
                estimatedCostUsd = Format.estimateCostUsd(rec.durationMs, s.rateFor(s.transcriptionModel)),
            )
        )
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(TranscriptionWorker.KEY_ID to recordingId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(recordingId), ExistingWorkPolicy.REPLACE, request)
        c.processingLog.add(recordingId, "In de wachtrij gezet${if (fresh) " (opnieuw vanaf het begin)" else ""}; start zodra er netwerk is.")
    }

    /** Continues a long transcription in a fresh job once the current one has finished. */
    fun enqueueContinuation(recordingId: Long) {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(TranscriptionWorker.KEY_ID to recordingId, TranscriptionWorker.KEY_CONTINUATION to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(recordingId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel(recordingId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(recordingId))
    }

    companion object {
        const val TAG = "transcription"
        fun workName(id: Long) = "transcribe_$id"
    }
}

/**
 * Transcribes one recording:
 * plan chunks → slice (no re-encode) → upload each chunk → persist each result → merge → store.
 *
 * Speaker consistency: after a chunk is done, we cut a short reference clip (2–10 s) for every
 * speaker we have not got one for yet (max. 4) and send those as `known_speaker_references[]`
 * with the following chunks, so the API labels them "spk_A", "spk_B", … across the recording.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val c by lazy { applicationContext.appContainer }

    private fun log(id: Long, msg: String) = c.processingLog.add(id, msg)

    override suspend fun doWork(): Result {
        val startedAt = SystemClock.elapsedRealtime()
        val id = inputData.getLong(KEY_ID, -1)
        val rec = c.db.recordings().get(id) ?: return Result.failure()
        log(
            id,
            if (inputData.getBoolean(KEY_CONTINUATION, false)) "Transcriptie gaat verder (vervolgronde)."
            else "Transcriptie gestart" + if (runAttemptCount > 0) " (nieuwe poging ${runAttemptCount + 1})." else ".",
        )
        val audio = rec.audioPath?.let(::File)?.takeIf { it.exists() }
            ?: return fail(rec, "Audio niet meer beschikbaar.")
        if (!c.secrets.hasApiKey()) return fail(rec, "Geen OpenAI API-sleutel ingesteld (Instellingen).")

        // As a foreground service the job may run long; otherwise Android stops it after ~10 min,
        // so we then work in short rounds and continue in a fresh job (never losing a paid chunk).
        val fg = runCatching { setForeground(foregroundInfo(rec, 0, "Transcriberen voorbereiden…")) }
        val budgetMs = c.debugConfig?.roundBudgetMs ?: if (fg.isSuccess) LONG_BUDGET_MS else SHORT_BUDGET_MS
        log(
            id,
            if (fg.isSuccess) "Draait als voorgrondtaak (melding zichtbaar)."
            else "Android staat geen voorgrondtaak toe (${fg.exceptionOrNull()?.javaClass?.simpleName}); " +
                "werkt in rondes van max. ${Format.duration(budgetMs)}. Tip: hef de batterijbeperking op voor AKO Notulist " +
                "(Instellingen → Achtergrondverwerking), dan loopt het in één keer door.",
        )

        val settings = c.settings.current()
        val modelRef = AtomicReference(TranscriptionModels.byId(rec.transcriptionModel ?: settings.transcriptionModel))
        val language = rec.language ?: settings.language
        val vocabulary = settings.vocabulary.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        val dao = c.db.recordings()
        dao.setStatus(id, RecordingStatus.TRANSCRIBING, 0, "Transcriberen voorbereiden…")

        val plan = ChunkPlanner.plan(rec.durationMs)
        if (plan.isEmpty()) return fail(rec, "Opname is leeg.")
        var stored = c.db.chunks().forRecording(id)
        if (stored.size != plan.size || stored.zip(plan).any { (s, p) -> s.startMs != p.startMs || s.endMs != p.endMs }) {
            c.db.chunks().deleteFor(id)
            c.db.chunks().insertAll(plan.map { ChunkEntity(id, it.index, it.startMs, it.endMs) })
            stored = c.db.chunks().forRecording(id)
        }
        val rows = stored.associateBy { it.chunkIndex }

        val done = ConcurrentHashMap<Int, ChunkResult>()
        plan.forEach { w ->
            rows[w.index]?.resultJson?.let { json ->
                val sc = storeJson.decodeFromString(StoredChunk.serializer(), json)
                done[w.index] = ChunkResult(w, sc.actualStartMs, sc.actualDurationMs, sc.transcript)
            }
        }
        log(id, "Opname ${Format.duration(rec.durationMs)} → ${plan.size} delen van max. 10 min; ${done.size} al klaar. Model: ${modelRef.get().id}.")

        val workDir = File(applicationContext.cacheDir, "chunks/$id").apply { mkdirs() }
        val active = ConcurrentHashMap<Int, ChunkPhase>()
        val references = LinkedHashMap<String, KnownSpeaker>()

        suspend fun processChunk(window: ChunkWindow) {
            val nr = window.index + 1
            active[window.index] = ChunkPhase.Uploading(0)
            val chunkFile = File(workDir, "chunk_${window.index}.m4a")
            try {
                val actualStart = AudioFiles.slice(audio, chunkFile, window.startMs, window.endMs)
                val actualDuration = AudioFiles.durationMs(chunkFile).takeIf { it > 0 } ?: (window.endMs - actualStart)
                val sizeKb = chunkFile.length() / 1024
                log(id, "Deel $nr: ${Format.duration(actualDuration)} audio, $sizeKb KB versturen…")
                val t0 = SystemClock.elapsedRealtime()
                var uploadLogged = false
                val onUpload: (Long, Long) -> Unit = { sent, total ->
                    val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                    if (pct >= 100) {
                        if (active[window.index] is ChunkPhase.Uploading) active[window.index] = ChunkPhase.Waiting(SystemClock.elapsedRealtime())
                        if (!uploadLogged) {
                            uploadLogged = true
                            log(id, "Deel $nr: verstuurd in ${(SystemClock.elapsedRealtime() - t0) / 1000} s; OpenAI is aan het transcriberen…")
                        }
                    } else {
                        active[window.index] = ChunkPhase.Uploading(pct)
                    }
                }
                val previousText = done[window.index - 1]?.transcript?.text
                var transcript = try {
                    val model = modelRef.get()
                    c.openAi.transcribe(
                        chunkFile, model, language, buildPrompt(model, vocabulary, previousText, language), vocabulary,
                        synchronized(references) { references.values.toList() }, onUpload,
                    )
                } catch (e: OpenAiException) {
                    if (e.modelUnavailable && modelRef.get() != TranscriptionModels.FALLBACK) {
                        // e.g. the diarization model after its shutdown (2027-02-26).
                        modelRef.set(TranscriptionModels.FALLBACK)
                        log(id, "Model niet beschikbaar (${e.message}); overgestapt op ${TranscriptionModels.FALLBACK.id}.")
                        dao.get(id)?.let { dao.upsert(it.copy(transcriptionModel = TranscriptionModels.FALLBACK.id)) }
                        c.openAi.transcribe(
                            chunkFile, TranscriptionModels.FALLBACK, language,
                            buildPrompt(TranscriptionModels.FALLBACK, vocabulary, null, language), vocabulary, onUploadProgress = onUpload,
                        )
                    } else throw e
                }
                transcript = ensureLanguage(id, nr, transcript, language) {
                    val model = modelRef.get()
                    c.openAi.transcribe(
                        chunkFile, model, language, buildPrompt(model, vocabulary, previousText, language), vocabulary,
                        synchronized(references) { references.values.toList() },
                    )
                }
                c.db.chunks().upsert(
                    rows.getValue(window.index).copy(
                        resultJson = storeJson.encodeToString(StoredChunk.serializer(), StoredChunk(actualStart, actualDuration, transcript)),
                        model = modelRef.get().id,
                    )
                )
                done[window.index] = ChunkResult(window, actualStart, actualDuration, transcript)
                log(id, "Deel $nr: klaar in ${(SystemClock.elapsedRealtime() - t0) / 1000} s (${transcript.segments.size} segmenten).")
            } catch (e: OpenAiException) {
                log(id, "Deel $nr: OpenAI-fout ${e.httpCode}: ${e.message}")
                throw e
            } catch (e: IOException) {
                log(id, "Deel $nr: netwerkfout: ${e.message ?: e.javaClass.simpleName}")
                throw e
            } finally {
                active.remove(window.index)
                chunkFile.delete()
            }
        }

        try {
            coroutineScope {
                val ticker = launch {
                    var lastNotified = 0L
                    while (isActive) {
                        val now = SystemClock.elapsedRealtime()
                        val phases = active.values.toList()
                        val pct = ChunkProgress.percent(done.size, plan.size, phases, now)
                        val text = ChunkProgress.text(done.size, plan.size, phases, now)
                        dao.setProgress(id, pct, text)
                        setProgress(workDataOf("progress" to pct))
                        if (fg.isSuccess && now - lastNotified > 5_000) {
                            lastNotified = now
                            runCatching { setForeground(foregroundInfo(rec, pct, text)) }
                        }
                        delay(1_000)
                    }
                }
                try {
                    // Chunk 1 first: it provides the speaker reference clips for all other chunks.
                    plan.firstOrNull { !done.containsKey(it.index) && it.index == 0 }?.let { processChunk(it) }
                    if (modelRef.get().hasDiarization) {
                        done[0]?.let { first -> updateReferences(audio, workDir, listOf(first), references) }
                        if (references.isNotEmpty()) log(id, "Sprekerfragmenten voor herkenning in volgende delen: ${references.keys.joinToString()}.")
                    }
                    // The rest in parallel (max. PARALLEL at a time), within the time budget.
                    val gate = Semaphore(PARALLEL)
                    plan.filter { !done.containsKey(it.index) }.map { w ->
                        async {
                            gate.withPermit {
                                // Past the budget: leave the chunk for the next round instead of risking a kill mid-request.
                                if (SystemClock.elapsedRealtime() - startedAt < budgetMs) processChunk(w)
                            }
                        }
                    }.awaitAll()
                } finally {
                    ticker.cancel()
                }
            }
        } catch (e: OpenAiException) {
            return if (e.retryable) retryOrFail(rec, e.message) else fail(rec, e.message ?: "OpenAI-fout")
        } catch (e: IOException) {
            return retryOrFail(rec, "Netwerkfout: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            log(id, "Onderbroken door Android (${done.size}/${plan.size} delen klaar; die blijven bewaard). Wordt automatisch hervat.")
            throw e
        } catch (e: Exception) {
            log(id, "Fout: ${e.javaClass.simpleName}: ${e.message}")
            return fail(rec, "Fout bij verwerken van de audio: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            workDir.listFiles()?.forEach { it.delete() }
        }

        if (done.size < plan.size) {
            log(id, "Tijdslimiet van deze ronde bereikt (${done.size}/${plan.size} klaar); volgende ronde wordt direct ingepland.")
            dao.setProgress(id, ChunkProgress.percent(done.size, plan.size, emptyList(), 0), "${done.size} van ${plan.size} delen klaar · gaat zo verder…")
            c.transcription.enqueueContinuation(id)
            return Result.success()
        }

        dao.setProgress(id, 92, "Transcript samenvoegen…")
        val results = plan.map { done.getValue(it.index) }
        val merged = TranscriptMerger.merge(results)
        storeTranscript(id, merged)
        log(id, "Alle ${plan.size} delen klaar in ${Format.duration(SystemClock.elapsedRealtime() - startedAt)} (deze ronde); ${merged.segments.size} zinnen.")

        val fresh = dao.get(id) ?: return Result.success()
        val finalModel = modelRef.get()
        dao.upsert(
            fresh.copy(
                transcriptionModel = finalModel.id,
                timestampsEstimated = merged.timestampsEstimated,
                estimatedCostUsd = Format.estimateCostUsd(fresh.durationMs, settings.rateFor(finalModel.id)),
            )
        )
        c.onTranscriptStored(id)
        return Result.success()
    }

    private suspend fun storeTranscript(id: Long, merged: MergeResult) {
        c.db.segments().replace(
            id,
            merged.segments.mapIndexed { i, s ->
                SegmentEntity(recordingId = id, position = i, startMs = s.startMs, endMs = s.endMs, speaker = s.speaker, text = s.text)
            },
        )
        val existing = c.db.speakers().forRecording(id).associateBy { it.label }
        val labels = merged.segments.mapNotNull { it.speaker }.distinct()
        c.db.speakers().deleteFor(id)
        c.db.speakers().insertAll(labels.map { l -> SpeakerEntity(id, l, existing[l]?.displayName ?: "Spreker $l") })
    }

    /** Cut one 2–10 s reference clip per newly seen speaker (max 4 in total). */
    private fun updateReferences(audio: File, dir: File, results: List<ChunkResult>, refs: MutableMap<String, KnownSpeaker>) {
        val merged = runCatching { TranscriptMerger.merge(results) }.getOrNull() ?: return
        val last = results.last()
        val map = merged.speakerMaps.lastOrNull() ?: return
        last.transcript.segments
            .filter { it.speaker != null && it.startMs != null && it.endMs != null }
            .groupBy { map[it.speaker!!] }
            .forEach { (global, segs) ->
                if (global == null || global in refs || refs.size >= 4) return@forEach
                val best = segs.map { it to (it.endMs!! - it.startMs!!) }
                    .filter { it.second >= 2_500 }
                    .maxByOrNull { (_, d) -> if (d <= 10_000) d else 10_000 - (d - 10_000) / 10 } ?: return@forEach
                val start = last.actualStartMs + best.first.startMs!!
                val len = minOf(best.second, 9_000)
                val clip = File(dir, "ref_$global.m4a")
                runCatching {
                    AudioFiles.slice(audio, clip, start, start + len)
                    val b64 = Base64.encodeToString(clip.readBytes(), Base64.NO_WRAP)
                    refs[global] = KnownSpeaker(TranscriptMerger.KNOWN_PREFIX + global, "data:audio/mp4;base64,$b64")
                }
                clip.delete()
            }
    }

    /**
     * The gpt-4o models sometimes answer Dutch speech with an English translation, even with
     * language=nl. If a chunk comes back in the wrong language: transcribe once more; if it is
     * still wrong, translate the segments into the target language (speakers and times are kept).
     */
    private suspend fun ensureLanguage(
        id: Long,
        nr: Int,
        transcript: ChunkTranscript,
        language: String,
        retry: suspend () -> ChunkTranscript,
    ): ChunkTranscript {
        if (!LanguageGuess.isWrongLanguage(transcript.text, language)) return transcript
        log(id, "Deel $nr: OpenAI gaf Engelse tekst terug in plaats van Nederlands; nog één keer transcriberen…")
        val second = runCatching { retry() }.getOrNull()
        if (second != null && !LanguageGuess.isWrongLanguage(second.text, language)) {
            log(id, "Deel $nr: tweede poging is wel Nederlands.")
            return second
        }
        val source = second ?: transcript
        return runCatching { LanguageFix.translate(Translator(c), source, language) }
            .onSuccess { log(id, "Deel $nr: nog steeds Engels; ${source.segments.size} zinnen naar het Nederlands vertaald (sprekers en tijden ongewijzigd).") }
            .onFailure { log(id, "Deel $nr: vertalen naar Nederlands mislukt (${it.message}); Engelse tekst bewaard.") }
            .getOrDefault(source)
    }

    private fun buildPrompt(model: TranscriptionModel, vocabulary: List<String>, previousText: String?, language: String? = null): String? {
        if (!model.supportsPrompt) return null
        val parts = buildList {
            // A prompt in the target language strongly steers the output language.
            if (language?.lowercase()?.startsWith("nl") == true) add("Dit is een Nederlandstalig gesprek, letterlijk uitgeschreven in het Nederlands.")
            if (vocabulary.isNotEmpty() && !model.supportsKeywords) add("Namen en termen: ${vocabulary.joinToString(", ")}.")
            // Carry context over the chunk boundary (recommended in the OpenAI docs).
            previousText?.takeLast(400)?.let { add(it.substringAfter(' ')) }
        }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private suspend fun retryOrFail(rec: RecordingEntity, message: String?): Result {
        log(rec.id, "Tijdelijk probleem: $message")
        return if (runAttemptCount < MAX_ATTEMPTS) {
            c.db.recordings().setStatus(
                rec.id, RecordingStatus.QUEUED, 0,
                "Opnieuw proberen (poging ${runAttemptCount + 2} van ${MAX_ATTEMPTS + 1})…", null,
            )
            Result.retry()
        } else {
            fail(rec, message ?: "Transcriberen mislukt na meerdere pogingen.")
        }
    }

    private suspend fun fail(rec: RecordingEntity, message: String): Result {
        log(rec.id, "Mislukt: $message")
        c.db.recordings().setStatus(rec.id, RecordingStatus.FAILED, 0, null, message)
        Notifications.notifyFinished(applicationContext, rec.id, rec.title, success = false, message = message)
        c.events.tryEmit(AppEvent.TranscriptionFailed(rec.id, rec.title))
        return Result.failure()
    }

    private fun foregroundInfo(rec: RecordingEntity, progress: Int, text: String): ForegroundInfo {
        val n = Notifications.processing(applicationContext, rec.id, rec.title, text, progress)
        val nid = Notifications.processingId(rec.id)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(nid, n)
        }
    }

    companion object {
        const val KEY_ID = "recording_id"
        const val KEY_CONTINUATION = "continuation"
        const val MAX_ATTEMPTS = 7

        /** Chunks sent to OpenAI at the same time. */
        const val PARALLEL = 3

        /** Without a foreground service Android stops a job after ~10 min: start no new chunk after 5. */
        const val SHORT_BUDGET_MS = 5 * 60_000L
        const val LONG_BUDGET_MS = 45 * 60_000L
    }
}

/** App-wide one-shot events (consumed by e.g. the Android Auto session for TTS). */
sealed interface AppEvent {
    data class TranscriptionDone(val recordingId: Long, val title: String) : AppEvent
    data class TranscriptionFailed(val recordingId: Long, val title: String) : AppEvent
}
