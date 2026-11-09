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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import nl.arnoldkolkman.akonotulist.AppContainer
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import nl.arnoldkolkman.akonotulist.data.SegmentEntity
import nl.arnoldkolkman.akonotulist.data.SpeakerEntity
import nl.arnoldkolkman.akonotulist.data.SummaryEntity
import nl.arnoldkolkman.akonotulist.transcribe.OpenAiException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of the summary call, already formatted for display. */
data class MeetingSummary(
    val title: String,
    val summary: String,
    val actionItems: List<String>,
    val decisions: List<String>,
)

/** Prompts and JSON schemas for the chat model. Pure so they can be unit tested. */
object Prompts {
    val summarySystem = """
        Je bent een nauwkeurige notulist. Je krijgt het transcript van een opname (vaak een
        vergadering, soms een memo of gesprek). Schrijf in correct, zakelijk Nederlands.
        - "title": korte, inhoudelijke titel (max. 8 woorden), zonder datum.
        - "summary": samenvatting van 3 tot 10 zinnen; noem sprekers bij naam als die bekend zijn.
        - "action_items": concrete actiepunten. "owner" is de verantwoordelijke (leeg als onbekend),
          "task" de actie, "due" de deadline zoals genoemd (leeg als niet genoemd).
        - "decisions": genomen besluiten, elk als één zin.
        Verzin niets: gebruik alleen wat in het transcript staat. Lege lijsten zijn toegestaan.
    """.trimIndent()

    val summarySchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { add("title"); add("summary"); add("action_items"); add("decisions") }
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string") }
            putJsonObject("summary") { put("type", "string") }
            putJsonObject("action_items") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonArray("required") { add("owner"); add("task"); add("due") }
                    putJsonObject("properties") {
                        putJsonObject("owner") { put("type", "string") }
                        putJsonObject("task") { put("type", "string") }
                        putJsonObject("due") { put("type", "string") }
                    }
                }
            }
            putJsonObject("decisions") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
        }
    }

    val speakerSystem = """
        Je krijgt genummerde zinnen uit een transcript zonder sprekerinformatie. Bepaal per zin
        welke spreker hem uitspreekt, op basis van inhoud, aanspreekvormen, vraag/antwoord en
        beurtwisselingen. Gebruik labels "A", "B", "C", … in volgorde van eerste optreden.
        Gebruik zo min mogelijk verschillende sprekers; is er waarschijnlijk maar één spreker
        (bijv. een memo), gebruik dan overal "A". Geef exact één label per zin, in dezelfde volgorde.
    """.trimIndent()

    val speakerSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { add("labels") }
        putJsonObject("properties") {
            putJsonObject("labels") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
        }
    }

    /** Transcript as plain lines "[mm:ss] Naam: tekst" for the summary prompt. */
    fun transcriptForPrompt(segments: List<SegmentEntity>, names: Map<String, String>): String =
        segments.joinToString("\n") { s ->
            val who = s.speaker?.let { names[it] ?: "Spreker $it" }
            "[${Format.timestamp(s.startMs)}] " + (if (who != null) "$who: " else "") + s.text
        }

    fun parseSummary(o: JsonObject): MeetingSummary {
        fun str(el: JsonObject, k: String) = el[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val actions = o["action_items"]?.jsonArray.orEmpty().map { it.jsonObject }.map { a ->
            val owner = str(a, "owner")
            val due = str(a, "due")
            buildString {
                if (owner.isNotEmpty()) append(owner).append(": ")
                append(str(a, "task"))
                if (due.isNotEmpty()) append(" (").append(due).append(")")
            }
        }.filter { it.isNotBlank() }
        val decisions = o["decisions"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
        return MeetingSummary(str(o, "title"), str(o, "summary"), actions, decisions)
    }

    /** Validates/normalises the labels the model returned for [count] lines. */
    fun normalizeLabels(raw: List<String>, count: Int, relabel: Boolean = true): List<String>? {
        if (raw.size != count) return null
        val clean = raw.map { it.trim().uppercase().filter { ch -> ch.isLetterOrDigit() }.take(3).ifEmpty { "A" } }
        if (!relabel) return clean
        // Relabel in order of first appearance so the result is always A, B, C, …
        val order = LinkedHashMap<String, String>()
        return clean.map { l -> order.getOrPut(l) { ('A' + order.size.coerceAtMost(25)).toString() } }
    }
}

private val listJson = Json

fun encodeList(items: List<String>): String = JsonArray(items.map { JsonPrimitive(it) }).toString()
fun decodeList(json: String): List<String> =
    runCatching { listJson.parseToJsonElement(json).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList())

/** Runs the optional post-processing (speaker estimation, summary) after transcription. */
class PostProcessor(private val c: AppContainer) {

    suspend fun estimateSpeakers(recordingId: Long, model: String) {
        val segments = c.db.segments().forRecording(recordingId)
        if (segments.isEmpty() || segments.any { it.speaker != null }) return
        val labels = ArrayList<String>(segments.size)
        val batch = 250
        for (from in segments.indices step batch) {
            val part = segments.subList(from, minOf(from + batch, segments.size))
            val context = if (from > 0) {
                val start = maxOf(0, from - 8)
                "Context: de vorige zinnen, al gelabeld. Gebruik dezelfde labels voor dezelfde sprekers.\n" +
                    (start until from).joinToString("\n") { j -> "${labels[j]}: ${segments[j].text}" } + "\n\nTe labelen:\n"
            } else ""
            val user = context + part.mapIndexed { i, s -> "${i + 1}. ${s.text}" }.joinToString("\n")
            val out = c.openAi.chatJson(model, Prompts.speakerSystem, user, "speaker_labels", Prompts.speakerSchema)
            val raw = out["labels"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
            // First batch: relabel to A, B, … by appearance. Later batches must keep the context's labels.
            val normalized = Prompts.normalizeLabels(raw, part.size, relabel = from == 0) ?: return // inconsistent: keep unlabeled
            labels += normalized
        }
        val updated = segments.mapIndexed { i, s -> s.copy(id = 0, speaker = labels[i]) }
        c.db.segments().replace(recordingId, updated)
        c.db.speakers().deleteFor(recordingId)
        c.db.speakers().insertAll(labels.distinct().sorted().map { SpeakerEntity(recordingId, it, "Spreker $it") })
    }

    suspend fun summarize(recordingId: Long, model: String) {
        val segments = c.db.segments().forRecording(recordingId)
        if (segments.isEmpty()) return
        val names = c.db.speakers().forRecording(recordingId).associate { it.label to it.displayName }
        val text = Prompts.transcriptForPrompt(segments, names)
        val out = c.openAi.chatJson(model, Prompts.summarySystem, text, "meeting_summary", Prompts.summarySchema)
        val s = Prompts.parseSummary(out)
        c.db.summaries().upsert(
            SummaryEntity(recordingId, s.summary, encodeList(s.actionItems), encodeList(s.decisions), model, System.currentTimeMillis())
        )
        val rec = c.db.recordings().get(recordingId) ?: return
        // Replace the generic "Opname 9 nov 14:03" title with something meaningful.
        if (s.title.isNotBlank() && rec.title == Format.defaultTitle(rec.createdAt)) {
            c.db.recordings().rename(recordingId, s.title.take(80))
        }
    }
}

/** Speaker estimation + summary, network-constrained with backoff. Never fails the transcript. */
class PostProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        val id = inputData.getLong(KEY_ID, -1)
        val force = inputData.getBoolean(KEY_FORCE_SUMMARY, false)
        val s = c.settings.current()
        if (c.db.recordings().get(id) == null) return Result.failure()
        val processor = PostProcessor(c)
        c.db.recordings().setStatus(id, RecordingStatus.SUMMARIZING, 95, null, null)
        return try {
            val model = s.summaryModel.ifBlank { nl.arnoldkolkman.akonotulist.transcribe.ChatModels.DEFAULT }
            if (s.estimateSpeakers && !force) processor.estimateSpeakers(id, model)
            if (s.summaryEnabled || force) processor.summarize(id, model)
            c.finishRecording(id, null)
            Result.success()
        } catch (e: OpenAiException) {
            if (e.retryable && runAttemptCount < 5) Result.retry() else {
                c.finishRecording(id, "Nabewerking mislukt: ${e.message}")
                Result.failure()
            }
        } catch (e: IOException) {
            if (runAttemptCount < 5) Result.retry() else {
                c.finishRecording(id, "Nabewerking mislukt: ${e.message}")
                Result.failure()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            c.finishRecording(id, "Nabewerking mislukt: ${e.message ?: e.javaClass.simpleName}")
            Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "recording_id"
        const val KEY_FORCE_SUMMARY = "force_summary"

        fun enqueue(context: Context, id: Long, forceSummary: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "postprocess_$id",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PostProcessWorker>()
                    .setInputData(workDataOf(KEY_ID to id, KEY_FORCE_SUMMARY to forceSummary))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
