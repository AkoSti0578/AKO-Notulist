package nl.arnoldkolkman.akonotulist.category

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.ActionItemStatus
import nl.arnoldkolkman.akonotulist.data.CategoryEntity
import nl.arnoldkolkman.akonotulist.data.RecordingCategoryRef
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.SummaryEntity
import nl.arnoldkolkman.akonotulist.export.Docx
import nl.arnoldkolkman.akonotulist.summary.decodeList

/** What the recordings list is filtered on. */
sealed interface CategoryFilter {
    data object All : CategoryFilter
    data object Uncategorized : CategoryFilter
    data class Category(val id: Long) : CategoryFilter

    /** Compact form for rememberSaveable: "all", "none" or the category id. */
    fun encode(): String = when (this) {
        All -> "all"
        Uncategorized -> "none"
        is Category -> id.toString()
    }

    companion object {
        fun decode(s: String?): CategoryFilter = when (s) {
            null, "all" -> All
            "none" -> Uncategorized
            else -> s.toLongOrNull()?.let(::Category) ?: All
        }
    }
}

/** One action item across a category, with the recording it came from. */
data class ActionRow(
    val recordingId: Long,
    val recordingTitle: String,
    val recordingCreatedAt: Long,
    val index: Int,
    val text: String,
    val done: Boolean,
)

data class DecisionRow(val recordingId: Long, val recordingTitle: String, val recordingCreatedAt: Long, val text: String)

data class CategoryStats(
    val recordings: Int,
    val totalDurationMs: Long,
    val totalCostUsd: Double,
    val openActions: Int,
    val doneActions: Int,
    val decisions: Int,
    val firstAt: Long?,
    val lastAt: Long?,
)

/** Pure aggregation over a category's recordings — the heart of the category dashboard. */
object CategoryInsights {

    fun filter(recordings: List<RecordingEntity>, refs: List<RecordingCategoryRef>, f: CategoryFilter): List<RecordingEntity> =
        when (f) {
            CategoryFilter.All -> recordings
            CategoryFilter.Uncategorized -> {
                val filed = refs.mapTo(HashSet()) { it.recordingId }
                recordings.filter { it.id !in filed }
            }
            is CategoryFilter.Category -> {
                val ids = refs.filter { it.categoryId == f.id }.mapTo(HashSet()) { it.recordingId }
                recordings.filter { it.id in ids }
            }
        }

    fun uncategorizedCount(recordings: List<RecordingEntity>, refs: List<RecordingCategoryRef>): Int =
        filter(recordings, refs, CategoryFilter.Uncategorized).size

    /** Category ids per recording (for the coloured chips in the list). */
    fun categoriesByRecording(refs: List<RecordingCategoryRef>): Map<Long, List<Long>> =
        refs.groupBy({ it.recordingId }, { it.categoryId })

    /**
     * All action items of the given recordings, open ones first (newest recording first), then the
     * done ones. A status row only counts when its text still matches: after a regenerated summary
     * the items may differ, and an old tick must not mark a new item as done.
     */
    fun actions(
        recordings: List<RecordingEntity>,
        summaries: List<SummaryEntity>,
        statuses: List<ActionItemStatus>,
    ): List<ActionRow> {
        val byId = recordings.associateBy { it.id }
        val status = statuses.associateBy { it.recordingId to it.itemIndex }
        return summaries.filter { it.recordingId in byId }.flatMap { s ->
            val rec = byId.getValue(s.recordingId)
            decodeList(s.actionItemsJson).mapIndexed { i, text ->
                val st = status[s.recordingId to i]
                ActionRow(rec.id, rec.title, rec.createdAt, i, text, done = st != null && st.done && st.text == text)
            }
        }.sortedWith(compareBy<ActionRow> { it.done }.thenByDescending { it.recordingCreatedAt }.thenBy { it.index })
    }

    fun decisions(recordings: List<RecordingEntity>, summaries: List<SummaryEntity>): List<DecisionRow> {
        val byId = recordings.associateBy { it.id }
        return summaries.filter { it.recordingId in byId }.flatMap { s ->
            val rec = byId.getValue(s.recordingId)
            decodeList(s.decisionsJson).map { DecisionRow(rec.id, rec.title, rec.createdAt, it) }
        }.sortedByDescending { it.recordingCreatedAt }
    }

    fun stats(recordings: List<RecordingEntity>, actions: List<ActionRow>, decisions: List<DecisionRow>): CategoryStats =
        CategoryStats(
            recordings = recordings.size,
            totalDurationMs = recordings.sumOf { it.durationMs },
            totalCostUsd = recordings.sumOf { it.estimatedCostUsd ?: 0.0 },
            openActions = actions.count { !it.done },
            doneActions = actions.count { it.done },
            decisions = decisions.size,
            firstAt = recordings.minOfOrNull { it.createdAt },
            lastAt = recordings.maxOfOrNull { it.createdAt },
        )

    /** Moves category [id] one place up (-1) or down (+1); returns the categories with new sortOrders. */
    fun move(categories: List<CategoryEntity>, id: Long, delta: Int): List<CategoryEntity> {
        val list = categories.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return categories.mapIndexed { k, c -> c.copy(sortOrder = k) }
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        return list.mapIndexed { k, c -> c.copy(sortOrder = k) }
    }

    fun label(c: CategoryEntity): String = (if (c.emoji.isBlank()) "" else c.emoji.trim() + " ") + c.name
}

/** A category report (all summaries, action items and decisions) for sharing. */
object CategoryReport {
    data class Input(
        val category: CategoryEntity,
        val recordings: List<RecordingEntity>,
        val summaries: List<SummaryEntity>,
        val actions: List<ActionRow>,
        val decisions: List<DecisionRow>,
        val stats: CategoryStats,
    )

    private fun period(s: CategoryStats): String =
        if (s.firstAt == null || s.lastAt == null) "" else "${Format.dateTime(s.firstAt)} – ${Format.dateTime(s.lastAt)}"

    private fun statLine(s: CategoryStats) =
        "${s.recordings} opname(s) · ${Format.duration(s.totalDurationMs)} · ${s.openActions} open / ${s.doneActions} afgerond actiepunt(en)"

    fun toText(r: Input): String = buildString {
        appendLine(CategoryInsights.label(r.category))
        if (r.category.description.isNotBlank()) appendLine(r.category.description.trim())
        appendLine(statLine(r.stats))
        period(r.stats).takeIf { it.isNotEmpty() }?.let { appendLine(it) }
        appendLine()
        appendLine("OPEN ACTIEPUNTEN")
        r.actions.filter { !it.done }.ifEmpty { null }?.forEach { appendLine("☐ ${it.text}  — ${it.recordingTitle}") }
            ?: appendLine("(geen)")
        val done = r.actions.filter { it.done }
        if (done.isNotEmpty()) {
            appendLine()
            appendLine("AFGERONDE ACTIEPUNTEN")
            done.forEach { appendLine("☑ ${it.text}  — ${it.recordingTitle}") }
        }
        if (r.decisions.isNotEmpty()) {
            appendLine()
            appendLine("BESLUITEN")
            r.decisions.forEach { appendLine("• ${it.text}  — ${it.recordingTitle}") }
        }
        val sums = r.summaries.associateBy { it.recordingId }
        appendLine()
        appendLine("OPNAMES")
        r.recordings.forEach { rec ->
            appendLine()
            appendLine("${rec.title} (${Format.dateTime(rec.createdAt)}, ${Format.duration(rec.durationMs)})")
            sums[rec.id]?.summary?.takeIf { it.isNotBlank() }?.let { appendLine(it.trim()) }
        }
    }.trimEnd() + "\n"

    fun toMarkdown(r: Input): String = buildString {
        appendLine("# ${CategoryInsights.label(r.category)}")
        appendLine()
        if (r.category.description.isNotBlank()) appendLine("> ${r.category.description.trim()}").appendLine()
        appendLine("_${statLine(r.stats)}${period(r.stats).let { if (it.isEmpty()) "" else " · $it" }}_")
        appendLine()
        appendLine("## Actiepunten")
        appendLine()
        if (r.actions.isEmpty()) appendLine("_Geen actiepunten._")
        r.actions.forEach { appendLine("- [${if (it.done) "x" else " "}] ${it.text} — _${it.recordingTitle}_") }
        appendLine()
        if (r.decisions.isNotEmpty()) {
            appendLine("## Besluiten")
            appendLine()
            r.decisions.forEach { appendLine("- ${it.text} — _${it.recordingTitle}_") }
            appendLine()
        }
        appendLine("## Opnames")
        val sums = r.summaries.associateBy { it.recordingId }
        r.recordings.forEach { rec ->
            appendLine()
            appendLine("### ${rec.title}")
            appendLine()
            appendLine("_${Format.dateTime(rec.createdAt)} · ${Format.duration(rec.durationMs)}_")
            sums[rec.id]?.summary?.takeIf { it.isNotBlank() }?.let { appendLine().appendLine(it.trim()) }
        }
    }.trimEnd() + "\n"

    fun toDocx(r: Input): ByteArray {
        val b = StringBuilder()
        b.append(Docx.para(CategoryInsights.label(r.category), style = "Title"))
        if (r.category.description.isNotBlank()) b.append(Docx.para(r.category.description.trim(), italic = true))
        b.append(Docx.para(statLine(r.stats) + period(r.stats).let { if (it.isEmpty()) "" else " · $it" }, italic = true))
        b.append(Docx.para("Actiepunten", style = "Heading1"))
        if (r.actions.isEmpty()) b.append(Docx.para("Geen actiepunten."))
        r.actions.forEach { b.append(Docx.para("${if (it.done) "☑" else "☐"} ${it.text} — ${it.recordingTitle}")) }
        if (r.decisions.isNotEmpty()) {
            b.append(Docx.para("Besluiten", style = "Heading1"))
            r.decisions.forEach { b.append(Docx.para("• ${it.text} — ${it.recordingTitle}")) }
        }
        b.append(Docx.para("Opnames", style = "Heading1"))
        val sums = r.summaries.associateBy { it.recordingId }
        r.recordings.forEach { rec ->
            b.append("<w:p>")
            b.append(Docx.run(rec.title, bold = true))
            b.append(Docx.run("  ${Format.dateTime(rec.createdAt)} · ${Format.duration(rec.durationMs)}", color = "777777"))
            b.append("</w:p>")
            sums[rec.id]?.summary?.takeIf { it.isNotBlank() }?.let { b.append(Docx.para(it.trim())) }
        }
        return Docx.pack(b.toString())
    }
}

/** Prompt + schema for automatic categorisation. Pure, unit tested. */
object CategoryPrompts {
    val system = """
        Je deelt opnames (vergaderingen, gesprekken, memo's) in bij de categorieën van de gebruiker.
        Kies 0, 1 of hooguit 2 categorieën uit de lijst die duidelijk passen. Gebruik de naam exact
        zoals in de lijst. Past geen enkele categorie goed, geef dan een lege lijst. Verzin geen
        nieuwe categorieën.
    """.trimIndent()

    val schema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { add("categories") }
        putJsonObject("properties") {
            putJsonObject("categories") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
        }
    }

    fun userMessage(categories: List<CategoryEntity>, title: String, summary: String?, transcriptHead: String): String = buildString {
        appendLine("Categorieën:")
        categories.forEach { c ->
            append("- ").append(c.name)
            if (c.description.isNotBlank()) append(": ").append(c.description.trim())
            appendLine()
        }
        appendLine()
        appendLine("Titel: $title")
        if (!summary.isNullOrBlank()) appendLine("Samenvatting: ${summary.trim()}")
        if (transcriptHead.isNotBlank()) appendLine("Begin van het transcript: ${transcriptHead.take(6_000)}")
    }

    /** Maps the model's answer onto category ids (case/emoji-insensitive), max. 2, no duplicates. */
    fun parse(o: JsonObject, categories: List<CategoryEntity>): List<Long> {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val byName = categories.associateBy { norm(it.name) }
        return o["categories"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .mapNotNull { byName[norm(it)]?.id }
            .distinct()
            .take(2)
    }
}
