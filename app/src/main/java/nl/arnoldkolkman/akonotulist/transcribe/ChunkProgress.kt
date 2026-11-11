package nl.arnoldkolkman.akonotulist.transcribe

import nl.arnoldkolkman.akonotulist.core.Format

/** Live state of one chunk that is being processed. */
sealed interface ChunkPhase {
    /** Bytes are being sent; [percent] 0..100. */
    data class Uploading(val percent: Int) : ChunkPhase

    /** Upload finished at [sinceMs] (elapsedRealtime); OpenAI is transcribing. */
    data class Waiting(val sinceMs: Long) : ChunkPhase
}

/** Pure formatting of the transcription progress shown in the app and the notification. */
object ChunkProgress {
    /** Typical server time for a 10-minute chunk; only used to make the bar move smoothly. */
    private const val EXPECTED_SERVER_MS = 120_000.0

    /** 0..90 (the last 10 % are merging and post-processing). */
    fun percent(done: Int, total: Int, active: Collection<ChunkPhase>, nowMs: Long): Int {
        if (total <= 0) return 0
        val partial = active.sumOf { p ->
            when (p) {
                is ChunkPhase.Uploading -> 0.2 * p.percent / 100.0
                is ChunkPhase.Waiting -> 0.2 + 0.75 * minOf((nowMs - p.sinceMs) / EXPECTED_SERVER_MS, 1.0)
            }
        }
        return ((done + partial) / total * 90).toInt().coerceIn(0, 90)
    }

    /** e.g. "4 van 14 delen klaar · uploaden 63% · OpenAI verwerkt 2 delen (1:12)". */
    fun text(done: Int, total: Int, active: Collection<ChunkPhase>, nowMs: Long): String {
        val parts = mutableListOf<String>()
        parts += if (total == 1) (if (done == 1) "Klaar" else "1 deel") else "$done van $total delen klaar"
        val uploading = active.filterIsInstance<ChunkPhase.Uploading>()
        if (uploading.isNotEmpty()) parts += "uploaden ${uploading.map { it.percent }.average().toInt()}%"
        val waiting = active.filterIsInstance<ChunkPhase.Waiting>()
        if (waiting.isNotEmpty()) {
            val longest = waiting.maxOf { nowMs - it.sinceMs }.coerceAtLeast(0)
            val what = if (waiting.size == 1) "OpenAI verwerkt" else "OpenAI verwerkt ${waiting.size} delen"
            parts += "$what (${Format.duration(longest)})"
        }
        return parts.joinToString(" · ")
    }
}
