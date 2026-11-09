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

    override fun onGetTemplate(): Template {
        val s = recorder
        val pane = Pane.Builder()
        val statusTitle = when {
            s.active && s.paused -> "Gepauzeerd · ${Format.duration(s.elapsedMs)}"
            s.active -> "● Opname loopt · ${Format.duration(s.elapsedMs)}"
            else -> "Klaar om op te nemen"
        }
        pane.addRow(Row.Builder().setTitle(statusTitle).addText(if (s.active) "Opname loopt ook met je telefoon vergrendeld" else "Tik op Start opname").build())

        latest?.takeIf { !s.active || it.id != s.recordingId }?.let { r ->
            pane.addRow(
                Row.Builder()
                    .setTitle(r.title)
                    .addText(statusLabel(r) + if (r.durationMs > 0) " · ${Format.duration(r.durationMs)}" else "")
                    .build()
            )
        }

        if (s.active) {
            pane.addAction(
                Action.Builder()
                    .setTitle("Stop")
                    .setIcon(carContext.icon(R.drawable.ic_stop))
                    .setBackgroundColor(CarColor.RED)
                    .setFlags(Action.FLAG_PRIMARY)
                    .setOnClickListener { c.recorder.stop(carContext) }
                    .build()
            )
            pane.addAction(
                Action.Builder()
                    .setTitle(if (s.paused) "Hervatten" else "Pauze")
                    .setIcon(carContext.icon(if (s.paused) R.drawable.ic_mic else R.drawable.ic_pause))
                    .setOnClickListener { if (s.paused) c.recorder.resume(carContext) else c.recorder.pause(carContext) }
                    .build()
            )
        } else {
            pane.addAction(
                Action.Builder()
                    .setTitle("Start opname")
                    .setIcon(carContext.icon(R.drawable.ic_mic))
                    .setBackgroundColor(CarColor.RED)
                    .setFlags(Action.FLAG_PRIMARY)
                    .setOnClickListener(::startRecording)
                    .build()
            )
            pane.addAction(
                Action.Builder()
                    .setTitle("Recente opnames")
                    .setIcon(carContext.icon(R.drawable.ic_list))
                    .setOnClickListener { screenManager.push(RecentScreen(carContext)) }
                    .build()
            )
        }

        return PaneTemplate.Builder(pane.build())
            .setHeader(Header.Builder().setTitle("AKO Notulist").setStartHeaderAction(Action.APP_ICON).build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("Recent").setOnClickListener { screenManager.push(RecentScreen(carContext)) }.build())
                    .build()
            )
            .build()
    }

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
        val list = items
        val builder = ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Recente opnames").setStartHeaderAction(Action.BACK).build())
        if (list == null) return builder.setLoading(true).build()

        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)
        val itemList = ItemList.Builder().setNoItemsMessage("Nog geen opnames")
        list.take(limit).forEach { r ->
            itemList.addItem(
                Row.Builder()
                    .setTitle(r.title)
                    .addText(listOfNotNull(Format.dateTime(r.createdAt), Format.duration(r.durationMs).takeIf { r.durationMs > 0 }).joinToString(" · "))
                    .addText(statusLabel(r))
                    .setOnClickListener {
                        if (r.status == RecordingStatus.DONE) screenManager.push(TranscriptScreen(carContext, r.id))
                        else CarToast.makeText(carContext, statusLabel(r), CarToast.LENGTH_SHORT).show()
                    }
                    .build()
            )
        }
        return builder.setSingleList(itemList.build()).build()
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
        val body = (if (showTranscript) transcriptText else summaryText).orEmpty().ifBlank { "Geen tekst beschikbaar." }
        val builder = LongMessageTemplate.Builder(truncate(body))
            .setTitle(t)
            .setHeaderAction(Action.BACK)
        if (summaryText != null) {
            builder.addAction(
                Action.Builder()
                    .setTitle(if (showTranscript) "Samenvatting" else "Transcript")
                    .setOnClickListener { showTranscript = !showTranscript; invalidate() }
                    .build()
            )
        }
        return builder.build()
    }

    private fun truncate(s: String): String =
        if (s.length <= MAX_CHARS) s else s.take(MAX_CHARS).substringBeforeLast(' ') + "…\n\n(Volledige tekst in de app op je telefoon.)"

    private companion object {
        const val MAX_CHARS = 6_000
    }
}
