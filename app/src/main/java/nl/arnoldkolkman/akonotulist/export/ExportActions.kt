package nl.arnoldkolkman.akonotulist.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.arnoldkolkman.akonotulist.AppContainer
import nl.arnoldkolkman.akonotulist.summary.decodeList
import java.io.File

enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    TXT("txt", "text/plain", "Tekst (.txt)"),
    MARKDOWN("md", "text/markdown", "Markdown (.md)"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word (.docx)"),
}

class ExportActions(private val context: Context, private val c: AppContainer) {

    suspend fun load(recordingId: Long): ExportDoc? = withContext(Dispatchers.IO) {
        val rec = c.db.recordings().get(recordingId) ?: return@withContext null
        val names = c.db.speakers().forRecording(recordingId).associate { it.label to it.displayName }
        val summary = c.db.summaries().get(recordingId)
        val segments = c.db.segments().forRecording(recordingId)
        val markers = c.db.markers().forRecording(recordingId).map { m ->
            ExportMarker(m.timeMs, segments.lastOrNull { it.startMs <= m.timeMs }?.text)
        }
        ExportDoc(
            title = rec.title,
            createdAt = rec.createdAt,
            durationMs = rec.durationMs,
            markers = markers,
            lines = segments.map { s ->
                ExportLine(s.startMs, s.speaker?.let { names[it] ?: "Spreker $it" }, s.text)
            },
            summary = summary?.summary,
            actionItems = summary?.actionItemsJson?.let(::decodeList).orEmpty(),
            decisions = summary?.decisionsJson?.let(::decodeList).orEmpty(),
            timestampsEstimated = rec.timestampsEstimated,
        )
    }

    /** Writes the export to the app cache and opens the Android share sheet. */
    suspend fun share(recordingId: Long, format: ExportFormat) {
        val doc = load(recordingId) ?: return
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // keep the cache clean
            val f = File(dir, safeFileName(doc.title) + "." + format.extension)
            when (format) {
                ExportFormat.TXT -> f.writeText(TranscriptExporter.toText(doc))
                ExportFormat.MARKDOWN -> f.writeText(TranscriptExporter.toMarkdown(doc))
                ExportFormat.DOCX -> f.writeBytes(TranscriptExporter.toDocx(doc))
            }
            f
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, doc.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Transcript delen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun copyToClipboard(recordingId: Long): Boolean {
        val doc = load(recordingId) ?: return false
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(doc.title, TranscriptExporter.toText(doc)))
        return true
    }

    companion object {
        fun safeFileName(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifEmpty { "transcript" }
    }
}
