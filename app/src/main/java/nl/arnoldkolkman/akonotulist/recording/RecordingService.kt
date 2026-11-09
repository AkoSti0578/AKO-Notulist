package nl.arnoldkolkman.akonotulist.recording

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.arnoldkolkman.akonotulist.R
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.core.Format
import nl.arnoldkolkman.akonotulist.core.Notifications
import nl.arnoldkolkman.akonotulist.data.MarkerEntity
import nl.arnoldkolkman.akonotulist.data.RecordingEntity
import nl.arnoldkolkman.akonotulist.data.RecordingSource
import nl.arnoldkolkman.akonotulist.data.RecordingStatus
import java.io.File
import kotlin.math.ln

/**
 * Microphone foreground service (foregroundServiceType="microphone"). Keeps recording with the
 * screen off; the ongoing notification offers Pause/Resume and Stop.
 *
 * Audio: AAC in MPEG-4 (.m4a), mono, 16 kHz, 32 kbps (~14 MB per hour) — ideal for speech
 * and far below the 25 MB API limit per 10-minute chunk.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { appContainer }
    private val controller get() = container.recorder

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingId: Long? = null
    private var title: String = ""
    private var segmentStartedAt = 0L // elapsedRealtime when current (unpaused) span started
    private var accumulatedMs = 0L // time recorded before the current span
    private var paused = false
    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_MARK -> mark()
            else -> if (recorder == null) stopSelf()
        }
        // Do not restart automatically after a kill: a half-written MP4 can't be resumed.
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null || stopping) return
        val now = System.currentTimeMillis()
        title = Format.defaultTitle(now)
        // startForeground must happen promptly after startForegroundService().
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_RECORDING, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            controller.update { RecorderState() }
            stopSelf()
            return
        }

        val dir = File(filesDir, "audio").apply { mkdirs() }
        val file = File(dir, "rec_$now.m4a")
        outputFile = file

        val r = try {
            createRecorder(file).also { it.start() }
        } catch (e: Exception) {
            file.delete()
            controller.update { RecorderState() }
            stopEverything()
            return
        }
        recorder = r
        acquireWakeLock()
        segmentStartedAt = SystemClock.elapsedRealtime()
        accumulatedMs = 0
        paused = false

        scope.launch {
            val id = withContext(Dispatchers.IO) {
                container.db.recordings().insert(
                    RecordingEntity(
                        title = title,
                        createdAt = now,
                        audioPath = file.absolutePath,
                        source = RecordingSource.MICROPHONE,
                        status = RecordingStatus.RECORDING,
                    )
                )
            }
            recordingId = id
            controller.update { it.copy(active = true, recordingId = id) }
        }
        startTicker()
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(file: File): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(32_000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        return r
    }

    private fun elapsedMs(): Long =
        if (paused) accumulatedMs else accumulatedMs + (SystemClock.elapsedRealtime() - segmentStartedAt)

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var n = 0
            while (isActive) {
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                controller.update { it.copy(active = true, elapsedMs = elapsedMs(), paused = paused, level = levelOf(amp)) }
                // Refresh the notification text every few seconds (chronometer handles the clock).
                if (n++ % 10 == 0) refreshNotification()
                delay(200)
            }
        }
    }

    /** Map MediaRecorder's 0..32767 amplitude onto a perceptual 0..1 scale. */
    private fun levelOf(amp: Int): Float {
        if (amp <= 0) return 0f
        val db = 20 * ln(amp / 32767.0) / ln(10.0) // -90..0
        return ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
    }

    private fun pause() {
        val r = recorder ?: return
        if (paused) return
        runCatching { r.pause() }.onSuccess {
            accumulatedMs = elapsedMs()
            paused = true
            controller.update { it.copy(paused = true, elapsedMs = accumulatedMs, level = 0f) }
            refreshNotification()
        }
    }

    private fun resume() {
        val r = recorder ?: return
        if (!paused) return
        runCatching { r.resume() }.onSuccess {
            segmentStartedAt = SystemClock.elapsedRealtime()
            paused = false
            controller.update { it.copy(paused = false) }
            refreshNotification()
        }
    }

    /** Flags the current moment as important. Stored with the recording's own timeline. */
    private fun mark() {
        if (recorder == null) return
        val id = recordingId ?: return
        val at = elapsedMs()
        controller.update { it.copy(markers = it.markers + 1) }
        scope.launch(Dispatchers.IO) {
            container.db.markers().insert(MarkerEntity(recordingId = id, timeMs = at, label = "Markering"))
        }
        refreshNotification()
    }

    private fun stop() {
        if (stopping) return
        stopping = true
        val r = recorder
        val file = outputFile
        val duration = elapsedMs()
        recorder = null
        tickJob?.cancel()
        val ok = r != null && runCatching { r.stop() }.isSuccess
        runCatching { r?.release() }
        releaseWakeLock()

        scope.launch {
            // The DB insert may still be in flight for very short recordings.
            var id = recordingId
            var tries = 0
            while (id == null && tries++ < 20) { delay(50); id = recordingId }
            if (id != null) container.onRecordingStopped(id, file, duration, ok)
            controller.update { RecorderState() }
            stopEverything()
        }
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshNotification() {
        if (recorder == null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(this).notify(Notifications.ID_RECORDING, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val elapsed = elapsedMs()
        val b = NotificationCompat.Builder(this, Notifications.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Opname gepauzeerd" else "Opname loopt")
            .setContentText(if (paused) "$title · ${Format.duration(elapsed)}" else title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openAppIntent(this))
        if (!paused) {
            b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsed).setShowWhen(true)
        }
        if (paused) b.addAction(R.drawable.ic_mic, "Hervatten", action(ACTION_RESUME, 2))
        else b.addAction(R.drawable.ic_pause, "Pauze", action(ACTION_PAUSE, 3))
        if (!paused) b.addAction(R.drawable.ic_star, "Markeer", action(ACTION_MARK, 4))
        b.addAction(R.drawable.ic_stop, "Stop", action(ACTION_STOP, 1))
        return b.build()
    }

    private fun action(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        // Bounded so a bug can never drain the battery forever (12 h covers any meeting).
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AKONotulist:recording").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        if (recorder != null) {
            // Killed while recording (e.g. app swiped away): finalise what we have.
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            val id = recordingId
            val file = outputFile
            val duration = elapsedMs()
            if (id != null) container.appScope.launch { container.onRecordingStopped(id, file, duration, true) }
            controller.update { RecorderState() }
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "nl.arnoldkolkman.akonotulist.action.START"
        const val ACTION_STOP = "nl.arnoldkolkman.akonotulist.action.STOP"
        const val ACTION_PAUSE = "nl.arnoldkolkman.akonotulist.action.PAUSE"
        const val ACTION_RESUME = "nl.arnoldkolkman.akonotulist.action.RESUME"
        const val ACTION_MARK = "nl.arnoldkolkman.akonotulist.action.MARK"

    }
}
