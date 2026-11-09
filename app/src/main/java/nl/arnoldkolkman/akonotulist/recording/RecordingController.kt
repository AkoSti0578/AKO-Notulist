package nl.arnoldkolkman.akonotulist.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live state of the (single) active recording. Shared by phone UI, notification and Android Auto. */
data class RecorderState(
    val active: Boolean = false,
    val recordingId: Long? = null,
    val elapsedMs: Long = 0,
    val paused: Boolean = false,
    /** Normalised input level 0..1 for the level meter. */
    val level: Float = 0f,
)

sealed interface StartResult {
    data object Started : StartResult
    data object AlreadyRecording : StartResult
    data object MissingPermission : StartResult
    data class Failed(val reason: String) : StartResult
}

/**
 * Single entry point to start/stop recording. Both the phone UI and the car screen call this,
 * so they always drive the same foreground service.
 */
class RecordingController(private val appContext: Context) {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    internal fun update(transform: (RecorderState) -> RecorderState) {
        _state.value = transform(_state.value)
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context = appContext): StartResult {
        if (_state.value.active) return StartResult.AlreadyRecording
        if (!hasMicPermission()) return StartResult.MissingPermission
        return try {
            ContextCompat.startForegroundService(context, intent(context, RecordingService.ACTION_START))
            // Optimistically mark active so double taps (phone + car) don't start twice.
            update { RecorderState(active = true) }
            StartResult.Started
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException & friends (e.g. started from background).
            StartResult.Failed(e.javaClass.simpleName)
        }
    }

    fun stop(context: Context = appContext) = send(context, RecordingService.ACTION_STOP)
    fun pause(context: Context = appContext) = send(context, RecordingService.ACTION_PAUSE)
    fun resume(context: Context = appContext) = send(context, RecordingService.ACTION_RESUME)

    private fun send(context: Context, action: String) {
        if (!_state.value.active) return
        runCatching { context.startService(intent(context, action)) }
    }

    private fun intent(context: Context, action: String) =
        Intent(context, RecordingService::class.java).setAction(action)
}
