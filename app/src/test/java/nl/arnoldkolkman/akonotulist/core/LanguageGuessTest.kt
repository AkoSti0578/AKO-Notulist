package nl.arnoldkolkman.akonotulist.core

import nl.arnoldkolkman.akonotulist.summary.LanguageFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageGuessTest {
    private val dutch = "Goedemorgen allemaal, fijn dat jullie er zijn. We gaan het vandaag hebben over de planning " +
        "van het project en ik wil ook even kijken naar de begroting, want die is nog niet helemaal rond. " +
        "Jan, kun jij iets vertellen over de offerte die we hebben gestuurd?"
    // The English that the model returned in the user's real transcript.
    private val english = "I get the version of the app installed. Only is it so that I am within Android Auto not see " +
        "as application. I have uh developers mode within Android Auto enabled and then I do not see it. " +
        "Also allow to copy the summary text, because that would be really useful for me and my team."

    @Test fun recognisesDutch() = assertEquals(LanguageGuess.Lang.DUTCH, LanguageGuess.guess(dutch))
    @Test fun recognisesEnglish() = assertEquals(LanguageGuess.Lang.ENGLISH, LanguageGuess.guess(english))
    @Test fun shortTextIsUnknown() = assertEquals(LanguageGuess.Lang.UNKNOWN, LanguageGuess.guess("Ja, prima. OK."))

    @Test fun wrongLanguageOnlyWhenDutchWasRequested() {
        assertTrue(LanguageGuess.isWrongLanguage(english, "nl"))
        assertFalse(LanguageGuess.isWrongLanguage(dutch, "nl"))
        assertFalse(LanguageGuess.isWrongLanguage(english, "en"))
        assertFalse(LanguageGuess.isWrongLanguage(english, null))
    }

    @Test fun dutchWithSomeEnglishTermsStaysDutch() {
        val mixed = dutch + " We gebruiken daarvoor de app en de API van OpenAI, the best tool for the job."
        assertFalse(LanguageGuess.isWrongLanguage(mixed, "nl"))
    }

    @Test fun wrongBlocksFindsOnlyTheEnglishPart() {
        val texts = List(LanguageFix.BLOCK) { dutch } + List(LanguageFix.BLOCK) { english } + List(10) { dutch }
        assertEquals(listOf(1), LanguageFix.wrongBlocks(texts, "nl"))
        assertEquals(emptyList<Int>(), LanguageFix.wrongBlocks(List(5) { dutch }, "nl"))
    }

    @Test fun languageNames() {
        assertEquals("Dutch", LanguageFix.languageName("nl"))
        assertEquals("German", LanguageFix.languageName("de"))
        assertEquals("Dutch", LanguageFix.languageName(null))
    }
}
