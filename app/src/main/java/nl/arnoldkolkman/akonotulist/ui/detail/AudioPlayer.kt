package nl.arnoldkolkman.akonotulist.ui.detail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.io.File

/** Small Compose-friendly wrapper around ExoPlayer with a polled position. */
@Stable
class AudioPlayerState(context: Context, path: String) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(path))))
        prepare()
    }
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var speed by mutableStateOf(1f)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0)
                if (state == Player.STATE_ENDED) { player.pause(); player.seekTo(0) }
            }
        })
    }

    fun toggle() = if (player.isPlaying) player.pause() else player.play()

    fun seekTo(ms: Long, play: Boolean = true) {
        player.seekTo(ms.coerceAtLeast(0))
        positionMs = ms
        if (play) player.play()
    }

    fun skip(deltaMs: Long) = seekTo((player.currentPosition + deltaMs).coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE), play = player.isPlaying)

    fun cycleSpeed() {
        speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player.setPlaybackSpeed(speed)
    }

    fun release() = player.release()
}

@Composable
fun rememberAudioPlayer(path: String?): AudioPlayerState? {
    val ctx = LocalContext.current
    if (path == null || !File(path).exists()) return null
    val state = remember(path) { AudioPlayerState(ctx, path) }
    DisposableEffect(state) { onDispose { state.release() } }
    LaunchedEffect(state) {
        while (true) {
            state.positionMs = state.player.currentPosition
            delay(200)
        }
    }
    return state
}
