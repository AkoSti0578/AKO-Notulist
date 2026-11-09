package nl.arnoldkolkman.akonotulist.transcribe

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class OpenAiClientTest {
    private val server = MockWebServer()
    private lateinit var client: OpenAiClient
    private lateinit var audio: File

    @Before fun setUp() {
        server.start()
        client = OpenAiClient(apiKey = { "test-key" }, baseUrl = server.url("/v1/").toString())
        audio = File.createTempFile("chunk", ".m4a").apply { writeBytes(ByteArray(64) { it.toByte() }) }
    }

    @After fun tearDown() {
        server.close()
        audio.delete()
    }

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test fun diarizeRequestUsesDiarizedJsonAndChunkingAndNoPrompt() = runBlocking {
        respond(
            200,
            """{"text":"Hoi. Hallo.","duration":3.2,"task":"transcribe","segments":[
               {"id":"seg_0","type":"transcript.text.segment","speaker":"A","start":0.0,"end":1.4,"text":" Hoi."},
               {"id":"seg_1","type":"transcript.text.segment","speaker":"B","start":1.5,"end":3.2,"text":"Hallo."}]}""",
        )
        val t = client.transcribe(
            audio, TranscriptionModels.DIARIZE, "nl", prompt = "context", keywords = listOf("AKO"),
            knownSpeakers = listOf(KnownSpeaker("spk_A", "data:audio/mp4;base64,AAAA")),
        )
        val req = server.takeRequest()
        val body = req.body!!.utf8()
        assertEquals("/v1/audio/transcriptions", req.url.encodedPath)
        assertEquals("Bearer test-key", req.headers["Authorization"])
        assertTrue(body.contains("name=\"model\"\r\n\r\ngpt-4o-transcribe-diarize"))
        assertTrue(body.contains("name=\"response_format\"\r\n\r\ndiarized_json"))
        assertTrue(body.contains("name=\"chunking_strategy\"\r\n\r\nauto"))
        assertTrue(body.contains("name=\"language\"\r\n\r\nnl"))
        assertTrue(body.contains("name=\"known_speaker_names[]\"\r\n\r\nspk_A"))
        assertTrue(body.contains("name=\"known_speaker_references[]\"\r\n\r\ndata:audio/mp4;base64,AAAA"))
        assertFalse("diarize model does not support prompt", body.contains("name=\"prompt\""))
        assertFalse(body.contains("keywords"))

        assertTrue(t.hasTimestamps)
        assertTrue(t.hasSpeakers)
        assertEquals(listOf(RawSegment(0, 1_400, "A", "Hoi."), RawSegment(1_500, 3_200, "B", "Hallo.")), t.segments)
    }

    @Test fun gptTranscribeUsesLanguagesArrayKeywordsAndPrompt() = runBlocking {
        respond(200, """{"text":"Goedemorgen.","languages":[{"code":"nl"}],"usage":{"type":"duration","seconds":3}}""")
        val t = client.transcribe(audio, TranscriptionModels.GPT_TRANSCRIBE, "nl", "Vorige zin.", listOf("Kolkman", "bad<word>\n"))
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("name=\"languages[]\"\r\n\r\nnl"))
        assertFalse(body.contains("name=\"language\""))
        assertTrue(body.contains("name=\"keywords[]\"\r\n\r\nKolkman"))
        assertTrue("keywords are sanitized", body.contains("name=\"keywords[]\"\r\n\r\nbad word"))
        assertTrue(body.contains("name=\"prompt\"\r\n\r\nVorige zin."))
        assertTrue(body.contains("name=\"response_format\"\r\n\r\njson"))
        assertFalse(body.contains("chunking_strategy"))
        assertEquals("Goedemorgen.", t.text)
        assertFalse(t.hasTimestamps)
    }

    @Test fun whisperVerboseJsonGivesTimestampsWithoutSpeakers() {
        val t = client.parseTranscription(
            """{"text":"a b","language":"dutch","duration":2,"segments":[{"id":0,"start":0.5,"end":1.25,"text":" a b "}]}""",
            ResponseKind.VERBOSE_JSON,
        )
        assertEquals(listOf(RawSegment(500, 1_250, null, "a b")), t.segments)
        assertTrue(t.hasTimestamps)
        assertFalse(t.hasSpeakers)
    }

    @Test fun errorMapping() {
        assertFalse(client.errorFor(401, """{"error":{"message":"Incorrect API key"}}""").retryable)
        assertTrue(client.errorFor(429, """{"error":{"message":"Rate limit","type":"requests"}}""").retryable)
        assertFalse(client.errorFor(429, """{"error":{"message":"quota","type":"insufficient_quota","code":"insufficient_quota"}}""").retryable)
        assertTrue(client.errorFor(503, "upstream").retryable)
        assertTrue(client.errorFor(404, """{"error":{"message":"The model `x` does not exist","code":"model_not_found"}}""").modelUnavailable)
        assertFalse(client.errorFor(400, """{"error":{"message":"bad file"}}""").retryable)
    }

    @Test fun serverErrorSurfacesAsRetryableException() = runBlocking {
        respond(500, """{"error":{"message":"boom"}}""")
        try {
            client.transcribe(audio, TranscriptionModels.GPT4O_MINI, "nl", null, emptyList())
            fail("expected exception")
        } catch (e: OpenAiException) {
            assertTrue(e.retryable)
            assertEquals(500, e.httpCode)
        }
    }

    @Test fun chatJsonSendsStrictSchemaAndParsesContent() = runBlocking {
        respond(200, """{"choices":[{"message":{"role":"assistant","content":"{\"summary\":\"Kort.\"}"}}]}""")
        val schema = buildJsonObject { put("type", "object") }
        val out = client.chatJson("gpt-6-luna", "sys", "user", "summary", schema)
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"json_schema\""))
        assertTrue(body.contains("\"strict\":true"))
        assertEquals("Kort.", out["summary"]!!.jsonPrimitive.content)
    }

    @Test fun missingKeyFailsWithoutNetwork() = runBlocking {
        val noKey = OpenAiClient(apiKey = { null }, baseUrl = server.url("/v1/").toString())
        try {
            noKey.transcribe(audio, TranscriptionModels.GPT_TRANSCRIBE, "nl", null, emptyList())
            fail("expected exception")
        } catch (e: OpenAiException) {
            assertFalse(e.retryable)
        }
        assertEquals(0, server.requestCount)
    }
}
