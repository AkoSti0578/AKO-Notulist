package nl.arnoldkolkman.akonotulist.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkProgressTest {
    @Test fun textShowsDoneUploadingAndWaitingWithTimer() {
        val now = 200_000L
        val t = ChunkProgress.text(
            done = 4, total = 14,
            active = listOf(ChunkPhase.Uploading(60), ChunkPhase.Waiting(now - 72_000), ChunkPhase.Waiting(now - 5_000)),
            nowMs = now,
        )
        assertEquals("4 van 14 delen klaar · uploaden 60% · OpenAI verwerkt 2 delen (1:12)", t)
    }

    @Test fun textSingleWaitingChunk() {
        assertEquals("0 van 14 delen klaar · OpenAI verwerkt (0:30)", ChunkProgress.text(0, 14, listOf(ChunkPhase.Waiting(0)), 30_000))
    }

    @Test fun textSingleChunkRecording() {
        assertEquals("1 deel · uploaden 10%", ChunkProgress.text(0, 1, listOf(ChunkPhase.Uploading(10)), 0))
    }

    @Test fun percentMovesWhileWaitingButNeverPast90() {
        val p0 = ChunkProgress.percent(0, 2, listOf(ChunkPhase.Waiting(0)), 0)
        val p1 = ChunkProgress.percent(0, 2, listOf(ChunkPhase.Waiting(0)), 60_000)
        val p2 = ChunkProgress.percent(0, 2, listOf(ChunkPhase.Waiting(0)), 10 * 60_000)
        assertTrue("waiting should move the bar: $p0 < $p1 < $p2", p0 < p1 && p1 < p2)
        assertTrue(p2 < 45) // one of two chunks never counts as more than half before it is done
        assertEquals(90, ChunkProgress.percent(14, 14, emptyList(), 0))
        assertEquals(0, ChunkProgress.percent(0, 0, emptyList(), 0))
    }
}
