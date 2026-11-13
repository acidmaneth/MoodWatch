package com.moodwatch.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** A saved Mood's thumbnail and traits, as last fetched. */
class CollectionThumb(
    val tokenId: Int,
    val expression: String?,
    val mood: String?,
    val image: ByteArray,
    val fetchedAt: Long,
) {
    /** False once Moods have changed expression since this was fetched. */
    val isFresh: Boolean get() = fetchedAt >= MoodDriftScheduler.lastDriftAt()
}

/** The Moods the user saved for quick switching, newest first, with cached thumbnails. */
object MoodCollection {
    const val THUMB_SIZE = 120

    private const val PREFS = "collection"
    private const val KEY_IDS = "ids"

    /** Keeps scrolling through a long Collection from opening dozens of connections at once. */
    private val fetchPermits = Semaphore(3)

    fun ids(context: Context): List<Int> =
        prefs(context).getString(KEY_IDS, "").orEmpty()
            .split(',')
            .mapNotNull { it.toIntOrNull() }

    fun contains(context: Context, id: Int): Boolean = id in ids(context)

    fun add(context: Context, id: Int) {
        save(context, listOf(id) + ids(context).filter { it != id })
    }

    fun remove(context: Context, id: Int) {
        save(context, ids(context).filter { it != id })
        imageFile(context, id).delete()
        infoFile(context, id).delete()
    }

    fun cachedThumb(context: Context, id: Int): CollectionThumb? {
        val image = imageFile(context, id).takeIf { it.exists() } ?: return null
        val info = infoFile(context, id).takeIf { it.exists() }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?: return null
        return CollectionThumb(
            tokenId = id,
            expression = info.optString("expression").ifBlank { null },
            mood = info.optString("mood").ifBlank { null },
            image = image.readBytes(),
            fetchedAt = info.optLong("fetchedAt"),
        )
    }

    /** Downloads a fresh thumbnail for [id] and caches it. */
    suspend fun fetchThumb(context: Context, id: Int): CollectionThumb = fetchPermits.withPermit {
        val preview = MoodRepository.preview(context, id, THUMB_SIZE)
        val thumb = CollectionThumb(
            id, preview.expression, preview.mood, preview.image, System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) { write(context, thumb) }
        thumb
    }

    /** Caches the selected Mood's thumbnail from its already-downloaded image. */
    suspend fun seedFromCurrent(context: Context) = withContext(Dispatchers.IO) {
        val info = MoodRepository.lastInfo(context) ?: return@withContext
        val bytes = MoodRepository.currentImageBytes(context) ?: return@withContext
        if (info.tokenId != MoodRepository.tokenId(context)) return@withContext
        val full = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 },
        ) ?: return@withContext
        val small = Bitmap.createScaledBitmap(full, THUMB_SIZE, THUMB_SIZE, true)
        val jpeg = ByteArrayOutputStream().use {
            small.compress(Bitmap.CompressFormat.JPEG, 88, it)
            it.toByteArray()
        }
        if (small !== full) small.recycle()
        full.recycle()
        write(
            context,
            CollectionThumb(
                info.tokenId, info.expression, info.mood, jpeg,
                MoodRepository.lastCheckedAt(context),
            ),
        )
    }

    private fun write(context: Context, thumb: CollectionThumb) {
        imageFile(context, thumb.tokenId).writeBytes(thumb.image)
        infoFile(context, thumb.tokenId).writeText(
            JSONObject()
                .put("expression", thumb.expression)
                .put("mood", thumb.mood)
                .put("fetchedAt", thumb.fetchedAt)
                .toString()
        )
    }

    private fun save(context: Context, ids: List<Int>) {
        prefs(context).edit { putString(KEY_IDS, ids.joinToString(",")) }
    }

    private fun dir(context: Context) = File(context.filesDir, "collection").apply { mkdirs() }

    private fun imageFile(context: Context, id: Int) = File(dir(context), "$id.jpg")

    private fun infoFile(context: Context, id: Int) = File(dir(context), "$id.json")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
