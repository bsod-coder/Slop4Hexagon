package com.slop4hexagon.core.backend

/**
 * Request types accepted by an [InferenceBackend].
 *
 * [MicroOpRequest] is used by the backend microbenchmark mode and executes a
 * single fixed-shape tensor operation. [ImagePipelineRequest] describes a full
 * generative pipeline invocation; no pipeline engine ships in v1, so backends
 * answer with [InferenceResult.Failure] / [InferenceError.NotSupportedYet]
 * instead of fabricating output.
 */
sealed class InferenceRequest {

    data class MicroOpRequest(
        val op: OpKind,
        /** Rows of A / output. */
        val rows: Int,
        /** Shared inner dimension (K of matmul, channels of conv). */
        val inner: Int,
        /** Columns of B / output. */
        val cols: Int,
        val inputA: FloatArray,
        val inputB: FloatArray,
    ) : InferenceRequest() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MicroOpRequest) return false
            return op == other.op && rows == other.rows && inner == other.inner && cols == other.cols &&
                inputA.contentEquals(other.inputA) && inputB.contentEquals(other.inputB)
        }

        override fun hashCode(): Int {
            var result = op.hashCode()
            result = 31 * result + rows
            result = 31 * result + inner
            result = 31 * result + cols
            result = 31 * result + inputA.contentHashCode()
            result = 31 * result + inputB.contentHashCode()
            return result
        }
    }

    data class ImagePipelineRequest(
        val modelId: String,
        val mode: ImageGenMode,
        val prompt: String,
        val negativePrompt: String?,
        val width: Int,
        val height: Int,
        val steps: Int,
        val seed: Long,
        /** Image-to-image denoising strength in [0, 1]; null when not applicable. */
        val denoisingStrength: Double?,
        val sourceImage: SourceImage?,
    ) : InferenceRequest()

    enum class ImageGenMode { TEXT_TO_IMAGE, IMAGE_TO_IMAGE }
}

data class SourceImage(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourceImage) return false
        return width == other.width && height == other.height && rgba.contentEquals(other.rgba)
    }

    override fun hashCode(): Int {
        var result = rgba.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

sealed class InferenceResult {

    /**
     * A micro-op execution completed.
     *
     * [checksum] is a cheap deterministic reduction of the output tensor used to
     * (a) prove the op actually ran and (b) validate results across backends.
     */
    data class MicroOpSuccess(
        val op: OpKind,
        val outputShape: List<Int>,
        val checksum: Double,
        val latencyNanos: Long,
    ) : InferenceResult()

    data class ImageSuccess(
        val pngBytes: ByteArray,
        val width: Int,
        val height: Int,
        val latencyNanos: Long,
    ) : InferenceResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageSuccess) return false
            return width == other.width && height == other.height &&
                latencyNanos == other.latencyNanos && pngBytes.contentEquals(other.pngBytes)
        }

        override fun hashCode(): Int {
            var result = pngBytes.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + latencyNanos.hashCode()
            return result
        }
    }

    data class Failure(val error: InferenceError) : InferenceResult()
}

sealed class InitOutcome {
    data class Success(val detail: String) : InitOutcome()
    data class Failure(val error: InferenceError, val detail: String) : InitOutcome()
}

/**
 * A compute backend able to execute inference requests.
 *
 * Implementations must keep [status] truthful at all times: in particular only
 * set [BackendStatus.VERIFIED_EXECUTION] after a request has genuinely executed
 * and produced a validated result.
 */
interface InferenceBackend : AutoCloseable {
    val id: BackendId
    val displayName: String

    fun capabilities(): BackendCapabilities

    fun status(): BackendStatus

    /** Short truthful description of the current status (reasons, versions...). */
    fun statusDetail(): String

    /** Idempotent. Must not claim [BackendStatus.INITIALIZED] on failure. */
    suspend fun initialize(): InitOutcome

    suspend fun execute(request: InferenceRequest): InferenceResult

    override fun close()
}
