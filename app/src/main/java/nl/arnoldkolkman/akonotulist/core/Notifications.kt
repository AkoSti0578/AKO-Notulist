package nl.arnoldkolkman.akonotulist.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import nl.arnoldkolkman.akonotulist.MainActivity
import nl.arnoldkolkman.akonotulist.R

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_PROCESSING = "processing"
    const val CHANNEL_DONE = "done"

    const val ID_RECORDING = 1
    private const val ID_PROCESSING_BASE = 10_000
    private const val ID_DONE_BASE = 20_000

    fun processingId(recordingId: Long) = ID_PROCESSING_BASE + (recordingId % 10_000).toInt()
    fun doneId(recordingId: Long) = ID_DONE_BASE + (recordingId % 10_000).toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RECORDING, "Opname", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Melding tijdens een lopende opname"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_PROCESSING, "Transcriberen", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Voortgang van uploaden en transcriberen"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_DONE, "Transcriptie klaar", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Melding wanneer een transcriptie klaar of mislukt is"
                },
            )
        )
    }

    fun openAppIntent(context: Context, recordingId: Long? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (recordingId != null) putExtra(MainActivity.EXTRA_RECORDING_ID, recordingId)
        }
        return PendingIntent.getActivity(
            context, recordingId?.toInt() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun processing(context: Context, recordingId: Long, title: String, text: String, progress: Int) =
        NotificationCompat.Builder(context, CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setContentIntent(openAppIntent(context, recordingId))
            .build()

    fun notifyFinished(context: Context, recordingId: Long, title: String, success: Boolean, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (success) "Transcriptie klaar" else "Transcriptie mislukt")
            .setContentText("$title — $message")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, recordingId))
            .build()
        NotificationManagerCompat.from(context).notify(doneId(recordingId), n)
    }
}
