package nl.arnoldkolkman.akonotulist.car

import androidx.car.app.model.Action
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.RecordingSource
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import nl.arnoldkolkman.akonotulist.recording.RecorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Car App Library validates template constraints in build() and throws on violations
 * (too many actions, wrong header actions, too many rows …). These tests build every
 * template variant we use so violations fail here and never crash in the car.
 */
class CarTemplatesTest {
    private fun rec(id: Long, status: RecordingStatus) = RecordingEntity(
        id = id, title = "Overleg $id", createdAt = 1_762_696_800_000 + id, durationMs = 61_000,
        audioPath = "/x", source = RecordingSource.MICROPHONE, status = status, progress = 40,
    )

    private fun control(s: RecorderState, latest: RecordingEntity? = rec(1, RecordingStatus.TRANSCRIBING)) =
        CarTemplates.control(s, latest, icon = { null }, onStart = {}, onStop = {}, onPauseResume = {}, onRecent = {})

    @Test fun idleControlScreen() {
        val t = control(RecorderState())
        val actions = t.pane.actions
        assertEquals(2, actions.size)
        assertEquals("Start opname", actions[0].title.toString())
        assertTrue(actions[0].flags and Action.FLAG_PRIMARY != 0)
        assertEquals("Klaar om op te nemen", t.pane.rows[0].title.toString())
        assertEquals("Overleg 1", t.pane.rows[1].title.toString())
    }

    @Test fun recordingControlScreenShowsTimerAndStop() {
        val t = control(RecorderState(active = true, recordingId = 2, elapsedMs = 83_000))
        assertEquals("● Opname loopt · 1:23", t.pane.rows[0].title.toString())
        assertEquals(listOf("Stop", "Pauze"), t.pane.actions.map { it.title.toString() })
        assertEquals("★ Markeer", t.actionStrip!!.actions.single().title.toString())
    }

    @Test fun markerCountIsShownInStatus() {
        val t = control(RecorderState(active = true, recordingId = 2, elapsedMs = 83_000, markers = 3))
        assertEquals("● Opname loopt · 1:23 · ★3", t.pane.rows[0].title.toString())
    }

    @Test fun pausedControlScreenOffersResume() {
        val t = control(RecorderState(active = true, recordingId = 1, elapsedMs = 5_000, paused = true))
        assertEquals(listOf("Stop", "Hervatten"), t.pane.actions.map { it.title.toString() })
        // the row for the recording in progress itself is not duplicated
        assertEquals(1, t.pane.rows.size)
    }

    @Test fun recentListRespectsHostLimit() {
        val list = (1L..20L).map { rec(it, RecordingStatus.DONE) }
        val t = CarTemplates.recent(list, limit = 6) {}
        assertEquals(6, t.singleList!!.items.size)
    }

    @Test fun recentListEmptyAndLoading() {
        assertEquals(0, CarTemplates.recent(emptyList(), 6) {}.singleList!!.items.size)
        assertTrue(CarTemplates.recent(null, 6) {}.isLoading)
    }

    @Test fun longTextIsTruncatedAndHasToggle() {
        val body = "woord ".repeat(5_000)
        val t = CarTemplates.longText("Titel", body, "Transcript") {}
        assertTrue(t.message.toString().length < CarTemplates.MAX_LONG_MESSAGE_CHARS + 80)
        assertTrue(t.message.toString().endsWith("(Volledige tekst in de app op je telefoon.)"))
        assertEquals(1, t.actions.size)
    }

    @Test fun longTextWithoutToggle() {
        val t = CarTemplates.longText("Titel", "", null) {}
        assertEquals("Geen tekst beschikbaar.", t.message.toString())
        assertEquals(0, t.actions.size)
    }
}
