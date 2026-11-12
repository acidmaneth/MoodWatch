package com.moodwatch.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** One check during the 9:00–9:15 AM Pacific window; schedules the next one if nothing changed. */
class DriftCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val now = System.currentTimeMillis()
        if (MoodDriftScheduler.isDoneToday(context) || now < MoodDriftScheduler.windowStart()) {
            MoodDriftScheduler.reschedule(context)
            return Result.success()
        }

        // Without a pre-drift baseline the first fetch can't tell old from new, so it only
        // becomes the baseline and the window keeps checking.
        val baselineSince = MoodDriftScheduler.windowStart() - TimeUnit.DAYS.toMillis(1)
        val hasBaseline = MoodRepository.hasBaselineSince(context, baselineSince)
        val changed = try {
            MoodRepository.refresh(context, onlyIfNewImage = true) && hasBaseline
        } catch (e: Exception) {
            false
        }

        val nextAttempt = System.currentTimeMillis() + MoodDriftScheduler.RETRY_INTERVAL_MS
        if (changed || nextAttempt > MoodDriftScheduler.windowEnd()) {
            MoodDriftScheduler.markDoneToday(context)
            MoodDriftScheduler.reschedule(context)
        } else {
            MoodDriftScheduler.scheduleRetry(context)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "mood-drift-check"

        // No network constraint: with Wi-Fi/LTE powered down there is no default network, so
        // CONNECTED could hold the job past the window. MoodNetwork brings a network up instead.
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DriftCheckWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
