package nl.arnoldkolkman.akonotulist.transcribe

import kotlin.math.abs
import kotlin.math.max

/** Result of transcribing one chunk, with the chunk's real position in the source audio. */
data class ChunkResult(
    val window: ChunkWindow,
    /** Real start of the uploaded audio in the source timeline (frame-aligned). */
    val actualStartMs: Long,
    val actualDurationMs: Long,
    val transcript: ChunkTranscript,
)

data class MergedSegment(val startMs: Long, val endMs: Long, val speaker: String?, val text: String)

data class MergeResult(
    val segments: List<MergedSegment>,
    /** True when at least one chunk had no timestamps and times were interpolated. */
    val timestampsEstimated: Boolean,
    val hasSpeakers: Boolean,
    /** Per chunk: the model's local speaker label -> global label ("A", "B", …). */
    val speakerMaps: List<Map<String, String>>,
)

/**
 * Merges per-chunk transcripts into one transcript with absolute timestamps.
 *
 * Pipeline:
 * 1. Every chunk is turned into a stream of words with absolute times. With timestamped
 *    responses a word's time is interpolated inside its segment; without timestamps it is
 *    interpolated over the whole chunk (by character offset) — marked as "estimated".
 * 2. Neighbouring chunks overlap by [ChunkPlanner.OVERLAP_MS]. We look for the longest run of
 *    identical words between the tail of the merged text and the head of the next chunk; when
 *    found, the text is stitched exactly there (no duplicated or lost words). Otherwise we cut
 *    at the middle of the overlap by time.
 * 3. Speaker labels are local to a request ("A" in chunk 2 is not necessarily "A" in chunk 1).
 *    Labels the API returns as known speakers ("spk_X", from reference clips) map straight to
 *    "X"; the rest are matched using the words both chunks transcribed in the overlap.
 */
object TranscriptMerger {
    const val KNOWN_PREFIX = "spk_"
    private const val STITCH_WINDOW = 80
    private const val MIN_RUN = 3
    internal const val MIN_VOTE = 1.0

    private data class Word(
        val text: String,
        val norm: String,
        val timeMs: Long,
        val speaker: String?, // local label while building, global after mapping
        val group: Long,
        val groupStart: Long,
        val groupEnd: Long,
        val groupFirst: Boolean,
        val groupLast: Boolean,
        val chunk: Int,
    )

    fun merge(chunks: List<ChunkResult>): MergeResult {
        val sorted = chunks.sortedBy { it.window.index }
        val estimated = sorted.any { !it.transcript.hasTimestamps }
        val hasSpeakers = sorted.any { it.transcript.hasSpeakers }
        val labeler = SpeakerLabeler()
        val maps = mutableListOf<Map<String, String>>()
        val out = mutableListOf<Word>()

        sorted.forEachIndexed { k, chunk ->
            val words = toWords(chunk, k)
            if (k == 0 || out.isEmpty()) {
                val map = labeler.map(localsInOrder(words), emptyMap())
                maps += map
                out += words.map { it.copy(speaker = it.speaker?.let(map::get)) }
                return@forEachIndexed
            }
            val prev = sorted[k - 1]
            val overlapStart = chunk.actualStartMs
            val overlapEnd = prev.actualStartMs + prev.actualDurationMs
            val cut = if (overlapEnd > overlapStart) (overlapStart + overlapEnd) / 2 else overlapStart

            val tailFrom = max(0, out.size - STITCH_WINDOW)
            val tail = out.subList(tailFrom, out.size).toList()
            val head = words.take(STITCH_WINDOW)
            val run = longestCommonRun(tail.map { it.norm }, head.map { it.norm })
                ?.takeIf { r -> r.length >= MIN_RUN && plausibleRun(r, tail, head, estimated) }

            // Votes "global label of previous text" x "local label of this chunk".
            val votes = HashMap<Pair<String, String>, Double>()
            if (run != null) {
                for (i in 0 until run.length) {
                    val g = tail[run.aStart + i].speaker ?: continue
                    val l = head[run.bStart + i].speaker ?: continue
                    votes.merge(g to l, 2.0, Double::plus)
                }
            }
            // Time-based votes from words transcribed twice in the overlap window.
            val prevInOverlap = out.filter { it.chunk == k - 1 && it.timeMs in overlapStart..overlapEnd && it.speaker != null }
            val curInOverlap = words.filter { it.timeMs in overlapStart..overlapEnd && it.speaker != null }
            for (pw in prevInOverlap) {
                val near = curInOverlap.minByOrNull { abs(it.timeMs - pw.timeMs) } ?: continue
                if (abs(near.timeMs - pw.timeMs) <= 700) votes.merge(pw.speaker!! to near.speaker!!, 1.0, Double::plus)
            }

            val map = labeler.map(localsInOrder(words), votes)
            maps += map

            val startIdx: Int
            if (run != null) {
                val keepUntil = tailFrom + run.aStart + run.length
                while (out.size > keepUntil) out.removeAt(out.size - 1)
                startIdx = run.bStart + run.length
            } else {
                out.removeAll { it.chunk == k - 1 && it.timeMs >= cut }
                startIdx = words.indexOfFirst { it.timeMs >= cut }.let { if (it < 0) words.size else it }
            }
            // Never go back in time: drop words that would land before what we already have.
            val lastTime = out.lastOrNull()?.timeMs ?: Long.MIN_VALUE
            out += words.drop(startIdx)
                .filter { it.timeMs >= lastTime - 1_000 }
                .map { it.copy(speaker = it.speaker?.let(map::get)) }
        }
        return MergeResult(buildSegments(out), estimated, hasSpeakers, maps)
    }

    /**
     * A matching run must sit where the overlap is: at the end of what we have and the start of
     * the new chunk. With real timestamps we check time; estimated times are too coarse for that,
     * so we check the position in the text instead.
     */
    private fun plausibleRun(r: Run, tail: List<Word>, head: List<Word>, estimated: Boolean): Boolean =
        if (estimated) {
            r.aStart + r.length >= tail.size - STITCH_WINDOW / 2 && r.bStart <= STITCH_WINDOW / 2
        } else {
            abs(tail[r.aStart].timeMs - head[r.bStart].timeMs) <= 10_000
        }

    private fun localsInOrder(words: List<Word>): List<String> = words.mapNotNull { it.speaker }.distinct()

    private fun toWords(chunk: ChunkResult, k: Int): List<Word> {
        val t = chunk.transcript
        val base = chunk.actualStartMs
        val result = mutableListOf<Word>()
        if (t.hasTimestamps) {
            t.segments.forEachIndexed { si, seg ->
                val start = base + (seg.startMs ?: 0)
                val end = max(start, base + (seg.endMs ?: seg.startMs ?: 0))
                addGroup(result, seg.text, start, end, seg.speaker, k.toLong() * 1_000_000 + si, k)
            }
        } else {
            val text = t.text.ifBlank { t.segments.joinToString(" ") { it.text } }.trim()
            if (text.isEmpty()) return result
            val total = text.length.toDouble()
            val end = base + chunk.actualDurationMs
            val sentences = splitSentences(text)
            var offset = 0
            sentences.forEachIndexed { si, s ->
                val idx = text.indexOf(s, offset).let { if (it < 0) offset else it }
                val sStart = base + ((end - base) * (idx / total)).toLong()
                offset = idx + s.length
                val sEnd = base + ((end - base) * (offset / total)).toLong()
                addGroup(result, s, sStart, sEnd, t.segments.firstOrNull()?.speaker, k.toLong() * 1_000_000 + si, k)
            }
        }
        return result
    }

    private fun addGroup(into: MutableList<Word>, text: String, start: Long, end: Long, speaker: String?, group: Long, chunk: Int) {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val total = tokens.sumOf { it.length + 1 }.toDouble()
        var acc = 0
        tokens.forEachIndexed { i, tok ->
            val time = start + ((end - start) * (acc / total)).toLong()
            acc += tok.length + 1
            into += Word(tok, normalize(tok), time, speaker, group, start, end, i == 0, i == tokens.lastIndex, chunk)
        }
    }

    private fun buildSegments(words: List<Word>): List<MergedSegment> {
        val segs = mutableListOf<MergedSegment>()
        var i = 0
        while (i < words.size) {
            var j = i
            while (j + 1 < words.size && words[j + 1].group == words[i].group) j++
            val first = words[i]
            val last = words[j]
            val start = if (first.groupFirst) first.groupStart else first.timeMs
            val nextTime = words.getOrNull(j + 1)?.let { if (it.groupFirst) it.groupStart else it.timeMs }
            val end = if (last.groupLast) last.groupEnd else (nextTime ?: last.timeMs)
            val text = words.subList(i, j + 1).joinToString(" ") { it.text }
            segs += MergedSegment(start, max(start, end), first.speaker, text)
            i = j + 1
        }
        // Monotonic, non-decreasing starts (estimated chunks can be slightly off).
        var lastStart = 0L
        return segs.map { s ->
            val st = max(s.startMs, lastStart)
            lastStart = st
            s.copy(startMs = st, endMs = max(st, s.endMs))
        }
    }

    internal fun normalize(token: String): String =
        token.lowercase().filter { it.isLetterOrDigit() }

    internal fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    internal data class Run(val aStart: Int, val bStart: Int, val length: Int)

    /** Longest common contiguous run of non-empty tokens (classic DP, inputs ≤ 80 tokens). */
    internal fun longestCommonRun(a: List<String>, b: List<String>): Run? {
        if (a.isEmpty() || b.isEmpty()) return null
        var best: Run? = null
        val prev = IntArray(b.size + 1)
        val cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                cur[j] = if (a[i - 1].isNotEmpty() && a[i - 1] == b[j - 1]) prev[j - 1] + 1 else 0
                if (cur[j] > (best?.length ?: 0)) best = Run(i - cur[j], j - cur[j], cur[j])
            }
            cur.copyInto(prev)
            cur.fill(0)
        }
        return best
    }
}

/** Hands out stable global speaker labels A, B, C, … and resolves per-chunk mappings. */
internal class SpeakerLabeler {
    private val used = LinkedHashSet<String>()

    private fun labelFor(i: Int) = if (i < 26) ('A' + i).toString() else "S${i + 1}"

    private fun next(): String {
        var i = 0
        while (labelFor(i) in used) i++
        return labelFor(i).also { used += it }
    }

    /**
     * @param localsInOrder local labels in order of first appearance.
     * @param votes evidence weight per (global, local) pair.
     */
    fun map(localsInOrder: List<String>, votes: Map<Pair<String, String>, Double>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val claimed = HashSet<String>()
        localsInOrder.filter { it.startsWith(TranscriptMerger.KNOWN_PREFIX) }.forEach { l ->
            val g = l.removePrefix(TranscriptMerger.KNOWN_PREFIX)
            result[l] = g
            claimed += g
            used += g
        }
        votes.entries
            .filter { it.value >= TranscriptMerger.MIN_VOTE }
            .sortedByDescending { it.value }
            .forEach { (pair, _) ->
                val (g, l) = pair
                if (l in result || g in claimed || l !in localsInOrder) return@forEach
                result[l] = g
                claimed += g
            }
        localsInOrder.filter { it !in result }.forEach { result[it] = next() }
        return result
    }
}
