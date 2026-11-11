package nl.arnoldkolkman.akonotulist.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-recording, human-readable processing log ("Verwerkingslog"), so the user can see what the
 * transcription is doing — and send it along when something is stuck. Never contains the API key.
 * Also mirrored to Logcat under tag "AKO" for adb debugging.
 */
class ProcessingLog(private val dir: File) {
    private val time = SimpleDateFormat("dd-MM HH:mm:ss", Locale.ROOT)

    private fun file(recordingId: Long) = File(dir, "rec_$recordingId.log")

    @Synchronized
    fun add(recordingId: Long, message: String) {
        Log.i("AKO", "[$recordingId] $message")
        runCatching {
            dir.mkdirs()
            val f = file(recordingId)
            // Keep the log small: drop the oldest half once it passes ~64 KB.
            if (f.length() > 64_000) f.writeText(f.readText().takeLast(32_000))
            f.appendText("${time.format(Date())}  $message\n")
        }
    }

    fun read(recordingId: Long): String = runCatching { file(recordingId).readText() }.getOrDefault("")

    fun clear(recordingId: Long) {
        file(recordingId).delete()
    }
}
