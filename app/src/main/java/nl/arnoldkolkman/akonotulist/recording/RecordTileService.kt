package nl.arnoldkolkman.akonotulist.recording

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.MainActivity
import nl.arnoldkolkman.akonotulist.appContainer

/**
 * Quick-settings tile "Opname": one tap to start or stop recording from the notification shade.
 * Starting goes through the activity, because Android 14+ only lets a visible app start a
 * microphone foreground service.
 */
class RecordTileService : TileService() {
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job = CoroutineScope(Dispatchers.Main).launch {
            appContainer.recorder.state.map { it.active to it.paused }.distinctUntilChanged().collect { (active, paused) ->
                val tile = qsTile ?: return@collect
                tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = if (active) "Stop opname" else "Opname"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = when {
                        active && paused -> "Gepauzeerd"
                        active -> "Loopt"
                        else -> "AKO Notulist"
                    }
                }
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val recorder = appContainer.recorder
        if (recorder.state.value.active) {
            recorder.stop(this)
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_QUICK_RECORD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
