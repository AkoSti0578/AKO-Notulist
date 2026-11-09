package nl.arnoldkolkman.akonotulist.transcribe

/**
 * Catalogue of OpenAI transcription models, verified against the official docs
 * (developers.openai.com, "File transcription" guide + API reference, November 2026):
 *
 * - `gpt-transcribe` is the recommended model. Response is `json` ({text, languages, usage});
 *   it accepts `languages[]` (instead of `language`), `keywords[]` and `prompt`. No timestamps,
 *   no speaker labels.
 * - `gpt-4o-transcribe-diarize` returns `diarized_json` with segments {id, speaker, start, end, text};
 *   needs `chunking_strategy=auto` for audio > 30 s, does NOT support `prompt`.
 *   Deprecated: shuts down on 2027-02-26.
 * - `gpt-4o-transcribe` / `gpt-4o-mini-transcribe`: `json` only. Deprecated (2027-02-26).
 * - `whisper-1`: `verbose_json` with segment timestamps. Deprecated (2027-02-26).
 * - All: files up to 25 MB.
 *
 * Prices (USD per audio minute) from the pricing page; they are only defaults — the user can
 * override the rate per model in settings.
 */
enum class ResponseKind { DIARIZED_JSON, VERBOSE_JSON, JSON }

data class TranscriptionModel(
    val id: String,
    val label: String,
    val description: String,
    val response: ResponseKind,
    val supportsPrompt: Boolean,
    /** gpt-transcribe uses `languages[]`; older models use the singular `language`. */
    val usesLanguagesArray: Boolean,
    val supportsKeywords: Boolean,
    val defaultRateUsdPerMinute: Double,
    /** ISO date after which OpenAI removes the model, or null. */
    val shutdownDate: String? = null,
) {
    val hasDiarization: Boolean get() = response == ResponseKind.DIARIZED_JSON
    val hasTimestamps: Boolean get() = response != ResponseKind.JSON
}

object TranscriptionModels {
    val DIARIZE = TranscriptionModel(
        id = "gpt-4o-transcribe-diarize",
        label = "GPT-4o Transcribe Diarize",
        description = "Herkent sprekers en geeft tijdstempels. Wordt op 26-02-2027 door OpenAI uitgefaseerd.",
        response = ResponseKind.DIARIZED_JSON,
        supportsPrompt = false,
        usesLanguagesArray = false,
        supportsKeywords = false,
        defaultRateUsdPerMinute = 0.006,
        shutdownDate = "2027-02-26",
    )
    val GPT_TRANSCRIBE = TranscriptionModel(
        id = "gpt-transcribe",
        label = "GPT Transcribe",
        description = "Nieuwste en goedkoopste model, hoge nauwkeurigheid. Geen sprekers of tijdstempels; die schat de app zelf.",
        response = ResponseKind.JSON,
        supportsPrompt = true,
        usesLanguagesArray = true,
        supportsKeywords = true,
        defaultRateUsdPerMinute = 0.0045,
    )
    val GPT4O = TranscriptionModel(
        id = "gpt-4o-transcribe",
        label = "GPT-4o Transcribe",
        description = "Alleen tekst. Wordt op 26-02-2027 uitgefaseerd.",
        response = ResponseKind.JSON,
        supportsPrompt = true,
        usesLanguagesArray = false,
        supportsKeywords = false,
        defaultRateUsdPerMinute = 0.006,
        shutdownDate = "2027-02-26",
    )
    val GPT4O_MINI = TranscriptionModel(
        id = "gpt-4o-mini-transcribe",
        label = "GPT-4o mini Transcribe",
        description = "Goedkoop, alleen tekst. Wordt op 26-02-2027 uitgefaseerd.",
        response = ResponseKind.JSON,
        supportsPrompt = true,
        usesLanguagesArray = false,
        supportsKeywords = false,
        defaultRateUsdPerMinute = 0.003,
        shutdownDate = "2027-02-26",
    )
    val WHISPER = TranscriptionModel(
        id = "whisper-1",
        label = "Whisper",
        description = "Echte tijdstempels per zin, geen sprekers. Wordt op 26-02-2027 uitgefaseerd.",
        response = ResponseKind.VERBOSE_JSON,
        supportsPrompt = true,
        usesLanguagesArray = false,
        supportsKeywords = false,
        defaultRateUsdPerMinute = 0.006,
        shutdownDate = "2027-02-26",
    )

    /** Default per the assignment: speaker diarization. Falls back to [FALLBACK] once retired. */
    val DEFAULT = DIARIZE
    val FALLBACK = GPT_TRANSCRIBE

    val all = listOf(DIARIZE, GPT_TRANSCRIBE, GPT4O, GPT4O_MINI, WHISPER)

    fun byId(id: String?): TranscriptionModel = all.firstOrNull { it.id == id } ?: DEFAULT
}

/** Chat models offered for the summary / speaker estimation. Free text is allowed too. */
object ChatModels {
    const val DEFAULT = "gpt-6-luna"
    val suggestions = listOf("gpt-6-luna", "gpt-6.1-terra", "gpt-5-mini")
}
