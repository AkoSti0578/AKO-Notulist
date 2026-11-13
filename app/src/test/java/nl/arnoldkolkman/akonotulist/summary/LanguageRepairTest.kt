package nl.arnoldkolkman.akonotulist.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageRepairTest {
    private val nl = listOf(
        "Goedemorgen allemaal, fijn dat jullie er zijn vandaag.",
        "We gaan het hebben over de planning van het project.",
        "Ik wil ook even kijken naar de begroting, want die is nog niet rond.",
        "Jan, kun jij iets vertellen over de offerte die we hebben gestuurd?",
        "Ja, dat kan ik doen, maar ik heb nog geen antwoord van de klant.",
        "Dan wachten we dat even af en gaan we door met het volgende punt.",
    )
    private val en = listOf(
        "Good morning everyone, it is good that you are all here today.",
        "We are going to talk about the planning of the project.",
        "I also want to look at the budget, because it is not done yet.",
        "John, can you tell us something about the offer that we have sent?",
        "Yes, I can do that, but I do not have an answer from the client yet.",
        "Then we will wait for that and go on with the next item.",
    )

    private fun timed(texts: List<String>, startAt: Long = 0, speakers: List<String?> = listOf("A", "B")) =
        texts.mapIndexed { i, t -> TimedText(startAt + i * 10_000L, startAt + i * 10_000L + 9_000, speakers[i % speakers.size], t) }

    @Test fun dutchTranscriptHasNoWrongRanges() {
        assertEquals(emptyList<IntRange>(), LanguageRepair.wrongRanges(nl + nl + nl, "nl"))
    }

    @Test fun onlyAppliesWhenDutchIsRequested() {
        assertEquals(emptyList<IntRange>(), LanguageRepair.wrongRanges(en + en, "en"))
        assertEquals(listOf(0..11), LanguageRepair.wrongRanges(en + en, "nl"))
    }

    @Test fun findsEnglishStretchInTheMiddle() {
        // This is what a whole-chunk check missed: the model switches language halfway.
        val texts = nl + nl + en + en + nl + nl
        val ranges = LanguageRepair.wrongRanges(texts, "nl")
        assertEquals(listOf(12..23), ranges)
    }

    @Test fun englishQuoteOrSingleWordInDutchSpeechIsNotRepaired() {
        val texts = nl + listOf("Yes.", "Zoals ze zeggen: the best tool for the job.") + nl
        assertEquals(emptyList<IntRange>(), LanguageRepair.wrongRanges(texts, "nl"))
    }

    @Test fun shortInterjectionsFollowTheirNeighbours() {
        val texts = en + listOf("Yes.", "OK.") + en
        assertEquals(listOf(0..13), LanguageRepair.wrongRanges(texts, "nl"))
    }

    @Test fun replaceSplicesDutchSentencesAndKeepsSpeakers() {
        val original = timed(nl) + timed(en, startAt = 60_000) + timed(nl, startAt = 120_000)
        // Re-transcription of the same audio: other sentence boundaries, no speakers.
        val dutch = listOf(
            TimedText(1_000, 8_000, null, "Goedemorgen allemaal."),                       // outside the wrong range: ignored
            TimedText(60_500, 68_000, null, "Goedemorgen, fijn dat jullie er zijn."),      // overlaps speaker A
            TimedText(70_200, 88_500, null, "We gaan het hebben over de planning en ik wil naar de begroting kijken."), // B (longest overlap: 8.8 s of B vs 8.5 s of A)
            TimedText(90_000, 118_000, null, "Jan, kun jij iets vertellen over de offerte? Ja, maar ik heb nog geen antwoord van de klant."),
            TimedText(121_000, 125_000, null, "Dit hoort bij het stuk erna."),             // outside: ignored
        )
        val r = LanguageRepair.replace(original, listOf(6..11), dutch, "nl")
        assertEquals(6, r.repaired)
        assertEquals(emptyList<IntRange>(), r.unrepaired)
        assertEquals(6 + 3 + 6, r.segments.size)
        assertEquals(original.take(6), r.segments.take(6))
        assertEquals(original.takeLast(6), r.segments.takeLast(6))
        val spliced = r.segments.subList(6, 9)
        assertEquals(listOf("Goedemorgen, fijn dat jullie er zijn.", 60_500L, "A"), spliced[0].let { listOf(it.text, it.startMs, it.speaker) })
        assertEquals("B", spliced[1].speaker)
        assertEquals("B", spliced[2].speaker) // 90–118 s: B speaks 90–99 and 110–119, A only 100–109
        // Times stay strictly in order.
        assertEquals(r.segments.map { it.startMs }.sorted(), r.segments.map { it.startMs })
    }

    @Test fun rangeStaysWhenRetranscriptionIsMissingOrEnglishToo() {
        val original = timed(en) + timed(nl, startAt = 60_000)
        val nothing = LanguageRepair.replace(original, listOf(0..5), emptyList(), "nl")
        assertEquals(0, nothing.repaired)
        assertEquals(listOf(0..5), nothing.unrepaired)
        assertEquals(original, nothing.segments)

        val stillEnglish = LanguageRepair.replace(original, listOf(0..5), timed(en).map { it.copy(speaker = null) }, "nl")
        assertEquals(listOf(0..5), stillEnglish.unrepaired)
        assertEquals(original, stillEnglish.segments)
    }

    @Test fun unrepairedIndicesPointIntoTheNewList() {
        // First range repaired with fewer sentences, second range not repaired.
        val original = timed(en) + timed(nl, startAt = 60_000) + timed(en, startAt = 120_000)
        val dutch = listOf(TimedText(5_000, 50_000, null, nl.joinToString(" ")))
        val r = LanguageRepair.replace(original, listOf(0..5, 12..17), dutch, "nl")
        assertEquals(6, r.repaired)
        assertEquals(listOf(7..12), r.unrepaired)
        assertEquals(en, r.unrepaired.single().map { r.segments[it].text })
    }

    @Test fun firstAndLastRangeReachTheEdgesOfTheAudio() {
        val original = timed(en)
        // Speech before the first and after the last original sentence must not be lost.
        val dutch = listOf(TimedText(-0L, 400, null, nl[0]), TimedText(500, 55_000, null, nl.drop(1).joinToString(" ")), TimedText(70_000, 75_000, null, nl[5]))
        val r = LanguageRepair.replace(original, listOf(0..5), dutch, "nl")
        assertEquals(3, r.segments.size)
        assertEquals(LanguageRepair.timeWindow(original, 0..5), 0L to Long.MAX_VALUE)
    }

    @Test fun splitByDurationCutsOnSentenceBoundaries() {
        val segs = List(200) { i -> TimedText(i * 10_000L, i * 10_000L + 9_000, "A", "x") } // 33 min
        val pieces = LanguageRepair.splitByDuration(segs, 0..199)
        assertEquals(0, pieces.first().first)
        assertEquals(199, pieces.last().last)
        pieces.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
        assertTrue(pieces.all { segs[it.last].endMs - segs[it.first].startMs <= LanguageRepair.MAX_PIECE_MS })
        assertEquals(4, pieces.size)
        assertEquals(listOf(3..7), LanguageRepair.splitByDuration(segs, 3..7))
    }

    @Test fun speakerFallsBackToNearestWhenNothingOverlaps() {
        val original = listOf(TimedText(0, 1_000, "A", "a"), TimedText(50_000, 51_000, "B", "b"))
        assertEquals("B", LanguageRepair.speakerAt(original, TimedText(40_000, 41_000, null, "x")))
        assertEquals(null, LanguageRepair.speakerAt(original.map { it.copy(speaker = null) }, TimedText(0, 1, null, "x")))
    }

    @Test fun wrongCountSumsSentences() {
        assertEquals(12, LanguageFix.wrongCount(en + en, "nl"))
        assertEquals(0, LanguageFix.wrongCount(nl, "nl"))
    }
}
