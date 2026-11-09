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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.arnoldkolkman.akonotulist.AppContainer
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.audio.AudioFiles
import nl.arnoldkolkman.akonotulist.core.Format
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

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val rec = c.db.recordings().get(id) ?: return Result.failure()
        val audio = rec.audioPath?.let(::File)?.takeIf { it.exists() }
            ?: return fail(rec, "Audio niet meer beschikbaar.")
        if (!c.secrets.hasApiKey()) return fail(rec, "Geen OpenAI API-sleutel ingesteld (Instellingen).")

        runCatching { setForeground(foregroundInfo(rec, 0, "Transcriberen voorbereiden…")) }

        val settings = c.settings.current()
        var model = TranscriptionModels.byId(rec.transcriptionModel ?: settings.transcriptionModel)
        val language = rec.language ?: settings.language
        val vocabulary = settings.vocabulary.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        val dao = c.db.recordings()
        dao.setStatus(id, RecordingStatus.TRANSCRIBING, 0, "Transcriberen…")

        val plan = ChunkPlanner.plan(rec.durationMs)
        if (plan.isEmpty()) return fail(rec, "Opname is leeg.")
        var stored = c.db.chunks().forRecording(id)
        if (stored.size != plan.size || stored.zip(plan).any { (s, p) -> s.startMs != p.startMs || s.endMs != p.endMs }) {
            c.db.chunks().deleteFor(id)
            c.db.chunks().insertAll(plan.map { ChunkEntity(id, it.index, it.startMs, it.endMs) })
            stored = c.db.chunks().forRecording(id)
        }

        val workDir = File(applicationContext.cacheDir, "chunks/$id").apply { mkdirs() }
        val results = mutableListOf<ChunkResult>()
        val references = LinkedHashMap<String, KnownSpeaker>() // global label -> clip

        try {
            for (window in plan) {
                val row = stored.first { it.chunkIndex == window.index }
                val done = row.resultJson?.let { storeJson.decodeFromString(StoredChunk.serializer(), it) }
                val result: ChunkResult = if (done != null) {
                    ChunkResult(window, done.actualStartMs, done.actualDurationMs, done.transcript)
                } else {
                    val n = plan.size
                    val detail = if (n > 1) "Deel ${window.index + 1} van $n uploaden…" else "Uploaden en transcriberen…"
                    val progress = (window.index * 90) / n + 2
                    dao.setProgress(id, progress, detail)
                    setProgress(workDataOf("progress" to progress))
                    runCatching { setForeground(foregroundInfo(rec, progress, detail)) }

                    val chunkFile = File(workDir, "chunk_${window.index}.m4a")
                    val actualStart = AudioFiles.slice(audio, chunkFile, window.startMs, window.endMs)
                    val actualDuration = AudioFiles.durationMs(chunkFile).takeIf { it > 0 } ?: (window.endMs - actualStart)
                    val prompt = buildPrompt(model, vocabulary, results.lastOrNull()?.transcript?.text)

                    val transcript = try {
                        c.openAi.transcribe(chunkFile, model, language, prompt, vocabulary, references.values.toList())
                    } catch (e: OpenAiException) {
                        if (e.modelUnavailable && model != TranscriptionModels.FALLBACK) {
                            // e.g. the diarization model after its shutdown (2027-02-26).
                            model = TranscriptionModels.FALLBACK
                            dao.upsert(dao.get(id)!!.copy(transcriptionModel = model.id))
                            dao.setProgress(id, progress, "Model niet beschikbaar; overgestapt op ${model.label}…")
                            c.openAi.transcribe(chunkFile, model, language, buildPrompt(model, vocabulary, null), vocabulary)
                        } else throw e
                    }
                    chunkFile.delete()
                    c.db.chunks().upsert(
                        row.copy(
                            resultJson = storeJson.encodeToString(StoredChunk.serializer(), StoredChunk(actualStart, actualDuration, transcript)),
                            model = model.id,
                        )
                    )
                    ChunkResult(window, actualStart, actualDuration, transcript)
                }
                results += result
                if (model.hasDiarization && window.index < plan.lastIndex && references.size < 4) {
                    updateReferences(audio, workDir, results, references)
                }
            }
        } catch (e: OpenAiException) {
            return if (e.retryable) retryOrFail(rec, e.message) else fail(rec, e.message ?: "OpenAI-fout")
        } catch (e: IOException) {
            return retryOrFail(rec, "Netwerkfout: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(rec, "Fout bij verwerken van de audio: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            workDir.listFiles()?.forEach { it.delete() }
        }

        dao.setProgress(id, 92, "Transcript samenvoegen…")
        val merged = TranscriptMerger.merge(results)
        storeTranscript(id, merged)

        val fresh = dao.get(id) ?: return Result.success()
        dao.upsert(
            fresh.copy(
                transcriptionModel = model.id,
                timestampsEstimated = merged.timestampsEstimated,
                estimatedCostUsd = Format.estimateCostUsd(fresh.durationMs, settings.rateFor(model.id)),
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

    private fun buildPrompt(model: TranscriptionModel, vocabulary: List<String>, previousText: String?): String? {
        if (!model.supportsPrompt) return null
        val parts = buildList {
            if (vocabulary.isNotEmpty() && !model.supportsKeywords) add("Namen en termen: ${vocabulary.joinToString(", ")}.")
            // Carry context over the chunk boundary (recommended in the OpenAI docs).
            previousText?.takeLast(400)?.let { add(it.substringAfter(' ')) }
        }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private suspend fun retryOrFail(rec: RecordingEntity, message: String?): Result {
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
        const val MAX_ATTEMPTS = 7
    }
}

/** App-wide one-shot events (consumed by e.g. the Android Auto session for TTS). */
sealed interface AppEvent {
    data class TranscriptionDone(val recordingId: Long, val title: String) : AppEvent
    data class TranscriptionFailed(val recordingId: Long, val title: String) : AppEvent
}
