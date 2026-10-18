package com.slop4hexagon.app.backend

import android.util.Log
import com.slop4hexagon.app.nativebridge.NativeCpuEngine
import com.slop4hexagon.app.nativebridge.NativeMicroOp
import com.slop4hexagon.app.nativebridge.NativeOutcome
import com.slop4hexagon.core.backend.BackendCapabilities
import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.backend.InferenceBackend
import com.slop4hexagon.core.backend.InferenceError
import com.slop4hexagon.core.backend.InferenceRequest
import com.slop4hexagon.core.backend.InferenceResult
import com.slop4hexagon.core.backend.InitOutcome
import com.slop4hexagon.core.backend.OpKind
import com.slop4hexagon.core.benchmark.MicroOpPlans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Real, shipped backend: fixed-shape tensor micro-ops executed by the portable
 * C++ engine through JNI on the CPU.
 *
 * Status honesty:
 *  * [BackendStatus.UNSUPPORTED] when the shared library is not loadable;
 *  * [BackendStatus.AVAILABLE] once loaded, before the self-test has run;
 *  * [BackendStatus.INITIALIZED] after a clean native self-test;
 *  * [BackendStatus.VERIFIED_EXECUTION] after a real op executed successfully
 *    and its digest was recomputed and matched;
 *  * [BackendStatus.FAILED] on any native/self-test failure.
 *
 * It never claims an image-generation pipeline capability.
 */
class CpuInferenceBackend(private val engine: NativeCpuEngine?) : InferenceBackend {

    override val id: BackendId = BackendId.CPU
    override val displayName: String = BackendId.CPU.displayName

    @Volatile
    private var status: BackendStatus = if (engine == null) BackendStatus.UNSUPPORTED else BackendStatus.AVAILABLE

    @Volatile
    private var detail: String = when {
        engine == null -> "native library " + com.slop4hexagon.app.nativebridge.SlopNativeBridge.LIBRARY_NAME +
            " is not loaded in this process"
        else -> "libslop_native loaded, engine v${engine.engineVersion}; self-test not run yet"
    }

    @Volatile
    private var lastError: String? = null

    private var verifiedOps: Int = 0
    private var totalOps: Int = 0
    private val initLock = Mutex()

    override fun capabilities(): BackendCapabilities = if (engine == null) {
        BackendCapabilities.NONE
    } else {
        BackendCapabilities(
            supportedOps = setOf(
                OpKind.MATMUL_F32,
                OpKind.MATMUL_INT8,
                OpKind.CONV2D_F32,
                OpKind.ELTWISE_ADD_F32,
                OpKind.ELTWISE_MUL_F32,
            ),
            supportsMicroBenchmarks = true,
            supportsImagePipeline = false,
            maxTensorElements = NATIVE_MAX_TENSOR_ELEMENTS,
            notes = listOf(
                "Element budget mirrors the native kernel limit (kMaxElements).",
                "No image-generation pipeline engine is implemented, so no " +
                    "text-to-image or image-to-image run can be executed here.",
            ),
        )
    }

    override fun status(): BackendStatus = status

    override fun statusDetail(): String = detail

    fun lastErrorDetail(): String? = lastError

    override suspend fun initialize(): InitOutcome {
        if (engine == null) {
            status = BackendStatus.UNSUPPORTED
            val message = "native library is not present for this ABI"
            lastError = message
            return InitOutcome.Failure(InferenceError.BackendUnavailable(message), message)
        }
        return initLock.withLock {
            if (status == BackendStatus.INITIALIZED || status == BackendStatus.VERIFIED_EXECUTION) {
                return@withLock InitOutcome.Success(detail)
            }
            val result = withContext(Dispatchers.Default) { engine.selfTest() }
            when {
                result.passed -> {
                    status = BackendStatus.INITIALIZED
                    detail = "libslop_native v${engine.engineVersion} initialized; " +
                        "self-test passed (${NativeCpuEngine.DEFAULT_SELF_TEST_ITERATIONS} iteration(s) of kernels vs reference)"
                    Log.i(TAG, detail)
                    InitOutcome.Success(detail)
                }
                else -> {
                    status = BackendStatus.FAILED
                    lastError = result.error ?: "self-test reported ${result.failedChecks} failed checks"
                    detail = "native self-test failed: ${lastError}"
                    Log.e(TAG, detail)
                    InitOutcome.Failure(InferenceError.ExecutionFailed(detail), detail)
                }
            }
        }
    }

    override suspend fun execute(request: InferenceRequest): InferenceResult {
        val engine = engine ?: return InferenceResult.Failure(
            InferenceError.BackendUnavailable("native library not loaded"),
        )
        return when (request) {
            is InferenceRequest.ImagePipelineRequest -> InferenceResult.Failure(
                InferenceError.NotSupportedYet(
                    "text-to-image / image-to-image needs a pipeline engine (UNet + VAE + text " +
                        "encoder). This build ships only the CPU micro-op engine.",
                ),
            )
            is InferenceRequest.MicroOpRequest -> executeMicroOp(engine, request)
        }
    }

    private suspend fun executeMicroOp(
        engine: NativeCpuEngine,
        request: InferenceRequest.MicroOpRequest,
    ): InferenceResult = withContext(Dispatchers.Default) {
        val plan = MicroOpPlans.plan(request.op, request.rows, request.inner, request.cols)
        val validationErrors = plan.validate(NATIVE_MAX_TENSOR_ELEMENTS)
        if (validationErrors.isNotEmpty()) {
            return@withContext InferenceResult.Failure(InferenceError.InvalidRequest(validationErrors.joinToString("; ")))
        }
        if (request.inputA.size < plan.inputAElements || request.inputB.size < plan.inputBElements) {
            return@withContext InferenceResult.Failure(
                InferenceError.InvalidRequest(
                    "input A has ${request.inputA.size} elements, plan needs ${plan.inputAElements}; " +
                        "input B has ${request.inputB.size}, needs ${plan.inputBElements}",
                ),
            )
        }
        val nativeOp = when (request.op) {
            OpKind.MATMUL_F32 -> NativeMicroOp.MatmulF32(
                request.inputA, request.inputB, request.rows, request.inner, request.cols,
            )
            OpKind.MATMUL_INT8 -> NativeMicroOp.MatmulInt8(
                a = quantizeToInt8(request.inputA),
                b = quantizeToInt8(request.inputB),
                rows = request.rows,
                inner = request.inner,
                cols = request.cols,
            )
            OpKind.CONV2D_F32 -> NativeMicroOp.Conv2dF32(
                input = request.inputA,
                weights = request.inputB,
                bias = null,
                channels = request.inner,
                height = request.cols,
                width = request.cols,
                filters = request.rows,
            )
            OpKind.ELTWISE_ADD_F32 -> NativeMicroOp.EltwiseF32(request.inputA, request.inputB, multiply = false)
            OpKind.ELTWISE_MUL_F32 -> NativeMicroOp.EltwiseF32(request.inputA, request.inputB, multiply = true)
        }

        val startedAt = System.nanoTime()
        val outcome = engine.microOp(nativeOp)
        val elapsed = System.nanoTime() - startedAt

        when (outcome) {
            is NativeOutcome.Error -> {
                status = BackendStatus.FAILED
                lastError = outcome.error.describe()
                InferenceResult.Failure(InferenceError.ExecutionFailed(outcome.error.describe()))
            }
            is NativeOutcome.Success -> {
                totalOps++
                // Second run with identical inputs must produce the identical
                // digest; that is what justifies claiming verified execution.
                val repeat = engine.microOp(nativeOp)
                if (repeat is NativeOutcome.Success && repeat.value == outcome.value) {
                    verifiedOps++
                    if (status != BackendStatus.INITIALIZED && status != BackendStatus.VERIFIED_EXECUTION) {
                        status = BackendStatus.INITIALIZED
                    }
                    status = BackendStatus.VERIFIED_EXECUTION
                    detail = "libslop_native v${engine.engineVersion}: $totalOps op(s) executed, " +
                        "$verifiedOps verified by digest match"
                } else {
                    status = BackendStatus.FAILED
                    lastError = "digest mismatch between two runs of the same op"
                    return@withContext InferenceResult.Failure(
                        InferenceError.ExecutionFailed("non-deterministic result from ${request.op.displayName}"),
                    )
                }
                InferenceResult.MicroOpSuccess(
                    op = request.op,
                    outputShape = plan.outputShape,
                    checksum = outcome.value,
                    latencyNanos = elapsed,
                )
            }
        }
    }

    override fun close() {
        // The native library has no per-instance state to release; nothing to do.
    }

    companion object {
        private const val TAG = "CpuBackend"

        /** Mirrors `slop::kMaxElements` in the C++ engine. */
        const val NATIVE_MAX_TENSOR_ELEMENTS: Long = 1L shl 24

        /** Saturating round-to-nearest quantization used for the INT8 path. */
        fun quantizeToInt8(values: FloatArray): ByteArray =
            ByteArray(values.size) { i ->
                val v = values[i]
                val clamped = when {
                    v.isNaN() -> 0f
                    v > 1f -> 1f
                    v < -1f -> -1f
                    else -> v
                }
                Math.round(clamped * 127f).toInt().coerceIn(-127, 127).toByte()
            }
    }
}
