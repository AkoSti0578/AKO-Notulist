package nl.arnoldkolkman.akonotulist.transcribe

/** A planned window of the source audio, [startMs, endMs). */
data class ChunkWindow(val index: Int, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Splits long recordings into overlapping windows that stay far below the 25 MB API limit.
 *
 * At 32 kbps a 10-minute chunk is ~2.4 MB, so size is never the constraint; 10 minutes keeps
 * each request fast and a failed request cheap to retry. The overlap gives the merger material
 * to stitch text and to match speakers between chunks.
 */
object ChunkPlanner {
    const val CHUNK_MS = 10 * 60_000L
    const val OVERLAP_MS = 10_000L
    /** A trailing chunk shorter than this is folded into the previous one. */
    const val MIN_LAST_CHUNK_MS = 60_000L

    /** Upper bound so that even a 128 kbps import stays under 25 MB per chunk. */
    const val MAX_BYTES_PER_CHUNK = 24L * 1024 * 1024

    fun plan(
        durationMs: Long,
        chunkMs: Long = CHUNK_MS,
        overlapMs: Long = OVERLAP_MS,
        minLastMs: Long = MIN_LAST_CHUNK_MS,
    ): List<ChunkWindow> {
        require(chunkMs > overlapMs) { "chunk must be longer than overlap" }
        if (durationMs <= 0) return emptyList()
        if (durationMs <= chunkMs + minLastMs) return listOf(ChunkWindow(0, 0, durationMs))

        val step = chunkMs - overlapMs
        val result = mutableListOf<ChunkWindow>()
        var index = 0
        while (true) {
            val start = index * step
            var end = start + chunkMs
            if (end >= durationMs || durationMs - end < minLastMs) {
                end = durationMs
                result += ChunkWindow(index, start, end)
                break
            }
            result += ChunkWindow(index, start, end)
            index++
        }
        return result
    }

    /** Chunk length that keeps a file of [bitrateBps] under [MAX_BYTES_PER_CHUNK]. */
    fun maxChunkMsForBitrate(bitrateBps: Int): Long =
        if (bitrateBps <= 0) CHUNK_MS else minOf(CHUNK_MS, MAX_BYTES_PER_CHUNK * 8 * 1000 / bitrateBps)
}
