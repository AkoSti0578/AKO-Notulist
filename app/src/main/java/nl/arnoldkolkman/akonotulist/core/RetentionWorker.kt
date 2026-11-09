package nl.arnoldkolkman.akonotulist.core

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import nl.arnoldkolkman.akonotulist.appContainer
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Daily job: deletes audio of finished recordings older than the retention period.
 * Transcript, speakers and summary are kept.
 */
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        val days = c.settings.current().audioRetentionDays
        if (days <= 0) return Result.success()
        val before = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        c.db.recordings().doneWithAudioBefore(before).forEach { rec ->
            rec.audioPath?.let { File(it).delete() }
            c.db.recordings().markAudioDeleted(rec.id, System.currentTimeMillis())
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "audio_retention",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
