package nl.arnoldkolkman.akonotulist.summary

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import nl.arnoldkolkman.akonotulist.transcribe.ChunkTranscript
import nl.arnoldkolkman.akonotulist.transcribe.OpenAiClient
import nl.arnoldkolkman.akonotulist.transcribe.RawSegment
import nl.arnoldkolkman.akonotulist.transcribe.TranscriptionModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class ChunkLanguageRepairTest {
    private val enLines = listOf(
        "Good morning everyone, it is good that you are all here today.",
        "We are going to talk about the planning of the project.",
        "I also want to look at the budget, because it is not done yet.",
        "John, can you tell us something about the offer that we have sent?",
    )
    private val nlLines = listOf(
        "Goedemorgen allemaal, fijn dat jullie er vandaag zijn.",
        "We gaan het hebben over de planning van het project.",
        "Ik wil ook naar de begroting kijken, want die is nog niet af.",
        "Jan, kun jij iets vertellen over de offerte die we hebben gestuurd?",
    )

    private fun diarized(lines: List<String>) = ChunkTranscript(
        lines.joinToString(" "),
        lines.mapIndexed { i, t -> RawSegment(i * 10_000L, i * 10_000L + 9_000, if (i % 2 == 0) "A" else "B", t) },
        hasTimestamps = true, hasSpeakers = true,
    )

    private fun whisper(lines: List<String>) = ChunkTranscript(
        lines.joinToString(" "),
        lines.mapIndexed { i, t -> RawSegment(i * 10_000L + 300, i * 10_000L + 8_700, null, t) },
        hasTimestamps = true, hasSpeakers = false,
    )

    private class Calls { var retranscribe = 0; var translate = 0; val log = mutableListOf<String>() }

    private fun repairer(calls: Calls, dutch: () -> ChunkTranscript, translate: (List<String>) -> List<String> = { it.map { l -> "NL:$l" } }) =
        ChunkLanguageRepair(
            retranscribe = { calls.retranscribe++; dutch() },
            translate = { calls.translate++; translate(it) },
            log = { calls.log += it },
        )

    @Test fun dutchChunkCostsNothingExtra() = runBlocking {
        val calls = Calls()
        val t = diarized(nlLines)
        assertEquals(t, repairer(calls, { error("must not be called") }).repair(t, "nl"))
        assertEquals(0, calls.retranscribe + calls.translate)
        assertTrue(calls.log.isEmpty())
    }

    @Test fun englishChunkGetsTheLiteralDutchWordsAndKeepsSpeakersAndTimes() = runBlocking {
        val calls = Calls()
        val out = repairer(calls, { whisper(nlLines) }).repair(diarized(enLines), "nl")
        assertEquals(nlLines, out.segments.map { it.text })
        assertEquals(listOf("A", "B", "A", "B"), out.segments.map { it.speaker })
        assertEquals(listOf(300L, 10_300L, 20_300L, 30_300L), out.segments.map { it.startMs })
        assertTrue(out.hasSpeakers && out.hasTimestamps)
        assertEquals(1, calls.retranscribe)
        assertEquals("no translation needed", 0, calls.translate)
    }

    @Test fun onlyTheEnglishHalfIsReplaced() = runBlocking {
        val calls = Calls()
        val mixed = diarized(nlLines + nlLines + enLines + enLines)
        val out = repairer(calls, { whisper(nlLines + nlLines + nlLines + nlLines) }).repair(mixed, "nl")
        // The Dutch half is untouched (original timestamps), the English half is re-transcribed.
        assertEquals(mixed.segments.take(8), out.segments.take(8))
        assertEquals(nlLines + nlLines, out.segments.drop(8).map { it.text })
        assertEquals(80_300L, out.segments[8].startMs)
        assertEquals(0, calls.translate)
    }

    @Test fun fallsBackToTranslationWhenListeningAgainFails() = runBlocking {
        val calls = Calls()
        val out = repairer(calls, { throw IOException("timeout") }).repair(diarized(enLines), "nl")
        assertEquals(enLines.map { "NL:$it" }, out.segments.map { it.text })
        assertEquals(listOf("A", "B", "A", "B"), out.segments.map { it.speaker })
        assertEquals(diarized(enLines).segments.map { it.startMs }, out.segments.map { it.startMs })
        assertEquals(1, calls.translate)
        assertTrue(calls.log.any { it.contains("opnieuw beluisteren lukte niet") })
    }

    @Test fun fallsBackToTranslationWhenSecondModelIsEnglishToo() = runBlocking {
        val calls = Calls()
        val out = repairer(calls, { whisper(enLines) }).repair(diarized(enLines), "nl")
        assertEquals(enLines.map { "NL:$it" }, out.segments.map { it.text })
        assertEquals(1, calls.translate)
    }

    @Test fun keepsEnglishAndSaysSoWhenEverythingFails() = runBlocking {
        val calls = Calls()
        val t = diarized(enLines)
        val out = repairer(calls, { throw IOException("x") }, { throw IOException("y") }).repair(t, "nl")
        assertEquals(t.segments, out.segments)
        assertTrue(calls.log.last().contains("blijven Engels"))
    }

    @Test fun textOnlyModelIsReplacedAsAWhole() = runBlocking {
        val calls = Calls()
        val textOnly = ChunkTranscript(enLines.joinToString(" "), listOf(RawSegment(text = enLines.joinToString(" "))), hasTimestamps = false, hasSpeakers = false)
        val out = repairer(calls, { whisper(nlLines) }).repair(textOnly, "nl")
        assertEquals(nlLines.joinToString(" "), out.text)
        assertTrue(out.hasTimestamps)
        assertEquals(0, calls.translate)
    }

    @Test fun otherTargetLanguagesAreLeftAlone() = runBlocking {
        val calls = Calls()
        val t = diarized(enLines)
        assertEquals(t, repairer(calls, { error("no") }).repair(t, "en"))
    }

    /** The real HTTP path: the second request goes to whisper-1 with language=nl and verbose_json. */
    @Test fun endToEndAgainstFakeOpenAi() = runBlocking {
        val server = MockWebServer()
        server.start()
        val audio = File.createTempFile("chunk", ".m4a").apply { writeBytes(ByteArray(128)) }
        try {
            val client = OpenAiClient(apiKey = { "test-key" }, baseUrl = server.url("/v1/").toString())
            val whisperJson = nlLines.mapIndexed { i, t -> """{"id":$i,"start":${i * 10 + 0.3},"end":${i * 10 + 8.7},"text":" $t"}""" }
                .joinToString(",", prefix = """{"text":"x","language":"dutch","duration":40,"segments":[""", postfix = "]}")
            server.enqueue(MockResponse.Builder().code(200).body(whisperJson).build())
            val log = mutableListOf<String>()
            val whisper = TranscriptionModels.WHISPER
            val out = ChunkLanguageRepair(
                retranscribe = { client.transcribe(audio, whisper, "nl", "Dit is een Nederlandstalig gesprek.", emptyList()) },
                translate = { error("translation must not be needed") },
                log = { log += it },
            ).repair(diarized(enLines), "nl")

            val body = server.takeRequest().body!!.utf8()
            assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-1"))
            assertTrue(body.contains("name=\"language\"\r\n\r\nnl"))
            assertTrue(body.contains("name=\"response_format\"\r\n\r\nverbose_json"))
            assertFalse(body.contains("chunking_strategy"))
            assertEquals(nlLines, out.segments.map { it.text })
            assertEquals(listOf("A", "B", "A", "B"), out.segments.map { it.speaker })
            assertTrue(log.any { it.contains("4 Engelse zinnen vervangen") })
        } finally {
            server.close()
            audio.delete()
        }
    }
}
