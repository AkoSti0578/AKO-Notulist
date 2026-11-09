package nl.arnoldkolkman.akonotulist.ui

import nl.arnoldkolkman.akonotulist.data.MarkerEntity
import nl.arnoldkolkman.akonotulist.data.SegmentEntity
import nl.arnoldkolkman.akonotulist.ui.detail.markedSegments
import nl.arnoldkolkman.akonotulist.ui.detail.summaryAsText
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkedSegmentsTest {
    private fun seg(id: Long, start: Long) = SegmentEntity(id, 1, id.toInt(), start, start + 4_000, "A", "Zin $id")

    @Test fun markerStarsTheSentenceBeingSpoken() {
        val segments = listOf(seg(1, 0), seg(2, 5_000), seg(3, 10_000))
        val markers = listOf(MarkerEntity(1, 1, 6_500), MarkerEntity(2, 1, 12_000), MarkerEntity(3, 1, 12_500))
        assertEquals(setOf(2L, 3L), markedSegments(segments, markers))
    }

    @Test fun markerBeforeFirstSentenceStarsNothing() {
        assertEquals(emptySet<Long>(), markedSegments(listOf(seg(1, 5_000)), listOf(MarkerEntity(1, 1, 1_000))))
    }

    @Test fun summaryCopyText() {
        assertEquals(
            "Overleg\n\nSamenvatting\nKort.\n\nActiepunten\n- Jan: bellen\n\nBesluiten\n- Akkoord",
            summaryAsText("Overleg", " Kort. ", listOf("Jan: bellen"), listOf("Akkoord")),
        )
    }
}
