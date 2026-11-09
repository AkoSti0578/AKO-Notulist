package nl.arnoldkolkman.akonotulist.summary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import nl.arnoldkolkman.akonotulist.data.SegmentEntity
import nl.arnoldkolkman.akonotulist.ui.detail.translationAsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPromptsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun numberedLines() {
        assertEquals("1. Hallo.\n2. Dag.", TranslationPrompts.numbered(listOf("Hallo.", "Dag.")))
    }

    @Test fun parseLinesTrims() {
        assertEquals(listOf("Hello.", "Bye."), TranslationPrompts.parseLines(obj("""{"translations":[" Hello. ","Bye."]}""")))
    }

    @Test fun systemPromptNamesTargetLanguage() {
        assertTrue(TranslationPrompts.system(TranslationLanguage.DE).contains("German"))
        assertTrue(TranslationPrompts.summarySystem(TranslationLanguage.FR).contains("French"))
    }

    @Test fun parseNamesKeepsOnlyKnownLabelsAndRealNames() {
        val names = TranslationPrompts.parseNames(
            obj(
                """{"speakers":[
                {"label":"a","name":"Jan"},
                {"label":"B","name":""},
                {"label":"C","name":"Spreker C"},
                {"label":"Z","name":"Piet"}]}"""
            ),
            labels = setOf("A", "B", "C"),
        )
        assertEquals(mapOf("A" to "Jan"), names)
    }

    @Test fun namesInputUsesLabelsAndSkipsUnlabelled() {
        val segs = listOf(
            SegmentEntity(1, 1, 0, 0, 1, "A", "Goedemorgen Jan."),
            SegmentEntity(2, 1, 1, 1, 2, null, "Ruis."),
            SegmentEntity(3, 1, 2, 2, 3, "B", "Morgen Arnold."),
        )
        assertEquals("A: Goedemorgen Jan.\nB: Morgen Arnold.", TranslationPrompts.namesInput(segs))
        assertEquals(5, TranslationPrompts.namesInput(segs, maxChars = 5).length)
    }

    @Test fun translationTextUsesTranslatedLinesAndNames() {
        val segs = listOf(
            SegmentEntity(1, 1, 0, 0, 1, "A", "Goedemorgen."),
            SegmentEntity(2, 1, 1, 65_000, 66_000, "B", "Hoi."),
        )
        val txt = translationAsText(
            "Overleg", TranslationLanguage.EN, "Summary\nShort.", segs, listOf("Good morning."), mapOf("A" to "Jan"),
        )
        // Second line has no translation (misaligned answer): falls back to the original text.
        assertEquals("Summary\nShort.\n\nTranscript\n[00:00] Jan: Good morning.\n[01:05] Spreker B: Hoi.", txt)
    }

    @Test fun languageLookup() {
        assertEquals(TranslationLanguage.DE, TranslationLanguage.byCode("de"))
        assertEquals(null, TranslationLanguage.byCode("xx"))
    }
}
