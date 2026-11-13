package com.moodwatch.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The always-on copy of a Mood: the same picture, dimmed by however much that Mood needs so the
 * whole ambient face averages about 13% luminance, under Play's 15% limit (WO-P7). Bright Moods
 * are dimmed more than dark ones, so each shows as brightly as the limit allows.
 */
object AmbientMood {
    /**
     * Average brightness of the picture inside the round screen, as a fraction of white. The
     * time, date and bezel complications drawn over it add about 3%.
     */
    private const val TARGET = 0.10

    /** A JPEG of the dimmed picture, or null if [jpeg] can't be decoded. */
    fun jpeg(jpeg: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        // Only the inscribed circle is visible; everything outside it is left black.
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val r2 = min(w, h) * min(w, h) / 4.0
        val inside = BooleanArray(w * h)
        var luma = 0.0
        var mean = 0.0
        var count = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > r2) continue
                inside[i] = true
                val p = pixels[i]
                val red = p shr 16 and 0xFF
                val green = p shr 8 and 0xFF
                val blue = p and 0xFF
                luma += 0.2126 * red + 0.7152 * green + 0.0722 * blue
                mean += (red + green + blue) / 3.0
                count++
            }
        }
        if (count == 0) return null

        // Luma undercounts saturated blues and reds, so whichever measure is higher sets the dimming.
        val level = max(luma, mean) / count / 255
        val scale = if (level > TARGET) TARGET / level else 1.0
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = if (!inside[i]) {
                0xFF000000.toInt()
            } else {
                val red = ((p shr 16 and 0xFF) * scale).roundToInt()
                val green = ((p shr 8 and 0xFF) * scale).roundToInt()
                val blue = ((p and 0xFF) * scale).roundToInt()
                (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        val dimmed = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use {
            dimmed.compress(Bitmap.CompressFormat.JPEG, 90, it)
            dimmed.recycle()
            it.toByteArray()
        }
    }
}
