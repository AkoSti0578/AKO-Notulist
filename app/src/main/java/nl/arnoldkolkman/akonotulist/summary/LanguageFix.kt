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
import nl.arnoldkolkman.akonotulist.core.LanguageGuess
import nl.arnoldkolkman.akonotulist.transcribe.ChunkTranscript
import nl.arnoldkolkman.akonotulist.transcribe.OpenAiException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Puts transcripts back into the configured language when OpenAI answered in English. */
object LanguageFix {
    /** Blocks of sentences checked/translated together (enough text for a reliable guess). */
    const val BLOCK = 40

    fun languageName(code: String?): String = when (code?.lowercase()?.take(2)) {
        "nl" -> "Dutch"
        "en" -> "English"
        "de" -> "German"
        "fr" -> "French"
        "es" -> "Spanish"
        else -> "Dutch"
    }

    /** Indices of the blocks of [texts] that are in the wrong language for [language]. */
    fun wrongBlocks(texts: List<String>, language: String?): List<Int> =
        texts.chunked(BLOCK).withIndex()
            .filter { (_, block) -> LanguageGuess.isWrongLanguage(block.joinToString(" "), language) }
            .map { it.index }

    /** Translates every segment of a chunk; structure (speakers, times) stays identical. */
    suspend fun translate(translator: Translator, t: ChunkTranscript, language: String): ChunkTranscript {
        if (t.segments.isEmpty()) return t
        val texts = translator.translateLines(t.segments.map { it.text }, languageName(language))
        val segments = t.segments.mapIndexed { i, s -> s.copy(text = texts.getOrNull(i)?.ifBlank { null } ?: s.text) }
        return t.copy(text = segments.joinToString(" ") { it.text }, segments = segments)
    }
}

/** Converts an existing (partly) English transcript into the configured language. */
class LanguageFixWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        val id = inputData.getLong(KEY_ID, -1)
        val rec = c.db.recordings().get(id) ?: return Result.failure()
        val language = rec.language ?: c.settings.current().language
        val segments = c.db.segments().forRecording(id)
        val blocks = LanguageFix.wrongBlocks(segments.map { it.text }, language)
        if (blocks.isEmpty()) {
            c.processingLog.add(id, "Taalcontrole: transcript is al ${LanguageFix.languageName(language)}; niets te doen.")
            return Result.success()
        }
        c.processingLog.add(id, "Taalcontrole: ${blocks.size} blok(ken) van max. ${LanguageFix.BLOCK} zinnen zijn Engels; omzetten naar het Nederlands…")
        return try {
            val translator = Translator(c)
            val texts = segments.map { it.text }.toMutableList()
            blocks.forEachIndexed { n, b ->
                val from = b * LanguageFix.BLOCK
                val to = minOf(from + LanguageFix.BLOCK, texts.size)
                val out = translator.translateLines(texts.subList(from, to).toList(), LanguageFix.languageName(language))
                out.forEachIndexed { i, t -> if (t.isNotBlank()) texts[from + i] = t }
                setProgress(workDataOf(KEY_PROGRESS to (n + 1) * 100 / blocks.size))
            }
            c.db.segments().replace(id, segments.mapIndexed { i, s -> s.copy(id = 0, text = texts[i]) })
            c.processingLog.add(id, "Taalcontrole klaar: transcript staat nu in het Nederlands (sprekers en tijden ongewijzigd).")
            Result.success()
        } catch (e: OpenAiException) {
            c.processingLog.add(id, "Omzetten mislukt: ${e.message}")
            if (e.retryable && runAttemptCount < 4) Result.retry() else Result.failure()
        } catch (e: IOException) {
            c.processingLog.add(id, "Omzetten mislukt (netwerk): ${e.message}")
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
