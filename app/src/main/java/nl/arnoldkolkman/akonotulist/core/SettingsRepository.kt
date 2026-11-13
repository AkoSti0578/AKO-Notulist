package nl.arnoldkolkman.akonotulist.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nl.arnoldkolkman.akonotulist.transcribe.ChatModels
import nl.arnoldkolkman.akonotulist.transcribe.TranscriptionModels

data class AppSettings(
    val transcriptionModel: String = TranscriptionModels.DEFAULT.id,
    val language: String = "nl",
    val summaryEnabled: Boolean = true,
    val summaryModel: String = ChatModels.DEFAULT,
    val autoTranscribe: Boolean = true,
    /** Delete audio this many days after a finished transcript; 0 = keep forever. */
    val audioRetentionDays: Int = 0,
    /** For models without diarization: let the chat model estimate who speaks. */
    val estimateSpeakers: Boolean = true,
    /** Comma-separated names/terms that help recognition (keywords/prompt). */
    val vocabulary: String = "",
    val carTts: Boolean = true,
    /** Keep the display on while the app is visible (toggle on the main screen). */
    val keepScreenOn: Boolean = false,
    /** Let the chat model file new recordings under the user's categories. */
    val autoCategorize: Boolean = true,
    /** Which microphone to record with ("auto" = Bluetooth when connected, "phone"). */
    val micPreference: String = "auto",
    val rateOverrides: Map<String, Double> = emptyMap(),
) {
    fun rateFor(modelId: String): Double =
        rateOverrides[modelId] ?: TranscriptionModels.byId(modelId).defaultRateUsdPerMinute
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val model = stringPreferencesKey("transcription_model")
        val language = stringPreferencesKey("language")
        val summary = booleanPreferencesKey("summary_enabled")
        val summaryModel = stringPreferencesKey("summary_model")
        val auto = booleanPreferencesKey("auto_transcribe")
        val retention = intPreferencesKey("audio_retention_days")
        val estimateSpeakers = booleanPreferencesKey("estimate_speakers")
        val vocabulary = stringPreferencesKey("vocabulary")
        val carTts = booleanPreferencesKey("car_tts")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val autoCategorize = booleanPreferencesKey("auto_categorize")
        val micPreference = stringPreferencesKey("mic_preference")
        fun rate(modelId: String) = doublePreferencesKey("rate_$modelId")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            transcriptionModel = this[Keys.model] ?: d.transcriptionModel,
            language = this[Keys.language] ?: d.language,
            summaryEnabled = this[Keys.summary] ?: d.summaryEnabled,
            summaryModel = this[Keys.summaryModel] ?: d.summaryModel,
            autoTranscribe = this[Keys.auto] ?: d.autoTranscribe,
            audioRetentionDays = this[Keys.retention] ?: d.audioRetentionDays,
            estimateSpeakers = this[Keys.estimateSpeakers] ?: d.estimateSpeakers,
            vocabulary = this[Keys.vocabulary] ?: d.vocabulary,
            carTts = this[Keys.carTts] ?: d.carTts,
            keepScreenOn = this[Keys.keepScreenOn] ?: d.keepScreenOn,
            autoCategorize = this[Keys.autoCategorize] ?: d.autoCategorize,
            micPreference = this[Keys.micPreference] ?: d.micPreference,
            rateOverrides = TranscriptionModels.all.mapNotNull { m -> this[Keys.rate(m.id)]?.let { m.id to it } }.toMap(),
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[Keys.model] = s.transcriptionModel
            prefs[Keys.language] = s.language
            prefs[Keys.summary] = s.summaryEnabled
            prefs[Keys.summaryModel] = s.summaryModel
            prefs[Keys.auto] = s.autoTranscribe
            prefs[Keys.retention] = s.audioRetentionDays
            prefs[Keys.estimateSpeakers] = s.estimateSpeakers
            prefs[Keys.vocabulary] = s.vocabulary
            prefs[Keys.carTts] = s.carTts
            prefs[Keys.keepScreenOn] = s.keepScreenOn
            prefs[Keys.autoCategorize] = s.autoCategorize
            prefs[Keys.micPreference] = s.micPreference
            TranscriptionModels.all.forEach { m ->
                val r = s.rateOverrides[m.id]
                if (r == null) prefs.remove(Keys.rate(m.id)) else prefs[Keys.rate(m.id)] = r
            }
        }
    }
}
