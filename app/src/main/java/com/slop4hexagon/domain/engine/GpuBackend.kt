package com.slop4hexagon.domain.engine

import android.content.Context
import android.os.Build
import com.slop4hexagon.domain.model.BackendCapabilities
import com.slop4hexagon.domain.model.BackendId
import com.slop4hexagon.domain.model.BackendStatus
import com.slop4hexagon.domain.model.ModelFormat

class GpuBackend : InferenceBackend {
    override val id = BackendId.GPU
    override val capabilities = BackendCapabilities(
        backendId = BackendId.GPU,
        supportedFormats = setOf(ModelFormat.ONNX), // via GPU delegate when available
        supportsImageToImage = true,
        supportsDenoisingStrength = false,
        supportsQuantizedInt8 = false,
        maxSupportedDimension = 768,
        requiresConversion = false,
        notes = "Adreno 642L GPU via Vulkan/OpenCL. Requires GPU delegate (not bundled in v0.1). Falls back to CPU."
    )

    private var status: BackendStatus = BackendStatus.UNSUPPORTED

    override fun getStatus(): BackendStatus = status

    override fun isAvailable(context: Context?): Boolean {
        // Heuristic: check for GPU info via Build
        // Real check would try to load GPU delegate
        status = if (Build.HARDWARE.contains("qcom", ignoreCase = true) || Build.SOC_MODEL?.contains("778", ignoreCase = true) == true) {
            BackendStatus.AVAILABLE
        } else {
            BackendStatus.UNSUPPORTED
        }
        // For 778G+ we know Adreno exists, but delegate missing => still mark AVAILABLE for probing
        // For now, we treat as AVAILABLE to allow init attempt, but init will report fallback
        return status == BackendStatus.AVAILABLE
    }

    override fun initialize(context: Context?): BackendStatus {
        // Try to init GPU delegate; since not bundled, report fallback
        status = BackendStatus.FALLBACK_OCCURRED
        return status
    }

    override fun shutdown() { status = BackendStatus.UNSUPPORTED }
}
