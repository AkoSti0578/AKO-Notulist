package nl.arnoldkolkman.akonotulist.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptMergerTest {

    private fun seg(startS: Double, endS: Double, speaker: String?, text: String) =
        RawSegment((startS * 1000).toLong(), (endS * 1000).toLong(), speaker, text)

    private fun diarized(vararg segs: RawSegment) =
        ChunkTranscript(segs.joinToString(" ") { it.text }, segs.toList(), hasTimestamps = true, hasSpeakers = true)

    private fun textOnly(text: String) =
        ChunkTranscript(text, listOf(RawSegment(text = text)), hasTimestamps = false, hasSpeakers = false)

    private fun chunk(index: Int, startMs: Long, durMs: Long, t: ChunkTranscript) =
        ChunkResult(ChunkWindow(index, startMs, startMs + durMs), startMs, durMs, t)

    private fun allText(r: MergeResult) = r.segments.joinToString(" ") { it.text }

    @Test fun singleChunkKeepsTimestampsAndLabels() {
        val r = TranscriptMerger.merge(
            listOf(chunk(0, 0, 60_000, diarized(seg(0.0, 4.5, "A", "Goedemorgen allemaal."), seg(4.5, 9.0, "B", "Goedemorgen Jan."))))
        )
        assertEquals(2, r.segments.size)
        assertEquals(MergedSegment(0, 4_500, "A", "Goedemorgen allemaal."), r.segments[0])
        assertEquals(MergedSegment(4_500, 9_000, "B", "Goedemorgen Jan."), r.segments[1])
        assertFalse(r.timestampsEstimated)
        assertTrue(r.hasSpeakers)
    }

    @Test fun secondChunkTimestampsAreOffsetByRealChunkStart() {
        val r = TranscriptMerger.merge(
            listOf(
                chunk(0, 0, 600_000, diarized(seg(0.0, 3.0, "A", "Eerste deel van het gesprek."))),
                // real (frame aligned) start 589_984 ms, a little before the planned 590_000
                ChunkResult(
                    ChunkWindow(1, 590_000, 1_190_000), 589_984, 600_016,
                    diarized(seg(15.0, 18.0, "A", "Iets heel anders na de overlap.")),
                ),
            )
        )
        val last = r.segments.last()
        assertEquals(589_984 + 15_000L, last.startMs)
        assertEquals(589_984 + 18_000L, last.endMs)
    }

    @Test fun overlappingWordsAreStitchedWithoutDuplicates() {
        // Chunk 0 ends (600 s) in the middle of a sentence that chunk 1 (starts 590 s) repeats.
        val c0 = chunk(
            0, 0, 600_000,
            diarized(
                seg(570.0, 585.0, "A", "We moeten het budget voor volgend jaar vaststellen."),
                seg(586.0, 600.0, "B", "Ik stel voor dat we dat in de volgende vergadering"),
            ),
        )
        val c1 = chunk(
            1, 590_000, 600_000,
            diarized(
                // model labels restart at "A" in every request: here local A == global B
                seg(0.0, 14.0, "A", "dat we dat in de volgende vergadering definitief beslissen."),
                seg(14.0, 20.0, "B", "Akkoord, dan zetten we het op de agenda."),
            ),
        )
        val r = TranscriptMerger.merge(listOf(c0, c1))
        val text = allText(r)
        assertEquals(1, Regex("volgende vergadering").findAll(text).count())
        assertTrue(text.contains("vergadering definitief beslissen."))
        // Speaker continuity: the sentence continues with the same global speaker B, and the
        // local "B" (no evidence in the overlap) must NOT be confused with global B: it gets a
        // fresh label. (Reference clips keep labels stable in practice; see TranscriptionWorker.)
        val beslissen = r.segments.first { it.text.contains("beslissen") }
        assertEquals("B", beslissen.speaker)
        val akkoord = r.segments.first { it.text.startsWith("Akkoord") }
        assertEquals("C", akkoord.speaker)
        assertEquals(mapOf("A" to "B", "B" to "C"), r.speakerMaps[1])
    }

    @Test fun knownSpeakerLabelsMapDirectly() {
        val c0 = chunk(0, 0, 600_000, diarized(seg(1.0, 5.0, "A", "Hallo."), seg(6.0, 9.0, "B", "Hoi.")))
        val c1 = chunk(
            1, 590_000, 600_000,
            diarized(
                seg(20.0, 25.0, "spk_B", "Ik heb nog een vraag."),
                seg(26.0, 30.0, "A", "Ik ben nieuw in dit overleg."),
            ),
        )
        val r = TranscriptMerger.merge(listOf(c0, c1))
        assertEquals("B", r.segments.first { it.text.startsWith("Ik heb") }.speaker)
        // The unknown local "A" is a new person: gets the next free letter.
        assertEquals("C", r.segments.first { it.text.startsWith("Ik ben nieuw") }.speaker)
    }

    @Test fun withoutTextMatchWeCutAtTheMiddleOfTheOverlap() {
        val c0 = chunk(
            0, 0, 600_000,
            diarized(seg(580.0, 592.0, "A", "Alfa bravo charlie."), seg(596.0, 599.0, "A", "Delta echo.")),
        )
        val c1 = chunk(
            1, 590_000, 600_000,
            diarized(seg(1.0, 2.0, "A", "Ruis."), seg(6.0, 9.0, "A", "Foxtrot golf hotel.")),
        )
        val r = TranscriptMerger.merge(listOf(c0, c1))
        val text = allText(r)
        // cut = (590 + 600) / 2 = 595 s: "Delta echo." (596 s) from chunk 0 is dropped,
        // "Ruis." (591 s) from chunk 1 is dropped, the rest is kept.
        assertEquals("Alfa bravo charlie. Foxtrot golf hotel.", text)
    }

    @Test fun textOnlyChunksGetEstimatedTimesAndAreStitched() {
        val c0 = chunk(0, 0, 600_000, textOnly("Dit is de eerste zin. Hier komt de tweede zin die over de grens loopt"))
        val c1 = chunk(1, 590_000, 600_000, textOnly("de tweede zin die over de grens loopt en hier eindigt. Derde zin."))
        val r = TranscriptMerger.merge(listOf(c0, c1))
        assertTrue(r.timestampsEstimated)
        assertFalse(r.hasSpeakers)
        assertEquals(
            "Dit is de eerste zin. Hier komt de tweede zin die over de grens loopt en hier eindigt. Derde zin.",
            allText(r),
        )
        // Estimated times are monotonic and inside the recording.
        val starts = r.segments.map { it.startMs }
        assertEquals(starts.sorted(), starts)
        assertTrue(r.segments.all { it.startMs in 0..1_190_000 && it.endMs >= it.startMs })
        assertNull(r.segments.first().speaker)
    }

    @Test fun estimatedTimesAreProportionalToTextPosition() {
        val r = TranscriptMerger.merge(listOf(chunk(0, 0, 100_000, textOnly("Een. Twee. Drie. Vier."))))
        assertEquals(4, r.segments.size)
        assertEquals(0L, r.segments[0].startMs)
        // "Drie." starts at char 11 of 22 -> half of the duration
        assertEquals(50_000L, r.segments[2].startMs)
    }

    @Test fun threeChunksKeepGlobalLabelsConsistent() {
        val c0 = chunk(0, 0, 600_000, diarized(seg(10.0, 20.0, "A", "Anna praat."), seg(595.0, 600.0, "B", "Bert zegt iets belangrijks")))
        val c1 = chunk(1, 590_000, 600_000, diarized(seg(5.0, 10.0, "A", "Bert zegt iets belangrijks hier."), seg(300.0, 310.0, "B", "Anna antwoordt.")))
        val c2 = chunk(2, 1_180_000, 300_000, diarized(seg(50.0, 60.0, "spk_A", "Anna sluit af.")))
        val r = TranscriptMerger.merge(listOf(c0, c1, c2))
        assertEquals("B", r.segments.first { it.text.startsWith("Bert") }.speaker)
        assertEquals("A", r.segments.first { it.text.startsWith("Anna sluit") }.speaker)
        assertEquals(1, Regex("Bert zegt").findAll(allText(r)).count())
    }

    @Test fun emptyChunkIsHandled() {
        val r = TranscriptMerger.merge(
            listOf(
                chunk(0, 0, 600_000, textOnly("")),
                chunk(1, 590_000, 100_000, textOnly("Toch nog tekst.")),
            )
        )
        assertEquals("Toch nog tekst.", allText(r))
    }

    @Test fun longestCommonRunFindsLongestRun() {
        val run = TranscriptMerger.longestCommonRun(listOf("a", "b", "c", "d", "e"), listOf("x", "c", "d", "e", "y"))
        assertNotNull(run)
        assertEquals(TranscriptMerger.Run(2, 1, 3), run)
    }

    @Test fun longestCommonRunIgnoresEmptyTokens() {
        assertNull(TranscriptMerger.longestCommonRun(listOf("", ""), listOf("", "")))
    }

    @Test fun normalizeStripsPunctuationAndCase() {
        assertEquals("vergadering", TranscriptMerger.normalize("Vergadering,"))
        assertEquals("één", TranscriptMerger.normalize("Één!"))
    }

    @Test fun splitSentences() {
        assertEquals(listOf("Ja.", "Echt?", "Zeker!", "Oké"), TranscriptMerger.splitSentences("Ja. Echt? Zeker! Oké"))
    }
}
