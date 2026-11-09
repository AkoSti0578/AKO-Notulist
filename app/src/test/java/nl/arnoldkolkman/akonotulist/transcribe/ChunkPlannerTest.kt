package nl.arnoldkolkman.akonotulist.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {
    private val min = 60_000L

    @Test fun zeroDurationGivesNoChunks() = assertEquals(emptyList<ChunkWindow>(), ChunkPlanner.plan(0))

    @Test fun shortRecordingIsOneChunk() {
        assertEquals(listOf(ChunkWindow(0, 0, 5 * min)), ChunkPlanner.plan(5 * min))
    }

    @Test fun slightlyLongerThanChunkStaysOneChunk() {
        // 10.5 min: a 30 s tail chunk would be wasteful, so it's folded into the first chunk.
        assertEquals(listOf(ChunkWindow(0, 0, 630_000)), ChunkPlanner.plan(630_000))
    }

    @Test fun exactlyChunkPlusMinTailIsOneChunk() {
        assertEquals(1, ChunkPlanner.plan(ChunkPlanner.CHUNK_MS + ChunkPlanner.MIN_LAST_CHUNK_MS).size)
    }

    @Test fun twentyFiveMinutesGivesThreeOverlappingChunks() {
        val plan = ChunkPlanner.plan(25 * min)
        assertEquals(
            listOf(
                ChunkWindow(0, 0, 600_000),
                ChunkWindow(1, 590_000, 1_190_000),
                ChunkWindow(2, 1_180_000, 1_500_000),
            ),
            plan,
        )
    }

    @Test fun shortTailIsFoldedIntoPreviousChunk() {
        // 20.5 min: window 2 would only hold 40 s of new audio -> extend window 1 to the end.
        val plan = ChunkPlanner.plan(1_230_000)
        assertEquals(listOf(ChunkWindow(0, 0, 600_000), ChunkWindow(1, 590_000, 1_230_000)), plan)
    }

    @Test fun invariantsHoldForManyDurations() {
        for (d in listOf(1L, 59_999L, 600_000L, 661_000L, 3_600_000L, 7_777_777L, 4 * 3_600_000L)) {
            val plan = ChunkPlanner.plan(d)
            assertEquals("starts at 0 for $d", 0L, plan.first().startMs)
            assertEquals("ends at duration for $d", d, plan.last().endMs)
            plan.forEachIndexed { i, w ->
                assertEquals(i, w.index)
                assertTrue("chunk too long for $d: $w", w.durationMs <= ChunkPlanner.CHUNK_MS + ChunkPlanner.MIN_LAST_CHUNK_MS)
                if (i > 0) {
                    val prev = plan[i - 1]
                    assertEquals("overlap for $d at $i", ChunkPlanner.OVERLAP_MS, prev.endMs - w.startMs)
                }
            }
        }
    }

    @Test fun oneHourAtThirtyTwoKbpsStaysFarBelowApiLimit() {
        val plan = ChunkPlanner.plan(3_600_000)
        val maxBytes = plan.maxOf { it.durationMs } / 1000 * 32_000 / 8
        assertTrue(maxBytes < 25L * 1024 * 1024)
        // 0, 590, 1180, 1770, 2360, 2950 s; the 50 s tail is folded into the last chunk.
        assertEquals(6, plan.size)
    }

    @Test fun highBitrateShortensChunks() {
        assertEquals(ChunkPlanner.CHUNK_MS, ChunkPlanner.maxChunkMsForBitrate(32_000))
        // 512 kbps: 24 MiB lasts ~393 s
        assertTrue(ChunkPlanner.maxChunkMsForBitrate(512_000) < ChunkPlanner.CHUNK_MS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlapMustBeSmallerThanChunk() {
        ChunkPlanner.plan(10_000_000, chunkMs = 1_000, overlapMs = 1_000)
    }
}
