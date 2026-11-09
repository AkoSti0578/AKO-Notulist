package nl.arnoldkolkman.akonotulist.car

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import nl.arnoldkolkman.akonotulist.R
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.recording.RecorderState
import nl.arnoldkolkman.akonotulist.ui.statusLabel

/**
 * Template factories, separated from the Screens so unit tests can verify that every template
 * we build passes the Car App Library's own constraint checks (which throw at build()).
 */
object CarTemplates {
    const val MAX_LONG_MESSAGE_CHARS = 6_000

    fun control(
        s: RecorderState,
        latest: RecordingEntity?,
        icon: (Int) -> CarIcon?,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onPauseResume: () -> Unit,
        onRecent: () -> Unit,
    ): PaneTemplate {
        val pane = Pane.Builder()
        val statusTitle = when {
            s.active && s.paused -> "Gepauzeerd · ${Format.duration(s.elapsedMs)}"
            s.active -> "● Opname loopt · ${Format.duration(s.elapsedMs)}"
            else -> "Klaar om op te nemen"
        }
        pane.addRow(
            Row.Builder()
                .setTitle(statusTitle)
                .addText(if (s.active) "Opname loopt ook met je telefoon vergrendeld" else "Tik op Start opname")
                .build()
        )
        latest?.takeIf { !s.active || it.id != s.recordingId }?.let { r ->
            pane.addRow(
                Row.Builder()
                    .setTitle(r.title)
                    .addText(statusLabel(r) + if (r.durationMs > 0) " · ${Format.duration(r.durationMs)}" else "")
                    .build()
            )
        }

        fun action(title: String, iconRes: Int, primary: Boolean, onClick: () -> Unit) = Action.Builder()
            .setTitle(title)
            .apply { icon(iconRes)?.let(::setIcon) }
            .apply { if (primary) setBackgroundColor(CarColor.RED).setFlags(Action.FLAG_PRIMARY) }
            .setOnClickListener(onClick)
            .build()

        if (s.active) {
            pane.addAction(action("Stop", R.drawable.ic_stop, primary = true, onClick = onStop))
            pane.addAction(
                action(if (s.paused) "Hervatten" else "Pauze", if (s.paused) R.drawable.ic_mic else R.drawable.ic_pause, false, onPauseResume)
            )
        } else {
            pane.addAction(action("Start opname", R.drawable.ic_mic, primary = true, onClick = onStart))
            pane.addAction(action("Recente opnames", R.drawable.ic_list, primary = false, onClick = onRecent))
        }

        return PaneTemplate.Builder(pane.build())
            .setHeader(Header.Builder().setTitle("AKO Notulist").setStartHeaderAction(Action.APP_ICON).build())
            .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle("Recent").setOnClickListener(onRecent).build()).build())
            .build()
    }

    fun recent(list: List<RecordingEntity>?, limit: Int, onOpen: (RecordingEntity) -> Unit): ListTemplate {
        val builder = ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Recente opnames").setStartHeaderAction(Action.BACK).build())
        if (list == null) return builder.setLoading(true).build()
        val items = ItemList.Builder().setNoItemsMessage("Nog geen opnames")
        list.take(limit.coerceAtLeast(1)).forEach { r ->
            items.addItem(
                Row.Builder()
                    .setTitle(r.title)
                    .addText(listOfNotNull(Format.dateTime(r.createdAt), Format.duration(r.durationMs).takeIf { r.durationMs > 0 }).joinToString(" · "))
                    .addText(statusLabel(r))
                    .setOnClickListener { onOpen(r) }
                    .build()
            )
        }
        return builder.setSingleList(items.build()).build()
    }

    fun longText(title: String, body: String, toggleLabel: String?, onToggle: () -> Unit): LongMessageTemplate {
        val builder = LongMessageTemplate.Builder(truncate(body.ifBlank { "Geen tekst beschikbaar." }))
            .setTitle(title)
            .setHeaderAction(Action.BACK)
        if (toggleLabel != null) {
            // Actions in a parked-only template must themselves be parked-only.
            builder.addAction(
                Action.Builder().setTitle(toggleLabel).setOnClickListener(ParkedOnlyOnClickListener.create(onToggle)).build()
            )
        }
        return builder.build()
    }

    fun truncate(s: String): String =
        if (s.length <= MAX_LONG_MESSAGE_CHARS) s
        else s.take(MAX_LONG_MESSAGE_CHARS).substringBeforeLast(' ') + "…\n\n(Volledige tekst in de app op je telefoon.)"
}
