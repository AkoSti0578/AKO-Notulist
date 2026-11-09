package nl.arnoldkolkman.akonotulist.export

import nl.arnoldkolkman.akonotulist.core.Format
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportLine(val startMs: Long, val speaker: String?, val text: String)

data class ExportDoc(
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val lines: List<ExportLine>,
    val summary: String? = null,
    val actionItems: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val timestampsEstimated: Boolean = false,
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
            appendLine("TRANSCRIPT")
        }
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
        body.append(para(d.title, style = "Title"))
        body.append(para(meta(d), italic = true))
        if (!d.summary.isNullOrBlank()) {
            body.append(para("Samenvatting", style = "Heading1"))
            d.summary.trim().split(Regex("\\n\\s*\\n")).forEach { body.append(para(it.trim())) }
            if (d.actionItems.isNotEmpty()) {
                body.append(para("Actiepunten", style = "Heading1"))
                d.actionItems.forEach { body.append(para("☐ $it")) }
            }
            if (d.decisions.isNotEmpty()) {
                body.append(para("Besluiten", style = "Heading1"))
                d.decisions.forEach { body.append(para("• $it")) }
            }
        }
        body.append(para("Transcript", style = "Heading1"))
        paragraphs(d.lines).forEach { p ->
            body.append("<w:p>")
            body.append(run("[${Format.timestamp(p.startMs)}] ", color = "777777"))
            if (p.speaker != null) body.append(run("${p.speaker}: ", bold = true))
            body.append(run(p.text))
            body.append("</w:p>")
        }

        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("[Content_Types].xml", CONTENT_TYPES)
            add("_rels/.rels", RELS)
            add("word/_rels/document.xml.rels", DOC_RELS)
            add("word/styles.xml", STYLES)
            add("word/document.xml", document)
        }
        return out.toByteArray()
    }

    private fun para(text: String, style: String? = null, italic: Boolean = false): String = buildString {
        append("<w:p>")
        if (style != null) append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
        append(run(text, italic = italic))
        append("</w:p>")
    }

    private fun run(text: String, bold: Boolean = false, italic: Boolean = false, color: String? = null): String = buildString {
        append("<w:r>")
        if (bold || italic || color != null) {
            append("<w:rPr>")
            if (bold) append("<w:b/>")
            if (italic) append("<w:i/>")
            if (color != null) append("<w:color w:val=\"").append(color).append("\"/>")
            append("</w:rPr>")
        }
        append("<w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r>")
    }

    internal fun xml(s: String): String = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            // XML 1.0 forbids most control characters.
            ch < ' ' && ch != '\t' -> append(' ')
            else -> append(ch)
        }
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:lang w:val="nl-NL"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="60"/></w:pPr><w:rPr><w:sz w:val="48"/><w:color w:val="0E5E6F"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="30"/><w:color w:val="0E5E6F"/></w:rPr></w:style></w:styles>"""
}
