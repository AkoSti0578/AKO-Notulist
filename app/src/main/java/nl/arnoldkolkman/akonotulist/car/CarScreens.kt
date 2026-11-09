package nl.arnoldkolkman.akonotulist.car

import android.Manifest
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.R
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import nl.arnoldkolkman.akonotulist.recording.RecorderState
import nl.arnoldkolkman.akonotulist.recording.StartResult
import nl.arnoldkolkman.akonotulist.summary.decodeList
import nl.arnoldkolkman.akonotulist.ui.statusLabel

private fun CarContext.icon(res: Int) =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).setTint(CarColor.DEFAULT).build()

/**
 * Driving-safe main screen: two big buttons and a status. Uses PaneTemplate (max. 2 actions).
 * Refreshes at most once per second while recording; content-only refreshes of the same
 * template do not count against the host's template step limit.
 */
class ControlScreen(carContext: CarContext) : Screen(carContext) {
    private val c = carContext.appContainer
    private var recorder = RecorderState()
    private var latest: RecordingEntity? = null
    private var lastInvalidate = 0L

    init {
        lifecycleScope.launch {
            combine(c.recorder.state, c.db.recordings().observeAll()) { s, list -> s to list.firstOrNull() }
                .collect { (s, first) ->
                    val structural = s.active != recorder.active || s.paused != recorder.paused ||
                        first?.status != latest?.status || first?.progress != latest?.progress || first?.id != latest?.id
                    recorder = s
                    latest = first
                    val now = System.currentTimeMillis()
                    if (structural || now - lastInvalidate >= 1_000) {
                        lastInvalidate = now
                        invalidate()
                    }
                }
        }
    }

    override fun onGetTemplate(): Template = CarTemplates.control(
        s = recorder,
        latest = latest,
        icon = { carContext.icon(it) },
        onStart = ::startRecording,
        onStop = { c.recorder.stop(carContext) },
        onPauseResume = { if (recorder.paused) c.recorder.resume(carContext) else c.recorder.pause(carContext) },
        onRecent = { screenManager.push(RecentScreen(carContext)) },
    )

    private fun startRecording() {
        when (val r = c.recorder.start(carContext)) {
            StartResult.Started, StartResult.AlreadyRecording -> Unit
            StartResult.MissingPermission ->
                carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                    if (Manifest.permission.RECORD_AUDIO in granted) c.recorder.start(carContext)
                    else CarToast.makeText(carContext, "Geef microfoontoestemming op je telefoon", CarToast.LENGTH_LONG).show()
                }
            is StartResult.Failed ->
                CarToast.makeText(carContext, "Starten mislukt (${r.reason}). Open de app één keer op je telefoon.", CarToast.LENGTH_LONG).show()
        }
    }
}

/** Recent recordings with status (ListTemplate, respects the host's list limit). */
class RecentScreen(carContext: CarContext) : Screen(carContext) {
    private val c = carContext.appContainer
    private var items: List<RecordingEntity>? = null

    init {
        lifecycleScope.launch {
            c.db.recordings().observeAll().collect { list ->
                val changed = items?.map { it.id to it.status } != list.map { it.id to it.status }
                items = list
                if (changed) invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        return CarTemplates.recent(items, limit) { r ->
            if (r.status == RecordingStatus.DONE) screenManager.push(TranscriptScreen(carContext, r.id))
            else CarToast.makeText(carContext, statusLabel(r), CarToast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Summary / transcript text. LongMessageTemplate is a "parked only" template: the host
 * only shows its content while the car is parked and blocks it while driving.
 */
class TranscriptScreen(carContext: CarContext, private val recordingId: Long) : Screen(carContext) {
    private val c = carContext.appContainer
    private var title: String? = null
    private var summaryText: String? = null
    private var transcriptText: String? = null
    private var showTranscript = false

    init {
        lifecycleScope.launch {
            val rec = c.db.recordings().get(recordingId)
            title = rec?.title ?: "Opname"
            val names = c.db.speakers().forRecording(recordingId).associate { it.label to it.displayName }
            transcriptText = c.db.segments().forRecording(recordingId).joinToString("\n") { s ->
                (s.speaker?.let { (names[it] ?: "Spreker $it") + ": " } ?: "") + s.text
            }
            summaryText = c.db.summaries().get(recordingId)?.let { s ->
                buildString {
                    append(s.summary)
                    val actions = decodeList(s.actionItemsJson)
                    if (actions.isNotEmpty()) append("\n\nActiepunten:\n").append(actions.joinToString("\n") { "• $it" })
                    val decisions = decodeList(s.decisionsJson)
                    if (decisions.isNotEmpty()) append("\n\nBesluiten:\n").append(decisions.joinToString("\n") { "• $it" })
                }
            }
            showTranscript = summaryText == null
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val t = title ?: return MessageTemplate.Builder("Laden…").setLoading(true)
            .setHeader(Header.Builder().setStartHeaderAction(Action.BACK).build()).build()
        val body = (if (showTranscript) transcriptText else summaryText).orEmpty()
        val toggle = if (summaryText != null) (if (showTranscript) "Samenvatting" else "Transcript") else null
        return CarTemplates.longText(t, body, toggle) { showTranscript = !showTranscript; invalidate() }
    }

}
