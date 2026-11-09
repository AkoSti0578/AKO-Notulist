package nl.arnoldkolkman.akonotulist

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.audio.AudioFiles
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.core.Notifications
import nl.arnoldkolkman.akonotulist.core.SecretStore
import nl.arnoldkolkman.akonotulist.core.SettingsRepository
import nl.arnoldkolkman.akonotulist.data.AppDatabase
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import nl.arnoldkolkman.akonotulist.recording.AudioImporter
import nl.arnoldkolkman.akonotulist.recording.RecordingController
import nl.arnoldkolkman.akonotulist.transcribe.OpenAiClient
import java.io.File

class AkoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.recoverInterruptedRecordings()
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as AkoApp).container

fun CoroutineScope.launchIo(block: suspend CoroutineScope.() -> Unit): Job = launch(Dispatchers.IO, block = block)

/** Hand-rolled dependency container: small app, no DI framework needed. */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.create(app) }
    val settings = SettingsRepository(app)
    val secrets = SecretStore(app)
    val recorder = RecordingController(app)
    val importer = AudioImporter(app)
    val openAi = OpenAiClient(apiKey = { secrets.getApiKey() })

    suspend fun testApiKey(): String = openAi.testKey()

    /** Called by the recording service when a microphone recording stops. */
    suspend fun onRecordingStopped(id: Long, file: File?, elapsedMs: Long, recorderStoppedCleanly: Boolean) {
        val dao = db.recordings()
        val rec = dao.get(id) ?: return
        val measured = file?.let { AudioFiles.durationMs(it) } ?: 0
        if (!recorderStoppedCleanly || measured <= 0) {
            file?.delete()
            dao.upsert(
                rec.copy(
                    status = RecordingStatus.FAILED,
                    audioPath = null,
                    durationMs = elapsedMs,
                    errorMessage = "Opname mislukt of te kort; er is geen bruikbare audio opgeslagen.",
                )
            )
            return
        }
        dao.upsert(rec.copy(status = RecordingStatus.RECORDED, durationMs = measured))
        onRecordingReady(id)
    }

    /** A recording (microphone or import) has usable audio: update the estimate and maybe transcribe. */
    suspend fun onRecordingReady(id: Long) {
        val s = settings.current()
        val dao = db.recordings()
        val rec = dao.get(id) ?: return
        dao.upsert(
            rec.copy(
                transcriptionModel = s.transcriptionModel,
                estimatedCostUsd = Format.estimateCostUsd(rec.durationMs, s.rateFor(s.transcriptionModel)),
            )
        )
    }

    /**
     * After a process death during recording the row is stuck in RECORDING. MediaRecorder never
     * wrote the MP4 index then, so the file is usually unreadable; salvage it when possible.
     */
    fun recoverInterruptedRecordings() {
        appScope.launchIo {
            if (recorder.state.value.active) return@launchIo
            db.recordings().withStatus(RecordingStatus.RECORDING).forEach { rec ->
                val file = rec.audioPath?.let(::File)
                val d = file?.let { AudioFiles.durationMs(it) } ?: 0
                if (d > 0) {
                    db.recordings().upsert(rec.copy(status = RecordingStatus.RECORDED, durationMs = d))
                } else {
                    file?.delete()
                    db.recordings().upsert(
                        rec.copy(
                            status = RecordingStatus.FAILED,
                            audioPath = null,
                            errorMessage = "Opname onderbroken (app werd afgesloten); audio kon niet worden hersteld.",
                        )
                    )
                }
            }
        }
    }
}
