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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import nl.arnoldkolkman.akonotulist.AppContainer
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.data.SegmentEntity
import nl.arnoldkolkman.akonotulist.data.TranslationEntity
import nl.arnoldkolkman.akonotulist.transcribe.ChatModels
import nl.arnoldkolkman.akonotulist.transcribe.OpenAiException
import nl.arnoldkolkman.akonotulist.ui.detail.summaryAsText
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Target languages offered in the app. */
enum class TranslationLanguage(val code: String, val label: String, val englishName: String) {
    EN("en", "Engels", "English"),
    DE("de", "Duits", "German"),
    FR("fr", "Frans", "French");

    companion object {
        fun byCode(code: String?) = entries.firstOrNull { it.code == code }
    }
}

/** Prompts and schemas for translation and name suggestions. Pure, unit tested. */
object TranslationPrompts {
    fun system(lang: TranslationLanguage) = """
        You translate meeting transcripts from Dutch (or mixed languages) into ${lang.englishName}.
        Translate every numbered line faithfully and naturally; keep names, numbers and terminology.
        Do not merge, split, skip or reorder lines: return exactly one translation per input line,
        in the same order, without the line numbers.
    """.trimIndent()

    fun summarySystem(lang: TranslationLanguage) = """
        Translate the following meeting summary into ${lang.englishName}. Keep the structure
        (headings, bullet lines starting with "- ") and all names. Translate the headings too.
    """.trimIndent()

    val linesSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { add("translations") }
        putJsonObject("properties") {
            putJsonObject("translations") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
        }
    }

    val textSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { add("text") }
        putJsonObject("properties") { putJsonObject("text") { put("type", "string") } }
    }

    fun numbered(lines: List<String>) = lines.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")

    fun parseLines(o: JsonObject): List<String> =
        o["translations"]?.jsonArray.orEmpty().map { it.jsonPrimitive.contentOrNull.orEmpty().trim() }

    // --- speaker name suggestions ---------------------------------------------------------

    val namesSystem = """
        Je krijgt een transcript met sprekerlabels (A, B, …). Bepaal per label hoe die persoon heet,
        alleen op basis van duidelijke aanwijzingen: iemand stelt zich voor ("ik ben Jan"), wordt
        aangesproken ("Goedemorgen Jan" → de ándere spreker heet Jan, niet de spreker die het zegt),
        of noemt zichzelf. Twijfel je, geef dan een lege naam. Verzin niets.
    """.trimIndent()

    val namesSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { add("speakers") }
        putJsonObject("properties") {
            putJsonObject("speakers") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonArray("required") { add("label"); add("name") }
                    putJsonObject("properties") {
                        putJsonObject("label") { put("type", "string") }
                        putJsonObject("name") { put("type", "string") }
                    }
                }
            }
        }
    }

    /** Label -> suggested name, only for known labels and non-empty, plausible names. */
    fun parseNames(o: JsonObject, labels: Set<String>): Map<String, String> =
        o["speakers"]?.jsonArray.orEmpty().mapNotNull { el ->
            val obj = el.jsonObject
            val label = obj["label"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (label !in labels || name.isEmpty() || name.length > 40 || name.startsWith("Spreker", ignoreCase = true)) null
            else label to name
        }.toMap()

    /** Transcript for the name prompt: at most [maxChars] characters, labelled lines. */
    fun namesInput(segments: List<SegmentEntity>, maxChars: Int = 60_000): String =
        segments.filter { it.speaker != null }.joinToString("\n") { "${it.speaker}: ${it.text}" }.take(maxChars)
}

class Translator(private val c: AppContainer) {

    private suspend fun model() = c.settings.current().summaryModel.ifBlank { ChatModels.DEFAULT }

    /** Translates [lines]; batches and, on a misaligned answer, splits the batch and retries. */
    suspend fun translateLines(lines: List<String>, lang: TranslationLanguage, onProgress: suspend (Int) -> Unit = {}): List<String> {
        val out = ArrayList<String>(lines.size)
        val batch = 120
        for (from in lines.indices step batch) {
            out += translateBatch(lines.subList(from, minOf(from + batch, lines.size)), lang, depth = 0)
            onProgress(minOf(100, (from + batch) * 100 / lines.size.coerceAtLeast(1)))
        }
        return out
    }

    private suspend fun translateBatch(lines: List<String>, lang: TranslationLanguage, depth: Int): List<String> {
        if (lines.isEmpty()) return emptyList()
        val o = c.openAi.chatJson(model(), TranslationPrompts.system(lang), TranslationPrompts.numbered(lines), "translations", TranslationPrompts.linesSchema)
        val result = TranslationPrompts.parseLines(o)
        if (result.size == lines.size) return result
        if (lines.size == 1 || depth >= 4) return lines.mapIndexed { i, l -> result.getOrNull(i) ?: l }
        val mid = lines.size / 2
        return translateBatch(lines.subList(0, mid), lang, depth + 1) + translateBatch(lines.subList(mid, lines.size), lang, depth + 1)
    }

    suspend fun translateText(text: String, lang: TranslationLanguage): String {
        val o = c.openAi.chatJson(model(), TranslationPrompts.summarySystem(lang), text, "translated_text", TranslationPrompts.textSchema)
        return o["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }

    suspend fun translateRecording(recordingId: Long, lang: TranslationLanguage, onProgress: suspend (Int) -> Unit) {
        val segments = c.db.segments().forRecording(recordingId)
        val texts = translateLines(segments.map { it.text }, lang) { p -> onProgress(p * 9 / 10) }
        val summary = c.db.summaries().get(recordingId)
        val rec = c.db.recordings().get(recordingId)
        val summaryText = summary?.let {
            translateText(summaryAsText(rec?.title.orEmpty(), it.summary, decodeList(it.actionItemsJson), decodeList(it.decisionsJson)), lang)
        }
        c.db.translations().upsert(
            TranslationEntity(recordingId, lang.code, summaryText, encodeList(texts), segments.size, model(), System.currentTimeMillis())
        )
        onProgress(100)
    }

    suspend fun suggestNames(recordingId: Long): Map<String, String> {
        val segments = c.db.segments().forRecording(recordingId)
        val labels = segments.mapNotNull { it.speaker }.toSet()
        if (labels.isEmpty()) return emptyMap()
        val o = c.openAi.chatJson(model(), TranslationPrompts.namesSystem, TranslationPrompts.namesInput(segments), "speaker_names", TranslationPrompts.namesSchema)
        return TranslationPrompts.parseNames(o, labels)
    }
}

/** Background translation (network-constrained, with retries) so it survives leaving the screen. */
class TranslateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val lang = TranslationLanguage.byCode(inputData.getString(KEY_LANG)) ?: return Result.failure()
        return try {
            Translator(applicationContext.appContainer).translateRecording(id, lang) { p -> setProgress(workDataOf(KEY_PROGRESS to p)) }
            Result.success()
        } catch (e: OpenAiException) {
            if (e.retryable && runAttemptCount < 4) Result.retry() else Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: IOException) {
            if (runAttemptCount < 4) Result.retry() else Result.failure(workDataOf(KEY_ERROR to e.message))
        }
    }

    companion object {
        const val KEY_ID = "recording_id"
        const val KEY_LANG = "language"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"

        fun workName(id: Long, lang: TranslationLanguage) = "translate_${id}_${lang.code}"

        fun enqueue(context: Context, id: Long, lang: TranslationLanguage) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(id, lang),
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TranslateWorker>()
                    .setInputData(workDataOf(KEY_ID to id, KEY_LANG to lang.code))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}

