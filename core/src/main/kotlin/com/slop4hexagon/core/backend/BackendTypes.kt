package com.slop4hexagon.core.backend

/**
 * Identifiers for compute backends. The set is fixed for v1; extensions should
 * be added only when an actual adapter implementation exists behind them.
 */
enum class BackendId(val displayName: String) {
    /** Portable C++ micro-op engine running on the CPU (JNI). Real, shipped. */
    CPU("CPU reference engine"),

    /** Adreno GPU compute backend. Not implemented in v1. */
    GPU("Adreno GPU"),

    /** Qualcomm Hexagon HTP through the QNN / QAIRT runtime. */
    HTP_QNN("Qualcomm Hexagon HTP (QNN)"),
}

/** User preference; AUTO prefers the most capable usable accelerator. */
enum class BackendPreference {
    AUTO, CPU, GPU, HTP;

    /** The backend this preference names directly, or null for AUTO. */
    fun directTarget(): BackendId? = when (this) {
        AUTO -> null
        CPU -> BackendId.CPU
        GPU -> BackendId.GPU
        HTP -> BackendId.HTP_QNN
    }
}

/**
 * Honest lifecycle states for a backend. A backend must never report
 * [VERIFIED_EXECUTION] unless at least one real inference has actually been
 * executed and its result validated on this backend in this process.
 */
enum class BackendStatus {
    /** This build or device cannot use this backend at all. */
    UNSUPPORTED,

    /** Supported in principle, but required runtime/SDK artifacts are missing. */
    UNAVAILABLE,

    /** Probed successfully or capability reported; not yet initialized. */
    AVAILABLE,

    /** initialize() completed successfully. */
    INITIALIZED,

    /** At least one inference executed successfully and was validated. */
    VERIFIED_EXECUTION,

    /** Initialization or execution failed. */
    FAILED,

    /** This backend was chosen only because a preferred backend was unusable. */
    FALLBACK_OCCURRED,
    ;

    fun isUsable(): Boolean = this == AVAILABLE || this == INITIALIZED || this == VERIFIED_EXECUTION
}

/** Fixed-shape tensor operations the microbenchmark layer can execute. */
enum class OpKind(val displayName: String) {
    MATMUL_F32("Matrix multiply (float32)"),
    MATMUL_INT8("Matrix multiply (int8, symmetric)"),
    CONV2D_F32("Convolution 3x3 (float32, NCHW)"),
    ELTWISE_ADD_F32("Element-wise add (float32)"),
    ELTWISE_MUL_F32("Element-wise multiply (float32)"),
}

data class BackendCapabilities(
    val supportedOps: Set<OpKind>,
    val supportsMicroBenchmarks: Boolean,
    /** End-to-end generative image pipeline (text-to-image / image-to-image). */
    val supportsImagePipeline: Boolean,
    /** Upper bound on tensor elements accepted by one request, 0 if unknown. */
    val maxTensorElements: Long,
    val notes: List<String> = emptyList(),
) {
    companion object {
        val NONE = BackendCapabilities(
            supportedOps = emptySet(),
            supportsMicroBenchmarks = false,
            supportsImagePipeline = false,
            maxTensorElements = 0,
        )
    }
}

/**
 * One row of the backend status table shown in Settings/Diagnostics.
 *
 * [detail] must be a truthful, human-readable explanation; [fallback] marks
 * that this backend currently holds the selection only via fallback logic.
 */
data class BackendStatusInfo(
    val id: BackendId,
    val displayName: String,
    val status: BackendStatus,
    val detail: String,
    val capabilities: BackendCapabilities?,
    val fallback: Boolean = false,
    val lastError: String? = null,
)

/** Explicit, structured failure reasons used across engine and UI layers. */
sealed class InferenceError {
    data class BackendUnavailable(val detail: String) : InferenceError()
    data class InvalidRequest(val detail: String) : InferenceError()
    data class UnsupportedOp(val op: OpKind) : InferenceError()
    data class OutOfResources(val detail: String) : InferenceError()
    data class ExecutionFailed(val detail: String) : InferenceError()
    data class NotSupportedYet(val feature: String) : InferenceError()
    object Cancelled : InferenceError()

    fun describe(): String = when (this) {
        is BackendUnavailable -> "Backend unavailable: $detail"
        is InvalidRequest -> "Invalid request: $detail"
        is UnsupportedOp -> "Unsupported operation: ${op.displayName}"
        is OutOfResources -> "Out of resources: $detail"
        is ExecutionFailed -> "Execution failed: $detail"
        is NotSupportedYet -> "Not supported in this build: $feature"
        Cancelled -> "Cancelled"
    }
}
