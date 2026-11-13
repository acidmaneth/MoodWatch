package com.moodwatch.app

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

data class MoodInfo(
    val tokenId: Int,
    val name: String,
    val expression: String?,
    val mood: String?,
    val updatedAt: Long,
)

class MoodNotFoundException(message: String) : IOException(message)

object MoodRepository {
    const val CONTRACT = "0xa6ebbdfcfeafdc7a5a778d6fe514f45bd0a0133d"
    const val DEFAULT_TOKEN_ID = 1184

    private const val RPC_URL = "https://rpc.mainnet.chain.robinhood.com"
    private const val TOKEN_URI_SELECTOR = "c87b56dd"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Wear OS) MoodWatch/1.0"
    private const val FACE_SIZE = 450
    private const val RESIZE_URL = "https://wsrv.nl/?url="
    private const val TAG = "MoodRepository"
    private const val NO_LOAD = -1

    /** The ID of the most recent [load] still in progress, or [NO_LOAD]. */
    private val latestLoad = AtomicInteger(NO_LOAD)

    private const val PREFS = "moods"
    private const val KEY_TOKEN_ID = "token_id"
    private const val KEY_NAME = "name"
    private const val KEY_EXPRESSION = "expression"
    private const val KEY_MOOD = "mood"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_IMAGE_TOKEN_ID = "image_token_id"
    private const val KEY_IMAGE_URL = "image_url"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_URI_PREFIX = "token_uri_prefix"

    fun tokenId(context: Context): Int =
        prefs(context).getInt(KEY_TOKEN_ID, DEFAULT_TOKEN_ID)

    fun imageFile(context: Context): File = File(context.filesDir, "mood.jpg")

    /** The saved image, only if it belongs to the currently selected token. */
    fun currentImageBytes(context: Context): ByteArray? {
        val file = imageFile(context)
        if (!file.exists()) return null
        if (prefs(context).getInt(KEY_IMAGE_TOKEN_ID, -1) != tokenId(context)) return null
        return file.readBytes()
    }

    fun lastInfo(context: Context): MoodInfo? {
        val p = prefs(context)
        val updatedAt = p.getLong(KEY_UPDATED_AT, 0L)
        if (updatedAt == 0L) return null
        return MoodInfo(
            tokenId = p.getInt(KEY_IMAGE_TOKEN_ID, -1),
            name = p.getString(KEY_NAME, null) ?: return null,
            expression = p.getString(KEY_EXPRESSION, null),
            mood = p.getString(KEY_MOOD, null),
            updatedAt = updatedAt,
        )
    }

    /** When the metadata was last fetched successfully, whether or not the image changed. */
    fun lastCheckedAt(context: Context): Long =
        prefs(context).getLong(KEY_CHECKED_AT, 0L)

    /** True if the saved image URL for the selected Mood was confirmed at or after [since]. */
    fun hasBaselineSince(context: Context, since: Long): Boolean {
        val p = prefs(context)
        return p.getInt(KEY_IMAGE_TOKEN_ID, -1) == tokenId(context) &&
            p.getString(KEY_IMAGE_URL, null) != null &&
            p.getLong(KEY_CHECKED_AT, 0L) >= since
    }

    /**
     * Switches to Mood [id]. The selected ID only changes once its image is saved, so the face
     * keeps showing the current Mood while this loads, and an unknown ID changes nothing.
     */
    suspend fun load(context: Context, id: Int) {
        latestLoad.set(id)
        try {
            fetch(context, id, onlyIfNewImage = false) { latestLoad.get() == id }
        } finally {
            latestLoad.compareAndSet(id, NO_LOAD)
        }
    }

    /**
     * Fetches the selected Mood and saves its image. With [onlyIfNewImage], the image is only
     * downloaded when its URL differs from the saved one. Returns true if a new image was saved.
     */
    suspend fun refresh(context: Context, onlyIfNewImage: Boolean = false): Boolean {
        val id = tokenId(context)
        return fetch(context, id, onlyIfNewImage) {
            tokenId(context) == id && latestLoad.get().let { it == NO_LOAD || it == id }
        }
    }

    /** [stillWanted] is checked right before saving, so a superseded fetch is dropped. */
    private suspend fun fetch(
        context: Context,
        id: Int,
        onlyIfNewImage: Boolean,
        stillWanted: () -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        MoodNetwork.withInternet(context) { network ->
            val metadata = fetchMetadata(context, id, network)
            val imageUrl = metadata.optString("image").takeIf { it.isNotBlank() }
                ?: throw IOException("Mood #$id has no image")

            val p = prefs(context)
            val sameImage = p.getInt(KEY_IMAGE_TOKEN_ID, -1) == id &&
                p.getString(KEY_IMAGE_URL, null) == imageUrl &&
                imageFile(context).exists()
            if (onlyIfNewImage && sameImage) {
                p.edit { putLong(KEY_CHECKED_AT, System.currentTimeMillis()) }
                return@withInternet false
            }

            val jpeg = downloadFaceJpeg(resolveUri(imageUrl), network)
            synchronized(this@MoodRepository) {
                if (!stillWanted()) return@withInternet false
                val tmp = File(context.filesDir, "mood.jpg.tmp")
                tmp.writeBytes(jpeg)
                if (!tmp.renameTo(imageFile(context))) throw IOException("Could not save image")
                saveInfo(context, id, imageUrl, metadata)
            }
            requestFaceUpdate(context)
            !sameImage
        }
    }

    private fun requestFaceUpdate(context: Context) {
        ComplicationDataSourceUpdateRequester.create(
            context,
            ComponentName(context, MoodComplicationService::class.java),
        ).requestUpdateAll()
    }

    private fun saveInfo(context: Context, id: Int, imageUrl: String, metadata: JSONObject) {
        val attributes = metadata.optJSONArray("attributes")
        val info = MoodInfo(
            tokenId = id,
            name = metadata.optString("name").ifBlank { "Moods #$id" },
            expression = attributes.trait("Expression"),
            mood = attributes.trait("Mood"),
            updatedAt = System.currentTimeMillis(),
        )
        prefs(context).edit {
            putInt(KEY_TOKEN_ID, id)
            putInt(KEY_IMAGE_TOKEN_ID, id)
            putString(KEY_IMAGE_URL, imageUrl)
            putString(KEY_NAME, info.name)
            putString(KEY_EXPRESSION, info.expression)
            putString(KEY_MOOD, info.mood)
            putLong(KEY_UPDATED_AT, info.updatedAt)
            putLong(KEY_CHECKED_AT, info.updatedAt)
        }
    }

    private fun fetchTokenUri(tokenId: Int, network: Network?): String {
        val data = "0x$TOKEN_URI_SELECTOR" + tokenId.toString(16).padStart(64, '0')
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "eth_call")
            .put(
                "params",
                JSONArray()
                    .put(JSONObject().put("to", CONTRACT).put("data", data))
                    .put("latest"),
            )
        val response = JSONObject(httpPost(RPC_URL, body.toString(), network))
        response.optJSONObject("error")?.let {
            throw MoodNotFoundException("Mood #$tokenId not found (${it.optString("message")})")
        }
        val result = response.optString("result")
        if (result.length <= 2) throw MoodNotFoundException("Mood #$tokenId not found")
        return decodeAbiString(result)
    }

    /**
     * Token URIs are "<prefix><id>", so once the prefix is known from one chain lookup the
     * metadata is fetched directly. Any failure on that path falls back to the chain.
     */
    private fun fetchMetadata(context: Context, id: Int, network: Network?): JSONObject {
        prefs(context).getString(KEY_URI_PREFIX, null)?.let { prefix ->
            try {
                return fetchMetadataAt(prefix + id, network)
            } catch (e: Exception) {
                Log.i(TAG, "Direct metadata for #$id failed, asking the chain: ${e.message}")
            }
        }
        val tokenUri = resolveUri(fetchTokenUri(id, network))
        if (tokenUri.startsWith("http") && tokenUri.endsWith("/$id")) {
            prefs(context).edit { putString(KEY_URI_PREFIX, tokenUri.removeSuffix("$id")) }
        }
        return fetchMetadataAt(tokenUri, network)
    }

    private fun fetchMetadataAt(uri: String, network: Network?): JSONObject {
        val dataPrefix = "data:application/json"
        if (uri.startsWith(dataPrefix)) {
            val payload = uri.substringAfter(",")
            val json = if (uri.substringBefore(",").endsWith(";base64")) {
                String(Base64.decode(payload, Base64.DEFAULT))
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8")
            }
            return JSONObject(json)
        }
        return JSONObject(String(httpGet(uri, network)))
    }

    /**
     * The source images are 2048px and 2–5 MB, far too slow over the watch's Bluetooth or LTE
     * link, so a 450px copy (~50 KB) comes from a resizing proxy, falling back to the original.
     */
    private fun downloadFaceJpeg(url: String, network: Network?): ByteArray {
        if (url.startsWith("https://") || url.startsWith("http://")) {
            val resized = RESIZE_URL + URLEncoder.encode(url, "UTF-8") +
                "&w=$FACE_SIZE&h=$FACE_SIZE&fit=cover&output=jpg&q=88"
            try {
                val bytes = httpGet(resized, network)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth == FACE_SIZE && bounds.outHeight == FACE_SIZE) return bytes
                Log.w(TAG, "Resized image was ${bounds.outWidth}x${bounds.outHeight}")
            } catch (e: IOException) {
                Log.w(TAG, "Resized image failed, using the original: ${e.message}")
            }
        }
        val bitmap = downloadSquareBitmap(url, network)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private fun downloadSquareBitmap(url: String, network: Network?): Bitmap {
        val bytes = httpGet(url, network)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= FACE_SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IOException("Could not read the Mood image")

        val side = min(decoded.width, decoded.height)
        val cropped = Bitmap.createBitmap(
            decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side,
        )
        val scaled = Bitmap.createScaledBitmap(cropped, FACE_SIZE, FACE_SIZE, true)
        if (cropped !== decoded) decoded.recycle()
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    private fun resolveUri(uri: String): String = when {
        uri.startsWith("ipfs://ipfs/") -> "https://ipfs.io/ipfs/" + uri.removePrefix("ipfs://ipfs/")
        uri.startsWith("ipfs://") -> "https://ipfs.io/ipfs/" + uri.removePrefix("ipfs://")
        uri.startsWith("ar://") -> "https://arweave.net/" + uri.removePrefix("ar://")
        else -> uri
    }

    private fun decodeAbiString(hex: String): String {
        val raw = hex.removePrefix("0x")
        val offset = raw.substring(0, 64).toBigInteger(16).toInt() * 2
        val length = raw.substring(offset, offset + 64).toBigInteger(16).toInt()
        val start = offset + 64
        val bytes = ByteArray(length) { i ->
            raw.substring(start + i * 2, start + i * 2 + 2).toInt(16).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun httpPost(url: String, json: String, network: Network?): String {
        val connection = open(url, network).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(json.toByteArray()) }
        return String(read(connection))
    }

    private fun httpGet(url: String, network: Network?): ByteArray = read(open(url, network))

    private fun open(url: String, network: Network?): HttpURLConnection =
        ((network?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }

    private fun read(connection: HttpURLConnection): ByteArray {
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Server returned $code for ${connection.url.host}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.trait(type: String): String? {
        if (this == null) return null
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            if (item.optString("trait_type") == type) return item.optString("value").ifBlank { null }
        }
        return null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
