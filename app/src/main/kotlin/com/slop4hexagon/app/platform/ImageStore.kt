package com.slop4hexagon.app.platform

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream

// Note: this class deliberately has no "placeholder image" helper. If a run
// cannot produce output, the UI shows the failure text instead of fake pixels.
/**
 * Real image I/O: source-image loading/preprocessing for image-to-image requests,
 * and saving generated PNG output either into the system media store or to a
 * user-picked SAF document.
 *
 * The preprocessing here is the part that would feed a pipeline engine; the
 * engine itself is not implemented in this build, so nothing pretends that a
 * decoded bitmap has been "generated".
 */
object ImageStore {

    private const val TAG = "ImageStore"

    /** Decoded, resized source image ready for a pipeline request. */
    data class PreparedImage(
        val width: Int,
        val height: Int,
        /** RGBA bytes, row-major, as [com.slop4hexagon.core.backend.SourceImage] expects. */
        val rgba: ByteArray,
        /** Normalized CHW floats in [-1, 1] (the conventional diffusion input range). */
        val normalizedChw: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PreparedImage) return false
            return width == other.width && height == other.height &&
                rgba.contentEquals(other.rgba) && normalizedChw.contentEquals(other.normalizedChw)
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + rgba.contentHashCode()
            result = 31 * result + normalizedChw.contentHashCode()
            return result
        }
    }

    /**
     * Decodes [uri] and resizes it to exactly [targetWidth] x [targetHeight]
     * using a two-step downscale (inSampleSize first) so large camera images do
     * not blow up the heap.
     */
    fun prepareSourceImage(context: Context, uri: Uri, targetWidth: Int, targetHeight: Int): PreparedImage {
        require(targetWidth > 0 && targetHeight > 0) { "target size must be positive" }
        val bounds = readBounds(context, uri)
        val sample = computeInSampleSize(bounds.width(), bounds.height(), maxOf(targetWidth, targetHeight) * 2)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("could not open image stream")

        val scaled = if (decoded.width == targetWidth && decoded.height == targetHeight) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        val pixels = IntArray(targetWidth * targetHeight)
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        if (scaled !== decoded) scaled.recycle() else decoded.recycle()

        val rgba = ByteArray(pixels.size * 4)
        val chw = FloatArray(3 * pixels.size)
        val plane = pixels.size
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val a = (c shr 24) and 0xFF
            rgba[i * 4] = r.toByte()
            rgba[i * 4 + 1] = g.toByte()
            rgba[i * 4 + 2] = b.toByte()
            rgba[i * 4 + 3] = a.toByte()
            // Alpha-composited over black, then mapped [0,255] -> [-1,1].
            chw[i] = (r / 255f) * 2f - 1f
            chw[plane + i] = (g / 255f) * 2f - 1f
            chw[2 * plane + i] = (b / 255f) * 2f - 1f
        }
        return PreparedImage(targetWidth, targetHeight, rgba, chw)
    }

    private fun readBounds(context: Context, uri: Uri): android.graphics.Rect {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val w = if (options.outWidth > 0) options.outWidth else 1
        val h = if (options.outHeight > 0) options.outHeight else 1
        return android.graphics.Rect(0, 0, w, h)
    }

    /** Power-of-two downscale factor so huge camera images never fill the heap. */
    fun computeInSampleSize(imageWidth: Int, imageHeight: Int, reqMinSide: Int): Int {
        var sample = 1
        val maxSide = maxOf(imageWidth, imageHeight)
        while (maxSide / (sample * 2) >= reqMinSide && sample < 128) sample *= 2
        return sample
    }

    fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** Writes PNG bytes into the system Pictures collection (no permission on API 29+). */
    fun savePngToMediaStore(context: Context, displayName: String, png: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Slop4Hexagon")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(png) }
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "media store write failed", t)
            runCatching { context.contentResolver.delete(uri, null, null) }
            null
        }
    }

    /** Writes arbitrary bytes (e.g. an exported JSON report) to a SAF document. */
    fun writeBytes(context: Context, target: Uri, bytes: ByteArray) {
        // Mode "w" (not "wt") is the SAF-portable request; some document
        // providers reject truncate flags.
        val stream: OutputStream = context.contentResolver.openOutputStream(target, "w")
            ?: error("no writable stream for $target")
        stream.use { it.write(bytes) }
    }

}
