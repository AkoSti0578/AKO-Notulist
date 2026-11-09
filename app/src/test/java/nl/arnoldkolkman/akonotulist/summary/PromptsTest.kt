package nl.arnoldkolkman.akonotulist.summary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import nl.arnoldkolkman.akonotulist.data.SegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptsTest {
    @Test fun parseSummaryFormatsActionItems() {
        val o = Json.parseToJsonElement(
            """{"title":"Budgetoverleg","summary":"Samenvatting.","action_items":[
                {"owner":"Jan","task":"Offerte sturen","due":"vrijdag"},
                {"owner":"","task":"Zaal reserveren","due":""},
                {"owner":"","task":"  ","due":""}],
               "decisions":["Budget omhoog.",""]}"""
        ).jsonObject
        val s = Prompts.parseSummary(o)
        assertEquals("Budgetoverleg", s.title)
        assertEquals(listOf("Jan: Offerte sturen (vrijdag)", "Zaal reserveren"), s.actionItems)
        assertEquals(listOf("Budget omhoog."), s.decisions)
    }

    @Test fun normalizeLabelsRelabelsByFirstAppearance() {
        assertEquals(listOf("A", "B", "A", "C"), Prompts.normalizeLabels(listOf("b", "Spreker 2", "B", "x"), 4))
    }

    @Test fun normalizeLabelsKeepsLabelsForLaterBatches() {
        assertEquals(listOf("B", "A"), Prompts.normalizeLabels(listOf(" b", "a"), 2, relabel = false))
    }

    @Test fun normalizeLabelsRejectsWrongLength() {
        assertNull(Prompts.normalizeLabels(listOf("A"), 2))
    }

    @Test fun transcriptForPromptUsesNamesAndTimestamps() {
        val segs = listOf(
            SegmentEntity(1, 1, 0, 61_000, 62_000, "A", "Hallo."),
            SegmentEntity(2, 1, 1, 63_000, 64_000, "B", "Hoi."),
            SegmentEntity(3, 1, 2, 65_000, 66_000, null, "Ruis."),
        )
        assertEquals(
            "[01:01] Jan: Hallo.\n[01:03] Spreker B: Hoi.\n[01:05] Ruis.",
            Prompts.transcriptForPrompt(segs, mapOf("A" to "Jan")),
        )
    }

    @Test fun listCodecRoundTrips() {
        val items = listOf("a \"quote\"", "ü", "")
        assertEquals(items, decodeList(encodeList(items)))
        assertEquals(emptyList<String>(), decodeList("not json"))
    }

    @Test fun markerHint() {
        assertEquals("", Prompts.markerHint(emptyList()))
        assertEquals(
            "\n\nDe gebruiker markeerde tijdens de opname deze momenten als belangrijk; geef ze extra aandacht: 01:05, 1:00:00.",
            Prompts.markerHint(listOf(65_000, 3_600_000)),
        )
    }
}
