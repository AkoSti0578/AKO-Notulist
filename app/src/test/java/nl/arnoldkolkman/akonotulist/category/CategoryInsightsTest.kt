package nl.arnoldkolkman.akonotulist.category

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import nl.arnoldkolkman.akonotulist.data.ActionItemStatus
import nl.arnoldkolkman.akonotulist.data.CategoryEntity
import nl.arnoldkolkman.akonotulist.data.RecordingCategoryRef
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.RecordingSource
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import nl.arnoldkolkman.akonotulist.data.SummaryEntity
import nl.arnoldkolkman.akonotulist.summary.encodeList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class CategoryInsightsTest {
    private fun rec(id: Long, createdAt: Long, durationMs: Long = 60_000, cost: Double? = 0.01) = RecordingEntity(
        id = id, title = "Opname $id", createdAt = createdAt, durationMs = durationMs, audioPath = null,
        source = RecordingSource.MICROPHONE, status = RecordingStatus.DONE, estimatedCostUsd = cost,
    )

    private fun sum(id: Long, actions: List<String>, decisions: List<String> = emptyList()) =
        SummaryEntity(id, "Samenvatting $id", encodeList(actions), encodeList(decisions), "m", 0)

    private val werk = CategoryEntity(1, "Werk", "💼", 0, "Zakelijk", 0)
    private val klant = CategoryEntity(2, "Klanten", "🤝", 2, "", 1)

    private val r1 = rec(1, 1_000)
    private val r2 = rec(2, 2_000, durationMs = 120_000, cost = null)
    private val r3 = rec(3, 3_000)
    private val refs = listOf(RecordingCategoryRef(1, 1), RecordingCategoryRef(2, 1), RecordingCategoryRef(2, 2, byAi = true))

    @Test fun filterAllCategoryAndUncategorized() {
        val all = listOf(r1, r2, r3)
        assertEquals(all, CategoryInsights.filter(all, refs, CategoryFilter.All))
        assertEquals(listOf(r1, r2), CategoryInsights.filter(all, refs, CategoryFilter.Category(1)))
        assertEquals(listOf(r2), CategoryInsights.filter(all, refs, CategoryFilter.Category(2)))
        assertEquals(listOf(r3), CategoryInsights.filter(all, refs, CategoryFilter.Uncategorized))
        assertEquals(1, CategoryInsights.uncategorizedCount(all, refs))
        assertEquals(mapOf(1L to listOf(1L), 2L to listOf(1L, 2L)), CategoryInsights.categoriesByRecording(refs))
    }

    @Test fun filterEncodingRoundTrips() {
        listOf(CategoryFilter.All, CategoryFilter.Uncategorized, CategoryFilter.Category(42)).forEach {
            assertEquals(it, CategoryFilter.decode(it.encode()))
        }
        assertEquals(CategoryFilter.All, CategoryFilter.decode("garbage"))
    }

    @Test fun actionsOpenFirstNewestFirstAndStaleTicksIgnored() {
        val summaries = listOf(sum(1, listOf("Jan: offerte", "Zaal boeken")), sum(2, listOf("Agenda sturen")))
        val statuses = listOf(
            ActionItemStatus(1, 0, "Jan: offerte", done = true),
            // stale: text changed after a regenerated summary -> must NOT count as done
            ActionItemStatus(1, 1, "Iets anders", done = true),
        )
        val rows = CategoryInsights.actions(listOf(r1, r2), summaries, statuses)
        assertEquals(listOf("Agenda sturen", "Zaal boeken", "Jan: offerte"), rows.map { it.text })
        assertEquals(listOf(false, false, true), rows.map { it.done })
        assertEquals("Opname 2", rows.first().recordingTitle)
    }

    @Test fun actionsOnlyForRecordingsInScope() {
        val rows = CategoryInsights.actions(listOf(r1), listOf(sum(1, listOf("a")), sum(3, listOf("b"))), emptyList())
        assertEquals(listOf("a"), rows.map { it.text })
    }

    @Test fun decisionsNewestFirst() {
        val d = CategoryInsights.decisions(listOf(r1, r2), listOf(sum(1, emptyList(), listOf("Oud")), sum(2, emptyList(), listOf("Nieuw"))))
        assertEquals(listOf("Nieuw", "Oud"), d.map { it.text })
    }

    @Test fun statsSumDurationCostAndActions() {
        val summaries = listOf(sum(1, listOf("a", "b"), listOf("x")))
        val actions = CategoryInsights.actions(listOf(r1, r2), summaries, listOf(ActionItemStatus(1, 0, "a", true)))
        val s = CategoryInsights.stats(listOf(r1, r2), actions, CategoryInsights.decisions(listOf(r1, r2), summaries))
        assertEquals(2, s.recordings)
        assertEquals(180_000L, s.totalDurationMs)
        assertEquals(0.01, s.totalCostUsd, 1e-9)
        assertEquals(1, s.openActions)
        assertEquals(1, s.doneActions)
        assertEquals(1, s.decisions)
        assertEquals(1_000L, s.firstAt)
        assertEquals(2_000L, s.lastAt)
    }

    @Test fun moveReordersAndNormalisesSortOrder() {
        val c3 = CategoryEntity(3, "Privé", sortOrder = 7)
        val moved = CategoryInsights.move(listOf(werk, klant, c3), 3, -1)
        assertEquals(listOf(1L, 3L, 2L), moved.map { it.id })
        assertEquals(listOf(0, 1, 2), moved.map { it.sortOrder })
        // moving the first one up is a no-op (but still normalised)
        assertEquals(listOf(1L, 2L, 3L), CategoryInsights.move(listOf(werk, klant, c3), 1, -1).map { it.id })
    }

    @Test fun labelWithAndWithoutEmoji() {
        assertEquals("💼 Werk", CategoryInsights.label(werk))
        assertEquals("Leeg", CategoryInsights.label(CategoryEntity(name = "Leeg")))
    }

    @Test fun promptParsingMatchesNamesLooselyAndCapsAtTwo() {
        val o = Json.parseToJsonElement("""{"categories":["werk","🤝 Klanten","Werk","Bestaat niet","Privé"]}""").jsonObject
        assertEquals(listOf(1L, 2L), CategoryPrompts.parse(o, listOf(werk, klant, CategoryEntity(3, "Privé"))))
    }

    @Test fun promptMessageListsCategoriesWithDescriptions() {
        val msg = CategoryPrompts.userMessage(listOf(werk, klant), "Overleg", "Kort.", "Hallo allemaal")
        assertTrue(msg.contains("- Werk: Zakelijk\n- Klanten\n"))
        assertTrue(msg.contains("Titel: Overleg") && msg.contains("Samenvatting: Kort.") && msg.contains("Hallo allemaal"))
    }

    private fun reportInput(): CategoryReport.Input {
        val recordings = listOf(r2, r1)
        val summaries = listOf(sum(1, listOf("a"), listOf("Besluit <x> & y")), sum(2, listOf("b")))
        val actions = CategoryInsights.actions(recordings, summaries, listOf(ActionItemStatus(2, 0, "b", true)))
        val decisions = CategoryInsights.decisions(recordings, summaries)
        return CategoryReport.Input(werk, recordings, summaries, actions, decisions, CategoryInsights.stats(recordings, actions, decisions))
    }

    @Test fun reportTextAndMarkdown() {
        val txt = CategoryReport.toText(reportInput())
        assertTrue(txt.startsWith("💼 Werk\nZakelijk\n2 opname(s) · 3:00 · 1 open / 1 afgerond actiepunt(en)"))
        assertTrue(txt.contains("OPEN ACTIEPUNTEN\n☐ a  — Opname 1"))
        assertTrue(txt.contains("AFGERONDE ACTIEPUNTEN\n☑ b  — Opname 2"))
        val md = CategoryReport.toMarkdown(reportInput())
        assertTrue(md.contains("- [ ] a — _Opname 1_"))
        assertTrue(md.contains("- [x] b — _Opname 2_"))
        assertTrue(md.contains("### Opname 2"))
    }

    @Test fun reportDocxIsWellFormed() {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(CategoryReport.toDocx(reportInput()))).use { zip ->
            while (true) { val e = zip.nextEntry ?: break; entries[e.name] = zip.readBytes() }
        }
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        entries.values.forEach { f.newDocumentBuilder().parse(ByteArrayInputStream(it)) }
        assertTrue(String(entries.getValue("word/document.xml")).contains("Besluit &lt;x&gt; &amp; y"))
    }
}
