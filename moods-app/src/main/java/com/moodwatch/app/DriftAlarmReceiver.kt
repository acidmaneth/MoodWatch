package com.moodwatch.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DriftAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK -> DriftCheckWorker.enqueue(context)
            else -> MoodDriftScheduler.reschedule(context)
        }
    }

    companion object {
        const val ACTION_CHECK = "com.moodwatch.app.action.DRIFT_CHECK"
    }
}
