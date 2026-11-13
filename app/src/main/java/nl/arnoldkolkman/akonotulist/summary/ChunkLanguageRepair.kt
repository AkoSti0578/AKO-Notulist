package nl.arnoldkolkman.akonotulist.summary

import nl.arnoldkolkman.akonotulist.core.LanguageGuess
import nl.arnoldkolkman.akonotulist.transcribe.ChunkTranscript
import nl.arnoldkolkman.akonotulist.transcribe.RawSegment

/**
 * Makes sure one transcribed chunk is in the requested language.
 *
 * The gpt-4o models sometimes answer Dutch speech with an English translation, even with
 * language=nl — for a whole chunk or only part of it. Repair, in order of fidelity:
 *
 * 1. Listen again ([retranscribe]): a transcription of the same audio by a model whose language
 *    parameter forces the output language (Whisper). Only the English stretches are replaced by
 *    the literal Dutch sentences; speakers are carried over by time overlap.
 * 2. Whatever is still English is translated ([translate]), keeping speakers and times.
 *
 * Free of Android and network types so the whole decision path is unit tested.
 */
class ChunkLanguageRepair(
    private val retranscribe: suspend () -> ChunkTranscript,
    private val translate: suspend (List<String>) -> List<String>,
    private val log: (String) -> Unit,
) {
    suspend fun repair(transcript: ChunkTranscript, language: String): ChunkTranscript {
        val wrong = LanguageRepair.wrongRanges(transcript.segments.map { it.text }, language)
        if (wrong.isEmpty()) return transcript
        val count = wrong.sumOf { it.count() }
        log("$count van ${transcript.segments.size} zinnen kwamen in het Engels terug; audio opnieuw beluisteren met Whisper (Nederlands afgedwongen)…")

        val dutch = try {
            retranscribe()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("opnieuw beluisteren lukte niet (${e.message}).")
            null
        }

        // Text-only models have no sentence times to splice into: take the Dutch transcript as a whole.
        if (!transcript.hasTimestamps && dutch != null && dutch.text.isNotBlank() && !LanguageGuess.isWrongLanguage(dutch.text, language)) {
            log("vervangen door de letterlijke Nederlandse tekst.")
            return dutch
        }

        var segments = transcript.segments.map { TimedText(it.startMs ?: 0, it.endMs ?: it.startMs ?: 0, it.speaker, it.text) }
        var todo = wrong
        if (transcript.hasTimestamps && dutch != null && dutch.hasTimestamps) {
            val result = LanguageRepair.replace(
                segments, wrong, dutch.segments.map { TimedText(it.startMs ?: 0, it.endMs ?: it.startMs ?: 0, null, it.text.trim()) }, language,
            )
            segments = result.segments
            todo = result.unrepaired
            if (result.repaired > 0) log("${result.repaired} Engelse zinnen vervangen door wat er letterlijk in het Nederlands is gezegd; sprekers behouden.")
        }
        if (todo.isNotEmpty()) {
            val indices = todo.flatMap { it.toList() }
            try {
                val lines = translate(indices.map { segments[it].text })
                val list = segments.toMutableList()
                indices.forEachIndexed { k, i -> lines.getOrNull(k)?.takeIf { it.isNotBlank() }?.let { list[i] = list[i].copy(text = it) } }
                segments = list
                log("${indices.size} zinnen naar het Nederlands vertaald (noodoplossing; sprekers en tijden ongewijzigd).")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("vertalen mislukt (${e.message}); ${indices.size} zinnen blijven Engels. Gebruik later 'Omzetten naar NL'.")
            }
        }
        val keepTimes = transcript.hasTimestamps
        return transcript.copy(
            text = segments.joinToString(" ") { it.text },
            segments = segments.map { RawSegment(it.startMs.takeIf { keepTimes }, it.endMs.takeIf { keepTimes }, it.speaker, it.text) },
        )
    }
}
