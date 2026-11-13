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
        val bytes = MoodRepository.currentImageBytes(this)
            ?: resources.openRawResource(R.drawable.mood_preview).use { it.readBytes() }
        return build(type, bytes, "Moods")
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
        return build(request.complicationType, bytes, name)
    }

    private fun build(type: ComplicationType, jpeg: ByteArray, name: String): ComplicationData? {
        val icon = Icon.createWithData(jpeg, 0, jpeg.size)
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

            // Photo complications have no always-on image, so the face asks for this type and
            // shows the dimmed copy while ambient.
            ComplicationType.SMALL_IMAGE ->
                SmallImageComplicationData.Builder(
                    SmallImage.Builder(icon, SmallImageType.PHOTO)
                        .setAmbientImage(
                            AmbientMood.jpeg(jpeg)?.let { Icon.createWithData(it, 0, it.size) },
                        )
                        .build(),
                    description,
                ).setTapAction(tap).build()

            else -> null
        }
    }
}
