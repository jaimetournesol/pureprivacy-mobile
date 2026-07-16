package ai.tournesol.pureprivacy.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Bitmap helpers that avoid the classic photo OOM. [decodeSampled] never allocates more
 * than we display; [toAvatarJpeg] always shrinks a picked photo down to a small avatar;
 * and [downscaleIfLarger] is the *threshold-gated* one used on the send path — it only
 * touches an image whose long edge is bigger than the cap, so normal photos are sent
 * untouched at full quality and only genuinely huge ones get shrunk.
 */
object ImageUtil {

    /** Decode [bytes] downsampled so its long edge is roughly [reqPx] — bounded memory.
     *  Used to render thumbnails/avatars; the full-res original is untouched (saving an
     *  attachment always re-fetches the real bytes). */
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

    /** The pixel long-edge above which a *sent* image is considered "huge" and gets capped.
     *  Set generously so ordinary phone photos (≈4032px) pass through at full quality — only
     *  panoramas / very high-MP shots exceed it. */
    const val SEND_MAX_PX = 4096

    /** Downscale + JPEG-encode a picked image into a compact avatar (<= [maxPx] long edge).
     *  Avatars are *always* shrunk (they're rendered tiny). Null if undecodable. */
    fun toAvatarJpeg(bytes: ByteArray, maxPx: Int = 512, quality: Int = 85): ByteArray? =
        scaledJpeg(bytes, maxPx, quality)

    /** For the SEND path: return a downscaled JPEG **only if** [bytes] is an image whose
     *  long edge exceeds [maxPx]; otherwise null — a signal to "send the original as-is,
     *  full quality". This keeps normal photos pristine and only caps the giant ones. */
    fun downscaleIfLarger(bytes: ByteArray, maxPx: Int = SEND_MAX_PX, quality: Int = 92): ByteArray? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0 || longEdge <= maxPx) return null   // not a decodable image, or small enough
        return scaledJpeg(bytes, maxPx, quality)
    }

    /** Sampled-decode + exact-scale [bytes] to [maxPx] long edge, then JPEG-encode. */
    private fun scaledJpeg(bytes: ByteArray, maxPx: Int, quality: Int): ByteArray? {
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
