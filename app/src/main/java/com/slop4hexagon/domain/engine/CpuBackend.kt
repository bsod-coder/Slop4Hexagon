package com.slop4hexagon.domain.engine

import android.content.Context
import com.slop4hexagon.domain.model.BackendCapabilities
import com.slop4hexagon.domain.model.BackendId
import com.slop4hexagon.domain.model.BackendStatus
import com.slop4hexagon.domain.model.ModelFormat

class CpuBackend : InferenceBackend, GenerativeBackend {
    override val id = BackendId.CPU
    override val capabilities = BackendCapabilities(
        backendId = BackendId.CPU,
        supportedFormats = setOf(ModelFormat.ONNX, ModelFormat.TFLITE),
        supportsImageToImage = true,
        supportsDenoisingStrength = false, // until real pipeline
        supportsQuantizedInt8 = true,
        maxSupportedDimension = 512,
        requiresConversion = false,
        notes = "CPU fallback always available. Supports ONNX/TFLite microbenchmark. Diffusion pipeline requires ONNX Runtime integration (not yet bundled)."
    )

    private var status: BackendStatus = BackendStatus.AVAILABLE
    private var verified = false

    override fun getStatus(): BackendStatus = status
    override fun isAvailable(context: Context?): Boolean = true

    override fun initialize(context: Context?): BackendStatus {
        // No native libs needed; always succeeds
        status = BackendStatus.INITIALIZED
        return status
    }

    override fun shutdown() {
        status = BackendStatus.AVAILABLE
        verified = false
    }

    override fun supportsMicroBenchmark(): Boolean = true

    /**
     * Honest generative implementation: currently returns failure with guidance,
     * instead of placeholder image. When ONNX Runtime is integrated, this will load .onnx.
     */
    override suspend fun generate(request: com.slop4hexagon.domain.model.InferenceRequest): com.slop4hexagon.domain.model.InferenceResult {
        // Validate denoising strength not honored
        if (request.denoisingStrength != null && !capabilities.supportsDenoisingStrength) {
            return com.slop4hexagon.domain.model.InferenceResult.Failure(
                reason = "Denoising strength not supported by CPU backend yet. Set to null or use a backend that supports it.",
                backendStatus = status,
                backendAttempted = id
            )
        }
        if (request.width > capabilities.maxSupportedDimension || request.height > capabilities.maxSupportedDimension) {
            return com.slop4hexagon.domain.model.InferenceResult.Failure(
                reason = "Requested ${request.width}x${request.height} exceeds CPU max ${capabilities.maxSupportedDimension}. Reduce dimensions.",
                backendStatus = status,
                backendAttempted = id
            )
        }
        // Real pipeline incomplete -> honest error, not placeholder
        return com.slop4hexagon.domain.model.InferenceResult.Failure(
            reason = "No executable diffusion pipeline on CPU yet. Import an ONNX model and wait for ONNX Runtime integration. Microbenchmark is available in Benchmark Lab.",
            backendStatus = status,
            backendAttempted = id
        )
    }
}
