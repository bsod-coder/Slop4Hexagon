package com.slop4hexagon.domain.model

enum class GenerationMode { TEXT_TO_IMAGE, IMAGE_TO_IMAGE }

data class InferenceRequest(
    val modelId: String,
    val prompt: String,
    val negativePrompt: String? = null,
    val mode: GenerationMode = GenerationMode.TEXT_TO_IMAGE,
    val sourceImageUri: String? = null, // SAF uri string
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val seed: Long? = null,
    val denoisingStrength: Float? = null, // 0..1, only if backend supports
    val preferredBackend: BackendId = BackendId.AUTO
)

sealed class InferenceResult {
    data class Success(
        val outputUri: String,
        val backendUsed: BackendId,
        val elapsedMillis: Long,
        val width: Int,
        val height: Int,
        val seedUsed: Long
    ) : InferenceResult()

    data class Failure(
        val reason: String,
        val backendStatus: BackendStatus,
        val backendAttempted: BackendId? = null,
        val isFallback: Boolean = false
    ) : InferenceResult()
}

enum class BackendId { CPU, GPU, HTP, AUTO }
enum class BackendStatus {
    AVAILABLE,
    INITIALIZED,
    VERIFIED_EXECUTION,
    UNSUPPORTED,
    FAILED,
    FALLBACK_OCCURRED
}

data class BackendCapabilities(
    val backendId: BackendId,
    val supportedFormats: Set<ModelFormat>,
    val supportsImageToImage: Boolean,
    val supportsDenoisingStrength: Boolean,
    val supportsQuantizedInt8: Boolean,
    val maxSupportedDimension: Int,
    val requiresConversion: Boolean = false,
    val notes: String
)
