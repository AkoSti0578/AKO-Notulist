package nl.arnoldkolkman.akonotulist.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Low-level audio file helpers based on the platform MediaExtractor/MediaMuxer (no re-encoding). */
object AudioFiles {

    /** Duration in ms, or 0 when the file is unreadable (e.g. an MP4 without moov atom). */
    fun durationMs(file: File): Long {
        if (!file.exists() || file.length() == 0L) return 0
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * Copies the AAC samples in [startMs, endMs) of [src] into a new .m4a [dst] without
     * re-encoding. AAC frames are all sync frames, so cuts are exact to ~64 ms (one frame at 16 kHz).
     *
     * @return the real start time (ms) of the first copied sample in the source timeline, which
     *         is what the merger must use as offset for the timestamps returned by the API.
     */
    fun slice(src: File, dst: File, startMs: Long, endMs: Long): Long {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(src.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Geen audiospoor gevonden")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            dst.parentFile?.mkdirs()
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()

            val startUs = startMs * 1000
            val endUs = endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 64 * 1024
            val buffer = ByteBuffer.allocate(maxOf(maxSize, 64 * 1024))
            val info = MediaCodec.BufferInfo()
            var firstUs = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (t >= endUs) break
                if (firstUs < 0) firstUs = t
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, t - firstUs, flags)
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            if (firstUs < 0) error("Leeg audiofragment")
            return firstUs / 1000
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
