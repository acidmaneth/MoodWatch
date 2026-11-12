package com.moodwatch.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** A one-off refresh, used when no image is saved yet or today's drift window was missed. */
class MoodRefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        try {
            MoodRepository.refresh(applicationContext)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }

    companion object {
        private const val NOW_WORK = "mood-refresh-now"
        private val LEGACY_WORK = listOf("daily-mood-refresh", "mood-refresh-daily")

        /**
         * With [replace], a pending or running refresh (possibly for an older ID) is restarted.
         * No network constraint, for the same reason as [DriftCheckWorker.enqueue].
         */
        fun refreshNow(context: Context, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<MoodRefreshWorker>().build()
            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK, policy, request)
        }

        fun cancelLegacySchedules(context: Context) {
            val workManager = WorkManager.getInstance(context)
            LEGACY_WORK.forEach { workManager.cancelUniqueWork(it) }
        }
    }
}
