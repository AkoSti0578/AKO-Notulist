package nl.arnoldkolkman.akonotulist.summary

import nl.arnoldkolkman.akonotulist.core.LanguageGuess

/** A transcript line with its position in time. */
data class TimedText(val startMs: Long, val endMs: Long, val speaker: String?, val text: String)

/**
 * Pure logic for repairing transcript parts that came back in English although Dutch was spoken.
 *
 * Instead of translating the English back (an approximation), the affected audio is transcribed
 * again with a model that is forced to Dutch; [replace] then splices those literal Dutch sentences
 * into the transcript and carries the speaker labels over by time overlap.
 */
object LanguageRepair {
    /** Sentences are grouped into windows of about this many words before the language is judged. */
    const val WINDOW_WORDS = 50

    /** Longest stretch of audio sent in one repair request (keeps files far below 25 MB). */
    const val MAX_PIECE_MS = 10 * 60_000L

    /**
     * Index ranges of [texts] that are in English while [language] is Dutch. Judged per sentence
     * with its surrounding window as context, so a transcript that switches language halfway is
     * caught too and the borders are exact.
     */
    fun wrongRanges(texts: List<String>, language: String?): List<IntRange> {
        if (language?.lowercase()?.startsWith("nl") != true || texts.isEmpty()) return emptyList()
        // 1) windows of >= WINDOW_WORDS words (a short tail joins the previous window)
        val windows = mutableListOf<IntRange>()
        var start = 0
        var words = 0
        texts.forEachIndexed { i, t ->
            words += t.split(Regex("\\s+")).count { it.isNotBlank() }
            if (words >= WINDOW_WORDS) {
                windows += start..i
                start = i + 1
                words = 0
            }
        }
        if (start <= texts.lastIndex) {
            if (windows.isNotEmpty() && words < WINDOW_WORDS / 2) windows[windows.lastIndex] = windows.last().first..texts.lastIndex
            else windows += start..texts.lastIndex
        }
        // 2) label per sentence: its own words decide; a sentence without evidence ("Oké.") takes
        //    the verdict of its window, and failing that of its neighbours
        val labels = arrayOfNulls<Boolean>(texts.size) // true = English
        for (w in windows) {
            val verdict = when (LanguageGuess.guess(w.joinToString(" ") { texts[it] })) {
                LanguageGuess.Lang.ENGLISH -> true
                LanguageGuess.Lang.DUTCH -> false
                LanguageGuess.Lang.UNKNOWN -> null
            }
            for (i in w) {
                val (nl, en) = LanguageGuess.score(texts[i])
                labels[i] = if (en > nl) true else if (nl > en) false else verdict
            }
        }
        for (i in labels.indices) if (labels[i] == null && i > 0) labels[i] = labels[i - 1]
        for (i in labels.indices.reversed()) if (labels[i] == null && i < labels.lastIndex) labels[i] = labels[i + 1]
        // 3) runs of English sentences; a run only counts when it is clearly English as a whole
        //    (so one "Yes." or an English quote inside Dutch speech never triggers a repair)
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < labels.size) {
            if (labels[i] != true) { i++; continue }
            var j = i
            while (j + 1 < labels.size && labels[j + 1] == true) j++
            if (LanguageGuess.guess((i..j).joinToString(" ") { texts[it] }) == LanguageGuess.Lang.ENGLISH) out += i..j
            i = j + 1
        }
        return out
    }

    /** Splits [range] on sentence boundaries into pieces that each span at most [maxMs] of audio. */
    fun splitByDuration(segments: List<TimedText>, range: IntRange, maxMs: Long = MAX_PIECE_MS): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var first = range.first
        for (i in range) {
            if (i > first && segments[i].endMs - segments[first].startMs > maxMs) {
                out += first until i
                first = i
            }
        }
        out += first..range.last
        return out
    }

    /** Audio window [startMs, endMs) that belongs to [range]: up to the start of the next sentence. */
    fun timeWindow(segments: List<TimedText>, range: IntRange): Pair<Long, Long> {
        val start = if (range.first == 0) 0L else segments[range.first].startMs
        val end = segments.getOrNull(range.last + 1)?.startMs ?: Long.MAX_VALUE
        return start to maxOf(end, start)
    }

    data class Result(
        val segments: List<TimedText>,
        /** Number of original sentences that were replaced by re-transcribed ones. */
        val repaired: Int,
        /** Ranges (indices in [segments]) that are still in the wrong language. */
        val unrepaired: List<IntRange>,
    )

    /**
     * Replaces the [wrong] ranges of [original] by the sentences of [dutch] (a re-transcription of
     * the same audio, same timeline, no speakers) that fall into the same time window. A range is
     * left untouched when the re-transcription has nothing usable there or is English as well.
     */
    fun replace(original: List<TimedText>, wrong: List<IntRange>, dutch: List<TimedText>, language: String?): Result {
        val out = ArrayList<TimedText>(original.size)
        val unrepaired = mutableListOf<IntRange>()
        var repaired = 0
        var next = 0
        for (range in wrong.sortedBy { it.first }) {
            for (i in next until range.first) out += original[i]
            val (t0, t1) = timeWindow(original, range)
            val picks = dutch.filter { d -> (d.startMs + d.endMs) / 2 in t0 until t1 && d.text.isNotBlank() }
            val usable = picks.isNotEmpty() && !LanguageGuess.isWrongLanguage(picks.joinToString(" ") { it.text }, language)
            if (usable) {
                picks.forEach { d -> out += d.copy(speaker = speakerAt(original, d)) }
                repaired += range.count()
            } else {
                val from = out.size
                for (i in range) out += original[i]
                unrepaired += from until out.size
            }
            next = range.last + 1
        }
        for (i in next until original.size) out += original[i]
        return Result(out, repaired, unrepaired)
    }

    /** Speaker of the original sentence that overlaps [d] the longest (nearest one if none overlaps). */
    fun speakerAt(original: List<TimedText>, d: TimedText): String? {
        val labelled = original.filter { it.speaker != null }
        if (labelled.isEmpty()) return null
        val best = labelled.maxByOrNull { minOf(it.endMs, d.endMs) - maxOf(it.startMs, d.startMs) }!!
        if (minOf(best.endMs, d.endMs) - maxOf(best.startMs, d.startMs) > 0) return best.speaker
        val mid = (d.startMs + d.endMs) / 2
        return labelled.minByOrNull { kotlin.math.abs((it.startMs + it.endMs) / 2 - mid) }!!.speaker
    }
}
