package com.slop4hexagon.app.nativebridge

import com.slop4hexagon.core.backend.OpKind

/**
 * Kotlin-side wrapper around [SlopNativeBridge] with explicit result types.
 *
 * Kept separate from the raw bridge so that:
 *  * argument shape validation happens before entering native code,
 *  * the negative-return-code protocol is translated once,
 *  * callers get [NativeOutcome] instead of magic numbers.
 */
class NativeCpuEngine private constructor() {

    /** False when the shared library could not be loaded in this process. */
    val available: Boolean get() = SlopNativeBridge.isLoaded

    val libraryLoadError: String? get() = SlopNativeBridge.lastLoadError()

    /** 0 until the library is loaded; then the native engine version. */
    val engineVersion: Int get() = if (available) SlopNativeBridge.nativeEngineVersion() else 0

    /**
     * Runs the native self-test: every kernel against an independently written
     * naive reference, plus determinism checks. A zero return is the only
     * evidence this app uses to promote the CPU backend to
     * [VERIFIED_EXECUTION][com.slop4hexagon.core.backend.BackendStatus.VERIFIED_EXECUTION].
     */
    fun selfTest(iterations: Int = DEFAULT_SELF_TEST_ITERATIONS): NativeSelfTestResult {
        if (!available) return NativeSelfTestResult(-1, "native library not loaded")
        val failures = SlopNativeBridge.nativeSelfTest(iterations.coerceIn(1, 64))
        return NativeSelfTestResult(failures, if (failures == 0) null else "$failures native self-test check(s) failed")
    }

    fun microOp(request: NativeMicroOp): NativeOutcome<Double> {
        if (!available) return NativeOutcome.Error(NativeError.LibraryMissing)
        val digest = DoubleArray(1)
        val code = when (request) {
            is NativeMicroOp.MatmulF32 -> {
                val err = checkMatrix(request.rows, request.inner, request.cols, request.a.size, request.b.size)
                if (err != null) return NativeOutcome.Error(err)
                SlopNativeBridge.nativeMatmulF32(request.a, request.b, request.rows, request.inner, request.cols, digest)
            }
            is NativeMicroOp.MatmulInt8 -> {
                val err = checkMatrix(request.rows, request.inner, request.cols, request.a.size, request.b.size)
                if (err != null) return NativeOutcome.Error(err)
                SlopNativeBridge.nativeMatmulInt8(request.a, request.b, request.rows, request.inner, request.cols, digest)
            }
            is NativeMicroOp.Conv2dF32 -> {
                val needIn = request.channels * request.height * request.width
                val needW = request.filters * request.channels * 9
                if (request.channels <= 0 || request.height <= 0 || request.width <= 0 || request.filters <= 0) {
                    return NativeOutcome.Error(NativeError.BadDimensions)
                }
                if (request.input.size < needIn || request.weights.size < needW ||
                    (request.bias != null && request.bias.size < request.filters)
                ) {
                    return NativeOutcome.Error(NativeError.BufferTooSmall)
                }
                SlopNativeBridge.nativeConv2dF32(
                    request.input, request.weights, request.bias,
                    request.channels, request.height, request.width, request.filters, digest,
                )
            }
            is NativeMicroOp.EltwiseF32 -> {
                if (request.a.isEmpty() || request.b.isEmpty()) return NativeOutcome.Error(NativeError.BufferTooSmall)
                SlopNativeBridge.nativeEltwiseF32(request.a, request.b, request.multiply, digest)
            }
        }
        return if (code == 0) NativeOutcome.Success(digest[0]) else NativeOutcome.Error(NativeError.fromCode(code))
    }

    fun peakRssBytes(): Long = if (available) SlopNativeBridge.nativePeakRssBytes() else -1L

    private fun checkMatrix(rows: Int, inner: Int, cols: Int, aSize: Int, bSize: Int): NativeError? {
        if (rows <= 0 || inner <= 0 || cols <= 0) return NativeError.BadDimensions
        val needA = rows.toLong() * inner.toLong()
        val needB = inner.toLong() * cols.toLong()
        if (needA > aSize || needB > bSize) return NativeError.BufferTooSmall
        return null
    }

    companion object {
        const val DEFAULT_SELF_TEST_ITERATIONS = 2

        /** Loads the library; returns null only when the bridge is unusable. */
        fun load(): NativeCpuEngine? = if (SlopNativeBridge.isLoaded) NativeCpuEngine() else null

        fun opKindSupported(op: OpKind): Boolean = when (op) {
            OpKind.MATMUL_F32, OpKind.MATMUL_INT8, OpKind.CONV2D_F32, OpKind.ELTWISE_ADD_F32, OpKind.ELTWISE_MUL_F32 -> true
        }
    }
}

data class NativeSelfTestResult(val failedChecks: Int, val error: String?) {
    /** -1 means the native library was not loaded at all. */
    val passed: Boolean get() = failedChecks == 0
    val available: Boolean get() = failedChecks >= 0
}

sealed class NativeError {
    data object LibraryMissing : NativeError()
    data object NullPointer : NativeError()
    data object BadDimensions : NativeError()
    data object BufferTooSmall : NativeError()
    data object Overflow : NativeError()
    data object Unsupported : NativeError()
    data class Unknown(val code: Int) : NativeError()

    fun describe(): String = when (this) {
        LibraryMissing -> "native library ${SlopNativeBridge.LIBRARY_NAME} is not loaded in this process"
        NullPointer -> "native call received a null buffer"
        BadDimensions -> "native call received invalid dimensions"
        BufferTooSmall -> "input array shorter than the declared tensor shape"
        Overflow -> "tensor exceeds the native element budget"
        Unsupported -> "operation not implemented natively"
        is Unknown -> "native error code $code"
    }

    companion object {
        fun fromCode(code: Int): NativeError = when (code) {
            -1 -> NullPointer
            -2 -> BadDimensions
            -3 -> BufferTooSmall
            -4 -> Overflow
            -5 -> Unsupported
            else -> Unknown(code)
        }
    }
}

sealed class NativeOutcome<out T> {
    data class Success<T>(val value: T) : NativeOutcome<T>()
    data class Error(val error: NativeError) : NativeOutcome<Nothing>()
}

sealed class NativeMicroOp {
    data class MatmulF32(val a: FloatArray, val b: FloatArray, val rows: Int, val inner: Int, val cols: Int) : NativeMicroOp()
    data class MatmulInt8(val a: ByteArray, val b: ByteArray, val rows: Int, val inner: Int, val cols: Int) : NativeMicroOp()
    data class Conv2dF32(
        val input: FloatArray,
        val weights: FloatArray,
        val bias: FloatArray?,
        val channels: Int,
        val height: Int,
        val width: Int,
        val filters: Int,
    ) : NativeMicroOp()

    data class EltwiseF32(val a: FloatArray, val b: FloatArray, val multiply: Boolean) : NativeMicroOp()
}
