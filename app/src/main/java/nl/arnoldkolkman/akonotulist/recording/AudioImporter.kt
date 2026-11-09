package nl.arnoldkolkman.akonotulist.recording

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.launchIo
import nl.arnoldkolkman.akonotulist.audio.AudioFiles
import nl.arnoldkolkman.akonotulist.audio.AudioTranscoder
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.RecordingSource
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import java.io.File

/**
 * Imports audio shared to the app or picked with the file picker. The content is first copied
 * to private storage (URI grants from other apps can expire), then transcoded in a worker.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AudioImporter(private val context: Context) {

    suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val container = context.appContainer
        val now = System.currentTimeMillis()
        val name = displayName(uri) ?: "Geïmporteerde audio"
        val staging = File(context.cacheDir, "import/${now}_${(0..9999).random()}").apply { parentFile?.mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { input ->
            staging.outputStream().use { input.copyTo(it) }
        } ?: error("Kan bestand niet openen")

        val id = container.db.recordings().insert(
            RecordingEntity(
                title = name.substringBeforeLast('.'),
                createdAt = now,
                audioPath = null,
                source = RecordingSource.IMPORT,
                status = RecordingStatus.IMPORTING,
                statusDetail = "Audio omzetten…",
            )
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            "import_$id",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ImportWorker>()
                .setInputData(workDataOf(ImportWorker.KEY_ID to id, ImportWorker.KEY_STAGING to staging.absolutePath))
                .build(),
        )
        id
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

@UnstableApi
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val staging = File(inputData.getString(KEY_STAGING) ?: return Result.failure())
        val container = applicationContext.appContainer
        val dao = container.db.recordings()
        val rec = dao.get(id) ?: return Result.failure()
        val out = File(applicationContext.filesDir, "audio/imp_${rec.createdAt}.m4a")
        return try {
            AudioTranscoder(applicationContext).toSpeechM4a(Uri.fromFile(staging), out) { p ->
                container.appScope.launchIo { dao.setProgress(id, p, "Audio omzetten… $p%") }
            }
            val duration = AudioFiles.durationMs(out)
            if (duration <= 0) error("Leeg of onleesbaar audiobestand")
            staging.delete()
            dao.upsert(
                rec.copy(
                    audioPath = out.absolutePath,
                    durationMs = duration,
                    status = RecordingStatus.RECORDED,
                    progress = 0,
                    statusDetail = null,
                    errorMessage = null,
                )
            )
            container.onRecordingReady(id)
            Result.success()
        } catch (e: Exception) {
            out.delete()
            staging.delete()
            dao.setStatus(id, RecordingStatus.FAILED, error = "Importeren mislukt: ${e.message ?: e.javaClass.simpleName}")
            Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "recording_id"
        const val KEY_STAGING = "staging_path"
    }
}
