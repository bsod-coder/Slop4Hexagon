@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "ClassName")

package android.graphics

import java.io.OutputStream

class Rect {
    constructor()
    constructor(left: Int, top: Int, right: Int, bottom: Int)
    fun width(): Int = 0
    fun height(): Int = 0
}

object Color {
    fun rgb(r: Int, g: Int, b: Int): Int = 0
    const val BLACK = 0
    const val WHITE = -1
}

class Bitmap {
    val width: Int get() = 0
    val height: Int get() = 0
    val config: Config? get() = null
    var isRecycled: Boolean = false
    fun getPixels(pixels: IntArray?, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {}
    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {}
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean = true
    fun recycle() {}

    enum class Config { ARGB_8888, RGB_565, ALPHA_8 }
    enum class CompressFormat { PNG, JPEG, WEBP }

    companion object {
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap()
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap = Bitmap()
    }
}

class BitmapFactory {
    class Options {
        var inJustDecodeBounds: Boolean = false
        var inSampleSize: Int = 1
        var inPreferredConfig: Bitmap.Config? = null
        var outWidth: Int = 0
        var outHeight: Int = 0
        var outMimeType: String? = null
    }

    companion object {
        fun decodeStream(stream: java.io.InputStream?, outPadding: Rect?, options: Options?): Bitmap? = null
        fun decodeByteArray(data: ByteArray?, offset: Int, length: Int): Bitmap? = null
        fun decodeByteArray(data: ByteArray?, offset: Int, length: Int, options: Options?): Bitmap? = null
    }
}

class Canvas(bitmap: Bitmap?) {
    fun drawColor(color: Int) {}
    fun drawText(text: String, x: Float, y: Float, paint: Paint) {}
}

class Paint {
    var color: Int = 0
    var textSize: Float = 0f
    var isAntiAlias: Boolean = false
}
