package nl.arnoldkolkman.akonotulist.export

import nl.arnoldkolkman.akonotulist.core.Format

data class ExportLine(val startMs: Long, val speaker: String?, val text: String)

/** A user-set marker with the sentence that was spoken at that moment. */
data class ExportMarker(val timeMs: Long, val context: String?)

data class ExportDoc(
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val lines: List<ExportLine>,
    val summary: String? = null,
    val actionItems: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val timestampsEstimated: Boolean = false,
    val markers: List<ExportMarker> = emptyList(),
)

/** A speaker turn: consecutive lines of the same speaker joined into one paragraph. */
data class Paragraph(val startMs: Long, val speaker: String?, val text: String)

/**
 * Renders a transcript to .txt, .md and .docx. Pure JVM code (no Android APIs) so the output
 * is unit tested. The .docx is a minimal, valid WordprocessingML package with real headings.
 */
object TranscriptExporter {

    fun paragraphs(lines: List<ExportLine>): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        for (l in lines) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == l.speaker && l.speaker != null) {
                out[out.lastIndex] = last.copy(text = last.text + " " + l.text)
            } else {
                out += Paragraph(l.startMs, l.speaker, l.text)
            }
        }
        return out
    }

    private fun meta(d: ExportDoc): String = buildString {
        append(Format.dateTime(d.createdAt))
        if (d.durationMs > 0) append(" · duur ").append(Format.duration(d.durationMs))
        if (d.timestampsEstimated) append(" · tijden geschat")
    }

    fun toText(d: ExportDoc): String = buildString {
        appendLine(d.title)
        appendLine(meta(d))
        appendLine()
        if (!d.summary.isNullOrBlank()) {
            appendLine("SAMENVATTING")
            appendLine(d.summary.trim())
            appendLine()
            if (d.actionItems.isNotEmpty()) {
                appendLine("ACTIEPUNTEN")
                d.actionItems.forEach { appendLine("- $it") }
                appendLine()
            }
            if (d.decisions.isNotEmpty()) {
                appendLine("BESLUITEN")
                d.decisions.forEach { appendLine("- $it") }
                appendLine()
            }
        }
        if (d.markers.isNotEmpty()) {
            appendLine("MARKERINGEN")
            d.markers.forEach { appendLine("★ [${Format.timestamp(it.timeMs)}]" + (it.context?.let { c -> " $c" } ?: "")) }
            appendLine()
        }
        if (!d.summary.isNullOrBlank() || d.markers.isNotEmpty()) appendLine("TRANSCRIPT")
        paragraphs(d.lines).forEach { p ->
            append('[').append(Format.timestamp(p.startMs)).append("] ")
            if (p.speaker != null) append(p.speaker).append(": ")
            appendLine(p.text)
        }
    }.trimEnd() + "\n"

    fun toMarkdown(d: ExportDoc): String = buildString {
        appendLine("# ${md(d.title)}")
        appendLine()
        appendLine("_${meta(d)}_")
        appendLine()
        if (!d.summary.isNullOrBlank()) {
            appendLine("## Samenvatting")
            appendLine()
            appendLine(md(d.summary.trim()))
            appendLine()
            if (d.actionItems.isNotEmpty()) {
                appendLine("## Actiepunten")
                appendLine()
                d.actionItems.forEach { appendLine("- [ ] ${md(it)}") }
                appendLine()
            }
            if (d.decisions.isNotEmpty()) {
                appendLine("## Besluiten")
                appendLine()
                d.decisions.forEach { appendLine("- ${md(it)}") }
                appendLine()
            }
        }
        if (d.markers.isNotEmpty()) {
            appendLine("## Markeringen")
            appendLine()
            d.markers.forEach { appendLine("- ★ `${Format.timestamp(it.timeMs)}`" + (it.context?.let { c -> " " + md(c) } ?: "")) }
            appendLine()
        }
        appendLine("## Transcript")
        appendLine()
        paragraphs(d.lines).forEach { p ->
            append("`").append(Format.timestamp(p.startMs)).append("` ")
            if (p.speaker != null) append("**").append(md(p.speaker)).append(":** ")
            appendLine(md(p.text))
            appendLine()
        }
    }.trimEnd() + "\n"

    /** Escapes characters that would otherwise turn transcript text into Markdown markup. */
    private fun md(s: String): String = s.replace(Regex("([\\\\`*_\\[\\]<>#|])"), "\\\\$1")

    fun toDocx(d: ExportDoc): ByteArray {
        val body = StringBuilder()
        body.append(Docx.para(d.title, style = "Title"))
        body.append(Docx.para(meta(d), italic = true))
        if (!d.summary.isNullOrBlank()) {
            body.append(Docx.para("Samenvatting", style = "Heading1"))
            d.summary.trim().split(Regex("\\n\\s*\\n")).forEach { body.append(Docx.para(it.trim())) }
            if (d.actionItems.isNotEmpty()) {
                body.append(Docx.para("Actiepunten", style = "Heading1"))
                d.actionItems.forEach { body.append(Docx.para("☐ $it")) }
            }
            if (d.decisions.isNotEmpty()) {
                body.append(Docx.para("Besluiten", style = "Heading1"))
                d.decisions.forEach { body.append(Docx.para("• $it")) }
            }
        }
        if (d.markers.isNotEmpty()) {
            body.append(Docx.para("Markeringen", style = "Heading1"))
            d.markers.forEach { body.append(Docx.para("★ [${Format.timestamp(it.timeMs)}]" + (it.context?.let { c -> " $c" } ?: ""))) }
        }
        body.append(Docx.para("Transcript", style = "Heading1"))
        paragraphs(d.lines).forEach { p ->
            body.append("<w:p>")
            body.append(Docx.run("[${Format.timestamp(p.startMs)}] ", color = "777777"))
            if (p.speaker != null) body.append(Docx.run("${p.speaker}: ", bold = true))
            body.append(Docx.run(p.text))
            body.append("</w:p>")
        }

        return Docx.pack(body.toString())
    }

    internal fun xml(s: String): String = Docx.xml(s)
}
