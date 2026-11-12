package com.moodwatch.app

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.util.Base64
import androidx.core.content.edit
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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

    private const val PREFS = "moods"
    private const val KEY_TOKEN_ID = "token_id"
    private const val KEY_NAME = "name"
    private const val KEY_EXPRESSION = "expression"
    private const val KEY_MOOD = "mood"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_IMAGE_TOKEN_ID = "image_token_id"
    private const val KEY_IMAGE_URL = "image_url"
    private const val KEY_CHECKED_AT = "checked_at"

    fun tokenId(context: Context): Int =
        prefs(context).getInt(KEY_TOKEN_ID, DEFAULT_TOKEN_ID)

    fun setTokenId(context: Context, tokenId: Int) {
        prefs(context).edit { putInt(KEY_TOKEN_ID, tokenId) }
    }

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
     * Fetches the selected Mood and saves its image. With [onlyIfNewImage], the image is only
     * downloaded when its URL differs from the saved one. Returns true if a new image was saved.
     */
    suspend fun refresh(context: Context, onlyIfNewImage: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            MoodNetwork.withInternet(context) { network ->
                refreshOn(context, network, onlyIfNewImage)
            }
        }

    private fun refreshOn(context: Context, network: Network?, onlyIfNewImage: Boolean): Boolean {
        val id = tokenId(context)
        val tokenUri = resolveUri(fetchTokenUri(id, network))
        val metadata = fetchMetadata(tokenUri, network)
        val imageUrl = metadata.optString("image").takeIf { it.isNotBlank() }
            ?: throw IOException("Mood #$id has no image")

        val p = prefs(context)
        val sameImage = p.getInt(KEY_IMAGE_TOKEN_ID, -1) == id &&
            p.getString(KEY_IMAGE_URL, null) == imageUrl &&
            imageFile(context).exists()
        if (onlyIfNewImage && sameImage) {
            p.edit { putLong(KEY_CHECKED_AT, System.currentTimeMillis()) }
            return false
        }

        save(context, id, imageUrl, metadata, network)
        return !sameImage
    }

    private fun save(
        context: Context,
        id: Int,
        imageUrl: String,
        metadata: JSONObject,
        network: Network?,
    ) {
        val bitmap = downloadSquareBitmap(resolveUri(imageUrl), network)

        synchronized(this) {
            // A slower fetch for a previously selected ID must not overwrite the current one.
            if (tokenId(context) != id) {
                bitmap.recycle()
                return
            }
            val tmp = File(context.filesDir, "mood.jpg.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            if (!tmp.renameTo(imageFile(context))) throw IOException("Could not save image")
            saveInfo(context, id, imageUrl, metadata)
        }

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

    private fun fetchMetadata(uri: String, network: Network?): JSONObject {
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
