package nl.arnoldkolkman.akonotulist.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class TranscriptExporterTest {
    private val doc = ExportDoc(
        title = "Overleg <budget> & planning",
        createdAt = 1_762_696_800_000, // 9 nov 2025
        durationMs = 125_000,
        lines = listOf(
            ExportLine(0, "Jan", "Goedemorgen."),
            ExportLine(2_000, "Jan", "Zullen we beginnen?"),
            ExportLine(5_000, "Spreker B", "Ja, prima. *Graag* zelfs"),
            ExportLine(65_000, "Jan", "Dan het budget."),
        ),
        summary = "Kort overleg over het budget.",
        actionItems = listOf("Jan: offerte sturen (vrijdag)"),
        decisions = listOf("Budget wordt verhoogd."),
    )

    @Test fun consecutiveLinesOfSameSpeakerBecomeOneParagraph() {
        val p = TranscriptExporter.paragraphs(doc.lines)
        assertEquals(3, p.size)
        assertEquals(Paragraph(0, "Jan", "Goedemorgen. Zullen we beginnen?"), p[0])
        assertEquals(65_000L, p[2].startMs)
    }

    @Test fun linesWithoutSpeakerAreNotMerged() {
        val p = TranscriptExporter.paragraphs(listOf(ExportLine(0, null, "Een."), ExportLine(1_000, null, "Twee.")))
        assertEquals(2, p.size)
    }

    @Test fun textExportHasSectionsAndTimestamps() {
        val txt = TranscriptExporter.toText(doc)
        assertTrue(txt.startsWith("Overleg <budget> & planning\n"))
        assertTrue(txt.contains("SAMENVATTING\nKort overleg over het budget."))
        assertTrue(txt.contains("ACTIEPUNTEN\n- Jan: offerte sturen (vrijdag)"))
        assertTrue(txt.contains("BESLUITEN\n- Budget wordt verhoogd."))
        assertTrue(txt.contains("[00:00] Jan: Goedemorgen. Zullen we beginnen?"))
        assertTrue(txt.contains("[01:05] Jan: Dan het budget."))
        assertTrue(txt.contains("duur 2:05"))
    }

    @Test fun markdownEscapesTranscriptMarkup() {
        val md = TranscriptExporter.toMarkdown(doc)
        assertTrue(md.startsWith("# Overleg \\<budget\\> & planning"))
        assertTrue(md.contains("## Actiepunten"))
        assertTrue(md.contains("- [ ] Jan: offerte sturen (vrijdag)"))
        assertTrue(md.contains("`00:05` **Spreker B:** Ja, prima. \\*Graag\\* zelfs"))
    }

    @Test fun docxIsAValidPackageWithWellFormedXml() {
        val bytes = TranscriptExporter.toDocx(doc)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes()
            }
        }
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels", "word/styles.xml", "word/document.xml"),
            entries.keys,
        )
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        entries.values.forEach { factory.newDocumentBuilder().parse(ByteArrayInputStream(it)) } // throws if malformed
        val documentXml = String(entries["word/document.xml"]!!)
        assertTrue(documentXml.contains("Overleg &lt;budget&gt; &amp; planning"))
        assertTrue(documentXml.contains("<w:pStyle w:val=\"Heading1\"/>"))
        assertTrue(documentXml.contains("Goedemorgen. Zullen we beginnen?"))
    }

    @Test fun xmlEscapingRemovesControlCharacters() {
        assertEquals("a b &amp; c", TranscriptExporter.xml("a\u0001b & c"))
    }

    @Test fun exportWithoutSummaryHasOnlyTranscript() {
        val txt = TranscriptExporter.toText(doc.copy(summary = null))
        assertTrue(!txt.contains("SAMENVATTING"))
        assertTrue(txt.contains("[00:00] Jan: Goedemorgen."))
    }

    @Test fun safeFileNames() {
        assertEquals("Overleg_ 9_11", ExportActions.safeFileName("Overleg/ 9:11"))
        assertEquals("transcript", ExportActions.safeFileName("   "))
    }
}
