package nl.arnoldkolkman.akonotulist

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.audio.AudioFiles
import nl.arnoldkolkman.akonotulist.category.CategoryRepository
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.core.DebugConfig
import nl.arnoldkolkman.akonotulist.core.Notifications
import nl.arnoldkolkman.akonotulist.core.ProcessingLog
import nl.arnoldkolkman.akonotulist.core.SecretStore
import nl.arnoldkolkman.akonotulist.core.SettingsRepository
import nl.arnoldkolkman.akonotulist.data.AppDatabase
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import nl.arnoldkolkman.akonotulist.recording.AudioImporter
import nl.arnoldkolkman.akonotulist.recording.RecordingController
import nl.arnoldkolkman.akonotulist.transcribe.AppEvent
import nl.arnoldkolkman.akonotulist.transcribe.OpenAiClient
import nl.arnoldkolkman.akonotulist.transcribe.TranscriptionScheduler
import nl.arnoldkolkman.akonotulist.transcribe.TranscriptionModels
import nl.arnoldkolkman.akonotulist.summary.PostProcessWorker
import kotlinx.coroutines.flow.MutableSharedFlow
import nl.arnoldkolkman.akonotulist.core.RetentionWorker
import java.io.File

class AkoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.recoverInterruptedRecordings()
        RetentionWorker.schedule(this)
        publishShortcuts()
        container.debugConfig?.let { dc ->
            dc.apiKey?.let { container.secrets.setApiKey(it) }
            // One-shot: only while the recording is still waiting (process restarts must not re-enqueue).
            dc.transcribeOnStart.forEach { id ->
                container.appScope.launchIo {
                    if (container.db.recordings().get(id)?.status == RecordingStatus.RECORDED) container.transcription.enqueue(id, fresh = true)
                }
            }
        }
    }

    /** Long-press the launcher icon -> "Opname starten". Dynamic, so it also works for the .debug build. */
    private fun publishShortcuts() {
        val record = ShortcutInfoCompat.Builder(this, "record")
            .setShortLabel("Opname starten")
            .setLongLabel("Nieuwe opname starten")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_mic))
            .setIntent(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_QUICK_RECORD))
            .build()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, listOf(record)) }
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
    val debugConfig: DebugConfig? = DebugConfig.load(app)
    val openAi = debugConfig?.baseUrl
        ?.let { OpenAiClient(apiKey = { secrets.getApiKey() }, baseUrl = it) }
        ?: OpenAiClient(apiKey = { secrets.getApiKey() })
    val transcription by lazy { TranscriptionScheduler(app, this) }
    val categories by lazy { CategoryRepository(this) }
    val processingLog = ProcessingLog(File(app.filesDir, "logs"))

    /** One-shot events, e.g. "transcription done" for the car's spoken notice. */
    val events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 16)

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
        if (s.autoTranscribe && secrets.hasApiKey()) transcription.enqueue(id)
    }

    /** The merged transcript is in the database: run post-processing (if any) and finish. */
    suspend fun onTranscriptStored(id: Long) {
        val s = settings.current()
        val rec = db.recordings().get(id) ?: return
        val model = TranscriptionModels.byId(rec.transcriptionModel)
        val needsSpeakers = s.estimateSpeakers && !model.hasDiarization
        val needsCategory = s.autoCategorize && db.categories().all().isNotEmpty()
        if (s.summaryEnabled || needsSpeakers || needsCategory) {
            db.recordings().setStatus(id, RecordingStatus.SUMMARIZING, 95)
            PostProcessWorker.enqueue(app, id)
        } else {
            finishRecording(id, null)
        }
    }

    /** Marks a recording DONE (optionally with a non-fatal warning) and tells the user. */
    suspend fun finishRecording(id: Long, warning: String?) {
        val rec = db.recordings().get(id) ?: return
        db.recordings().setStatus(id, RecordingStatus.DONE, 100, null, warning)
        Notifications.notifyFinished(app, id, rec.title, success = true, message = warning ?: "Het transcript staat klaar.")
        events.tryEmit(AppEvent.TranscriptionDone(id, rec.title))
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
