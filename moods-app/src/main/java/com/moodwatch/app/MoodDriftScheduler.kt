package com.moodwatch.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Moods drift to a new expression every day at 9:00 AM Pacific. Between 9:00 and 9:15 the watch
 * checks once a minute and stops for the day as soon as the image changes.
 */
object MoodDriftScheduler {
    private val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    private val WINDOW_START: LocalTime = LocalTime.of(9, 0)
    private val WINDOW_END: LocalTime = LocalTime.of(9, 15)
    const val RETRY_INTERVAL_MS = 60_000L
    private const val DRIFT_SETTLE_MS = 5 * 60_000L

    private const val PREFS = "moods_drift"
    private const val KEY_DONE_DATE = "done_date"
    private const val KEY_NEXT_ALARM_AT = "next_alarm_at"

    fun today(): LocalDate = ZonedDateTime.now(ZONE).toLocalDate()

    fun windowStart(date: LocalDate = today()): Long =
        date.atTime(WINDOW_START).atZone(ZONE).toInstant().toEpochMilli()

    fun windowEnd(date: LocalDate = today()): Long =
        date.atTime(WINDOW_END).atZone(ZONE).toInstant().toEpochMilli()

    /** Roughly when Moods last changed expression: the latest 9:05 AM Pacific before [now]. */
    fun lastDriftAt(now: Long = System.currentTimeMillis()): Long {
        val today = windowStart() + DRIFT_SETTLE_MS
        return if (now >= today) today else windowStart(today().minusDays(1)) + DRIFT_SETTLE_MS
    }

    fun isDoneToday(context: Context): Boolean =
        prefs(context).getString(KEY_DONE_DATE, null) == today().toString()

    fun markDoneToday(context: Context) {
        prefs(context).edit { putString(KEY_DONE_DATE, today().toString()) }
    }

    /** True if today's window has passed without the watch ever checking for the new image. */
    fun missedToday(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return now > windowEnd() && !isDoneToday(context) &&
            MoodRepository.lastCheckedAt(context) < windowStart()
    }

    /** Schedules the next check unless an alarm at or before that time is already pending. */
    fun ensureScheduled(context: Context) {
        val now = System.currentTimeMillis()
        val target = nextCheckTime(context, now)
        val pending = prefs(context).getLong(KEY_NEXT_ALARM_AT, 0L)
        if (pending in now..target) return
        setAlarm(context, target)
    }

    /** Re-arms the alarm unconditionally; alarms are cleared on reboot and app updates. */
    fun reschedule(context: Context) {
        setAlarm(context, nextCheckTime(context, System.currentTimeMillis()))
    }

    fun scheduleRetry(context: Context) {
        setAlarm(context, System.currentTimeMillis() + RETRY_INTERVAL_MS)
    }

    private fun nextCheckTime(context: Context, now: Long): Long {
        val today = today()
        return if (!isDoneToday(context) && now < windowEnd(today)) {
            maxOf(now + 2_000L, windowStart(today))
        } else {
            windowStart(today.plusDays(1))
        }
    }

    private fun setAlarm(context: Context, triggerAt: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DriftAlarmReceiver::class.java).setAction(DriftAlarmReceiver.ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
        prefs(context).edit { putLong(KEY_NEXT_ALARM_AT, triggerAt) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
