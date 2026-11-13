package nl.arnoldkolkman.akonotulist.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.arnoldkolkman.akonotulist.BuildConfig
import java.io.File

/**
 * Test hooks for the DEBUG build only (never read in release): an alternative API base URL
 * (e.g. a local fake OpenAI server), a shorter per-round time budget and start-up actions, read from
 * Android/data/<package>/files/debug.json.
 */
@Serializable
data class DebugConfig(
    val baseUrl: String? = null,
    val roundBudgetMs: Long? = null,
    /** Stored as API key at start (fake key for the fake server). */
    val apiKey: String? = null,
    /** Recording ids to (re)transcribe from scratch when the app process starts. */
    val transcribeOnStart: List<Long> = emptyList(),
    /** Recording ids whose transcript is language-checked/repaired when the app process starts. */
    val fixLanguageOnStart: List<Long> = emptyList(),
) {
    companion object {
        fun load(context: Context): DebugConfig? {
            if (!BuildConfig.DEBUG) return null
            val f = File(context.getExternalFilesDir(null) ?: return null, "debug.json")
            if (!f.exists()) return null
            return runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), f.readText()) }.getOrNull()
        }
    }
}
