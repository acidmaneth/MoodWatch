package com.moodwatch.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class MoodComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val icon = MoodRepository.currentImageBytes(this)
            ?.let { Icon.createWithData(it, 0, it.size) }
            ?: Icon.createWithResource(this, R.drawable.mood_preview)
        return build(type, icon, "Moods")
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        MoodDriftScheduler.ensureScheduled(this)
        val bytes = MoodRepository.currentImageBytes(this)
        if (bytes == null) {
            MoodRefreshWorker.refreshNow(this)
            return NoDataComplicationData()
        }
        if (MoodDriftScheduler.missedToday(this)) {
            MoodRefreshWorker.refreshNow(this)
        }
        val name = MoodRepository.lastInfo(this)?.name ?: "Moods"
        return build(request.complicationType, Icon.createWithData(bytes, 0, bytes.size), name)
    }

    private fun build(type: ComplicationType, icon: Icon, name: String): ComplicationData? {
        val description = PlainComplicationText.Builder(name).build()
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return when (type) {
            ComplicationType.PHOTO_IMAGE ->
                PhotoImageComplicationData.Builder(icon, description)
                    .setTapAction(tap)
                    .build()

            ComplicationType.SMALL_IMAGE ->
                SmallImageComplicationData.Builder(
                    SmallImage.Builder(icon, SmallImageType.PHOTO).build(),
                    description,
                ).setTapAction(tap).build()

            else -> null
        }
    }
}
