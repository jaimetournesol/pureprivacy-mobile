package ai.tournesol.pureprivacy.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Bitmap helpers that always **downsample** — decoding a full-resolution image straight to
 * a bitmap is what makes the app OOM on big photos. [decodeSampled] picks an `inSampleSize`
 * from the image's declared bounds so we never allocate more than we show, and
 * [toAvatarJpeg] shrinks a picked photo to a small square-ish JPEG for use as an avatar.
 */
object ImageUtil {

    /** Decode [bytes] downsampled so its long edge is roughly [reqPx] — bounded memory. */
    fun decodeSampled(bytes: ByteArray, reqPx: Int): Bitmap? {
        if (bytes.isEmpty() || reqPx <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        var sample = 1
        while (longEdge / (sample * 2) >= reqPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    /** Downscale + JPEG-encode a picked image into a compact avatar (<= [maxPx] long edge).
     *  Returns null if the bytes aren't a decodable image. */
    fun toAvatarJpeg(bytes: ByteArray, maxPx: Int = 512, quality: Int = 85): ByteArray? {
        val bmp = decodeSampled(bytes, maxPx) ?: return null
        val long = maxOf(bmp.width, bmp.height)
        val scaled = if (long > maxPx) {
            val ratio = maxPx.toFloat() / long
            Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt().coerceAtLeast(1),
                (bmp.height * ratio).toInt().coerceAtLeast(1), true)
        } else bmp
        val out = ByteArrayOutputStream()
        val ok = runCatching { scaled.compress(Bitmap.CompressFormat.JPEG, quality, out) }.getOrDefault(false)
        return if (ok) out.toByteArray() else null
    }
}
