package com.slop4hexagon.app.nativebridge

import android.util.Log

/**
 * Direct JNI surface of `libslop_native.so`. Keep this file boring: it must map
 * one-to-one onto the C entry points in app/src/main/cpp/native_jni.cpp.
 *
 * All micro-op functions return 0 on success and a negative error code
 * otherwise; the digest is written to `outDigest[0]`.
 */
object SlopNativeBridge {

    private const val TAG = "SlopNativeBridge"
    const val LIBRARY_NAME = "slop_native"

    /** null when the shared library is unavailable (e.g. wrong ABI). */
    @Volatile
    private var loadFailure: Throwable? = null

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary(LIBRARY_NAME)
            isLoaded = true
        } catch (t: Throwable) {
            loadFailure = t
            Log.w(TAG, "native library ${LIBRARY_NAME} not loadable: ${t.message}")
        }
    }

    fun lastLoadError(): String? = loadFailure?.let { "${it::class.java.simpleName}: ${it.message}" }

    external fun nativeEngineVersion(): Int

    external fun nativeSelfTest(iterations: Int): Int

    external fun nativePeakRssBytes(): Long

    external fun nativeMatmulF32(a: FloatArray, b: FloatArray, m: Int, k: Int, n: Int, outDigest: DoubleArray): Int

    external fun nativeMatmulInt8(a: ByteArray, b: ByteArray, m: Int, k: Int, n: Int, outDigest: DoubleArray): Int

    external fun nativeConv2dF32(
        input: FloatArray,
        weights: FloatArray,
        bias: FloatArray?,
        c: Int,
        h: Int,
        w: Int,
        kc: Int,
        outDigest: DoubleArray,
    ): Int

    external fun nativeEltwiseF32(a: FloatArray, b: FloatArray, multiply: Boolean, outDigest: DoubleArray): Int
}
