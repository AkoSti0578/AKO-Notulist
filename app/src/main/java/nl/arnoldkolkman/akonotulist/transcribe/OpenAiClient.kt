package nl.arnoldkolkman.akonotulist.transcribe

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A transcribed piece of text. Times are relative to the uploaded file, in ms (null = unknown). */
@Serializable
data class RawSegment(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val speaker: String? = null,
    val text: String,
)

/** Normalised result of one transcription request, independent of the model's response format. */
@Serializable
data class ChunkTranscript(
    val text: String,
    val segments: List<RawSegment>,
    val hasTimestamps: Boolean,
    val hasSpeakers: Boolean,
)

/** Reference clip (2–10 s) used to keep speaker labels consistent across chunks. */
data class KnownSpeaker(val name: String, val dataUrl: String)

/** Reports how many bytes of the audio have actually been written to the network. */
internal class CountingRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun isOneShot() = delegate.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                onProgress(sent, total)
            }
        }.buffer()
        delegate.writeTo(counting)
        counting.flush()
    }
}

class OpenAiException(
    val httpCode: Int,
    message: String,
    val retryable: Boolean,
    val modelUnavailable: Boolean = false,
) : IOException(message)

/**
 * Minimal OpenAI REST client (OkHttp + kotlinx.serialization). Only talks to api.openai.com.
 * The API key is read per request from [apiKey] and is never logged: there is deliberately
 * no HttpLoggingInterceptor.
 */
class OpenAiClient(
    private val apiKey: () -> String?,
    private val baseUrl: String = "https://api.openai.com/v1/",
    private val http: OkHttpClient = defaultHttpClient(),
) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun transcribe(
        file: File,
        model: TranscriptionModel,
        language: String?,
        prompt: String?,
        keywords: List<String>,
        knownSpeakers: List<KnownSpeaker> = emptyList(),
        onUploadProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): ChunkTranscript {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("file", file.name, CountingRequestBody(file.asRequestBody("audio/mp4".toMediaType()), onUploadProgress))
            addFormDataPart("model", model.id)
            addFormDataPart(
                "response_format",
                when (model.response) {
                    ResponseKind.DIARIZED_JSON -> "diarized_json"
                    ResponseKind.VERBOSE_JSON -> "verbose_json"
                    ResponseKind.JSON -> "json"
                },
            )
            if (model.response == ResponseKind.DIARIZED_JSON) {
                // Required for inputs longer than 30 s.
                addFormDataPart("chunking_strategy", "auto")
                knownSpeakers.take(4).forEach {
                    addFormDataPart("known_speaker_names[]", it.name)
                    addFormDataPart("known_speaker_references[]", it.dataUrl)
                }
            }
            if (!language.isNullOrBlank()) {
                if (model.usesLanguagesArray) addFormDataPart("languages[]", language) else addFormDataPart("language", language)
            }
            if (model.supportsPrompt && !prompt.isNullOrBlank()) addFormDataPart("prompt", prompt)
            if (model.supportsKeywords) {
                keywords.map(::sanitizeKeyword).filter { it.isNotEmpty() }.take(100).forEach { addFormDataPart("keywords[]", it) }
            }
        }.build()

        val request = Request.Builder().url(baseUrl + "audio/transcriptions").post(body).auth().build()
        val text = execute(request)
        return parseTranscription(text, model.response)
    }

    internal fun parseTranscription(body: String, kind: ResponseKind): ChunkTranscript {
        val root = json.parseToJsonElement(body).jsonObject
        val fullText = root["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (kind) {
            ResponseKind.DIARIZED_JSON, ResponseKind.VERBOSE_JSON -> {
                val segs = root["segments"]?.jsonArray.orEmpty().map { el ->
                    val o = el.jsonObject
                    RawSegment(
                        startMs = o["start"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
                        endMs = o["end"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
                        speaker = o["speaker"]?.jsonPrimitive?.contentOrNull,
                        text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                    )
                }.filter { it.text.isNotEmpty() }
                ChunkTranscript(
                    text = fullText,
                    segments = segs.ifEmpty { listOf(RawSegment(text = fullText.trim())) },
                    hasTimestamps = segs.isNotEmpty(),
                    hasSpeakers = kind == ResponseKind.DIARIZED_JSON && segs.any { it.speaker != null },
                )
            }
            ResponseKind.JSON -> ChunkTranscript(
                text = fullText,
                segments = if (fullText.isBlank()) emptyList() else listOf(RawSegment(text = fullText.trim())),
                hasTimestamps = false,
                hasSpeakers = false,
            )
        }
    }

    /**
     * Chat Completions call with Structured Outputs (strict JSON schema). Returns the parsed object.
     */
    suspend fun chatJson(model: String, system: String, user: String, schemaName: String, schema: JsonObject): JsonObject {
        val payload = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject {
                    put("name", schemaName)
                    put("strict", true)
                    put("schema", schema)
                })
            })
        }
        val request = Request.Builder()
            .url(baseUrl + "chat/completions")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .auth()
            .build()
        val root = json.parseToJsonElement(execute(request)).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw OpenAiException(200, "Leeg antwoord van het chatmodel", retryable = true)
        message["refusal"]?.jsonPrimitive?.contentOrNull?.let { throw OpenAiException(200, "Model weigerde: $it", retryable = false) }
        val content = message["content"]?.jsonPrimitive?.contentOrNull
            ?: throw OpenAiException(200, "Leeg antwoord van het chatmodel", retryable = true)
        return json.parseToJsonElement(content).jsonObject
    }

    /** Cheap authenticated call to validate the key. */
    suspend fun testKey(): String = try {
        execute(Request.Builder().url(baseUrl + "models").get().auth().build())
        "De sleutel werkt."
    } catch (e: OpenAiException) {
        e.message ?: "Onbekende fout"
    } catch (e: IOException) {
        "Geen verbinding met OpenAI: ${e.message}"
    }

    private fun Request.Builder.auth(): Request.Builder {
        val key = apiKey() ?: throw OpenAiException(401, "Geen OpenAI API-sleutel ingesteld", retryable = false)
        return header("Authorization", "Bearer $key")
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = runCatching { it.body.string() }.getOrElse { e -> cont.resumeWithException(e); return }
                    if (it.isSuccessful) cont.resume(text) else cont.resumeWithException(errorFor(it.code, text))
                }
            }
        })
    }

    internal fun errorFor(code: Int, body: String): OpenAiException {
        val err = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val apiMessage = err?.get("message")?.jsonPrimitive?.contentOrNull
        val apiCode = err?.get("code")?.jsonPrimitive?.contentOrNull
        val type = err?.get("type")?.jsonPrimitive?.contentOrNull
        val modelGone = apiCode == "model_not_found" || (code == 404 && apiMessage?.contains("model", ignoreCase = true) == true) ||
            (apiMessage?.contains("deprecated", ignoreCase = true) == true)
        return when {
            code == 401 -> OpenAiException(code, "Ongeldige OpenAI API-sleutel (401).", retryable = false)
            apiCode == "insufficient_quota" || type == "insufficient_quota" ->
                OpenAiException(code, "OpenAI-tegoed is op of het limiet is bereikt.", retryable = false)
            modelGone -> OpenAiException(code, "Model niet beschikbaar: ${apiMessage ?: code}", retryable = false, modelUnavailable = true)
            code == 408 || code == 409 || code == 429 || code >= 500 ->
                OpenAiException(code, "Tijdelijke fout bij OpenAI ($code)${apiMessage?.let { ": $it" } ?: ""}", retryable = true)
            else -> OpenAiException(code, "OpenAI-fout ($code)${apiMessage?.let { ": $it" } ?: ""}", retryable = false)
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(10, TimeUnit.MINUTES) // long chunks can take a while server-side
            .callTimeout(15, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()

        /** The API rejects keywords containing '<', '>', CR or LF. */
        fun sanitizeKeyword(k: String): String = k.replace(Regex("[<>\\r\\n]"), " ").trim()
    }
}
