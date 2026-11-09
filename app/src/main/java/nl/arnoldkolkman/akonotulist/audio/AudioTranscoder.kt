package nl.arnoldkolkman.akonotulist.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Converts any supported input (m4a, mp3, wav, ogg/opus, flac, …) to the app's canonical format:
 * AAC-LC in .m4a, mono, 16 kHz, 32 kbps. Uniform input keeps chunking simple and uploads small.
 */
@UnstableApi
class AudioTranscoder(private val context: Context) {

    suspend fun toSpeechM4a(input: Uri, output: File, onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.Main) {
        output.parentFile?.mkdirs()
        output.delete()

        val mixer = ChannelMixingAudioProcessor().apply {
            // Down-mix anything up to 8 channels to mono.
            for (inCh in 1..8) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(inCh, 1))
        }
        val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(16_000) }

        val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(mixer, resampler), emptyList()))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(32_000).build())
            .build()

        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        transformer.start(item, output.absolutePath)
        coroutineScope {
            val poller = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    delay(500)
                }
            }
            try {
                done.await()
            } finally {
                poller.cancel()
                if (!done.isCompleted) transformer.cancel()
            }
        }
    }
}
