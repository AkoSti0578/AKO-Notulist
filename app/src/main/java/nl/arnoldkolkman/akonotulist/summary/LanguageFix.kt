package nl.arnoldkolkman.akonotulist.summary

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.audio.AudioFiles
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.SegmentEntity
import nl.arnoldkolkman.akonotulist.transcribe.OpenAiException
import nl.arnoldkolkman.akonotulist.transcribe.TranscriptionModels
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object LanguageFix {
    fun languageName(code: String?): String = when (code?.lowercase()?.take(2)) {
        "nl" -> "Dutch"
        "en" -> "English"
        "de" -> "German"
        "fr" -> "French"
        "es" -> "Spanish"
        else -> "Dutch"
    }

    /** Number of sentences of [texts] that are in the wrong language for [language]. */
    fun wrongCount(texts: List<String>, language: String?): Int =
        LanguageRepair.wrongRanges(texts, language).sumOf { it.count() }
}

/**
 * Repairs an existing transcript that is (partly) English ("Omzetten naar NL").
 *
 * With the audio still on the device and real timestamps, the English stretches are listened to
 * again (Whisper, Dutch forced) in pieces of at most 10 minutes, and replaced by the literal Dutch
 * sentences with the original speakers. Without audio — or for what could not be repaired that
 * way — the sentences are translated.
 */
class LanguageFixWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        val id = inputData.getLong(KEY_ID, -1)
        val rec = c.db.recordings().get(id) ?: return Result.failure()
        val language = rec.language ?: c.settings.current().language
        val stored = c.db.segments().forRecording(id)
        var segments = stored.map { TimedText(it.startMs, it.endMs, it.speaker, it.text) }
        val wrong = LanguageRepair.wrongRanges(segments.map { it.text }, language)
        fun log(msg: String) = c.processingLog.add(id, msg)
        if (wrong.isEmpty()) {
            log("Taalcontrole: het transcript is al Nederlands; niets te doen.")
            return Result.success()
        }
        val total = wrong.sumOf { it.count() }
        log("Taalcontrole: $total van ${segments.size} zinnen zijn Engels.")
        return try {
            var todo = wrong
            val audio = rec.audioPath?.let(::File)?.takeIf { it.exists() }
            if (audio != null && !rec.timestampsEstimated) {
                val pieces = wrong.flatMap { LanguageRepair.splitByDuration(segments, it) }
                log("Audio opnieuw beluisteren met Whisper (Nederlands afgedwongen): ${pieces.size} fragment(en).")
                val dutch = mutableListOf<TimedText>()
                val dir = File(applicationContext.cacheDir, "langfix/$id").apply { mkdirs() }
                val whisper = TranscriptionModels.WHISPER
                try {
                    pieces.forEachIndexed { n, piece ->
                        val (t0, t1raw) = LanguageRepair.timeWindow(segments, piece)
                        // The last sentence has no successor: stop at the end of the recording.
                        val t1 = minOf(t1raw, if (rec.durationMs > 0) rec.durationMs else segments[piece.last].endMs + 2_000)
                        val file = File(dir, "piece_$n.m4a")
                        try {
                            val actualStart = AudioFiles.slice(audio, file, t0, t1)
                            val t = c.openAi.transcribe(
                                file, whisper, language,
                                "Dit is een Nederlandstalig gesprek, letterlijk uitgeschreven in het Nederlands.", emptyList(),
                            )
                            t.segments.forEach { s ->
                                if (s.startMs != null) dutch += TimedText(actualStart + s.startMs, actualStart + (s.endMs ?: s.startMs), null, s.text)
                            }
                            log("Fragment ${n + 1}/${pieces.size} (${Format.timestamp(t0)}–${Format.timestamp(t1)}): ${t.segments.size} zinnen.")
                        } catch (e: OpenAiException) {
                            if (e.retryable) throw e
                            log("Fragment ${n + 1}: opnieuw beluisteren lukte niet (${e.message}); wordt vertaald.")
                        } catch (e: IllegalStateException) {
                            log("Fragment ${n + 1}: audio niet leesbaar; wordt vertaald.")
                        } finally {
                            file.delete()
                        }
                        setProgress(workDataOf(KEY_PROGRESS to (n + 1) * 80 / pieces.size))
                    }
                } finally {
                    dir.deleteRecursively()
                }
                val result = LanguageRepair.replace(segments, pieces, dutch, language)
                segments = result.segments
                todo = result.unrepaired
                if (result.repaired > 0) log("${result.repaired} Engelse zinnen vervangen door wat er letterlijk is gezegd; sprekers behouden.")
            } else {
                log(if (audio == null) "Audio is niet meer aanwezig: de zinnen worden vertaald." else "Tijden zijn geschat: de zinnen worden vertaald.")
            }
            if (todo.isNotEmpty()) {
                val indices = todo.flatMap { it.toList() }
                val lines = Translator(c).translateLines(indices.map { segments[it].text }, LanguageFix.languageName(language))
                val list = segments.toMutableList()
                indices.forEachIndexed { k, i -> lines.getOrNull(k)?.takeIf { it.isNotBlank() }?.let { list[i] = list[i].copy(text = it) } }
                segments = list
                log("${indices.size} zinnen naar het Nederlands vertaald (sprekers en tijden ongewijzigd).")
            }
            c.db.segments().replace(
                id,
                segments.mapIndexed { i, s -> SegmentEntity(recordingId = id, position = i, startMs = s.startMs, endMs = s.endMs, speaker = s.speaker, text = s.text) },
            )
            log("Taalcontrole klaar: het transcript staat nu in het Nederlands.")
            Result.success()
        } catch (e: OpenAiException) {
            log("Omzetten mislukt: ${e.message}")
            if (e.retryable && runAttemptCount < 4) Result.retry() else Result.failure()
        } catch (e: IOException) {
            log("Omzetten mislukt (netwerk): ${e.message}")
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "recording_id"
        const val KEY_PROGRESS = "progress"
        fun workName(id: Long) = "langfix_$id"

        fun enqueue(context: Context, id: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(id),
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LanguageFixWorker>()
                    .setInputData(workDataOf(KEY_ID to id))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
