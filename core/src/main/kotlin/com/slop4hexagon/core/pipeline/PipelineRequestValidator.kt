package com.slop4hexagon.core.pipeline

import com.slop4hexagon.core.backend.BackendCapabilities
import com.slop4hexagon.core.backend.InferenceRequest
import com.slop4hexagon.core.model.ModelFormat
import com.slop4hexagon.core.model.ModelInspection
import com.slop4hexagon.core.model.PipelineComponent

/**
 * User-facing generation settings, kept independent of any engine so the UI can
 * render them and this validator can tell the truth about which ones the
 * selected backend can actually honour.
 */
data class PipelineSettings(
    val mode: InferenceRequest.ImageGenMode = InferenceRequest.ImageGenMode.TEXT_TO_IMAGE,
    val prompt: String = "",
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val seed: Long = 0,
    val denoisingStrength: Double = 0.6,
    val sourceImageSelected: Boolean = false,
    val modelId: String? = null,
)

/** Which settings the current backend/model combination can honour. */
enum class SettingSupport {
    /** The engine implements this and will apply it. */
    HONOURED,

    /** No engine in this build implements this; the UI must mark it so. */
    NOT_SUPPORTED,

    /** Would work, but the selected model is missing a component for it. */
    MISSING_MODEL_PART,
}

data class ValidatedRequest(
    val fieldSupport: Map<String, SettingSupport>,
    /** Hard errors that make the request invalid regardless of backend. */
    val errors: List<String>,
    /** Why nothing can run right now, e.g. "no pipeline engine in this build". */
    val blockers: List<String>,
) {
    val executable: Boolean get() = errors.isEmpty() && blockers.isEmpty()
}

/**
 * Pure validator that decides which generation settings can be honoured, so the
 * UI never shows a slider that does nothing and never reports a run that cannot
 * happen.
 */
object PipelineRequestValidator {

    const val FIELD_PROMPT = "prompt"
    const val FIELD_NEGATIVE_PROMPT = "negativePrompt"
    const val FIELD_MODE = "mode"
    const val FIELD_DENOISING = "denoisingStrength"
    const val FIELD_SOURCE_IMAGE = "sourceImage"
    const val FIELD_WIDTH = "width"
    const val FIELD_HEIGHT = "height"
    const val FIELD_STEPS = "steps"
    const val FIELD_SEED = "seed"

    /** Latent/VAE downsampling factor used by Stable-Diffusion-style pipelines. */
    const val VAE_DOWNSCALE = 8

    fun validate(
        settings: PipelineSettings,
        capabilities: BackendCapabilities?,
        inspection: ModelInspection?,
    ): ValidatedRequest {
        val errors = mutableListOf<String>()
        val blockers = mutableListOf<String>()
        val support = LinkedHashMap<String, SettingSupport>()

        if (settings.prompt.isBlank()) errors += "A positive prompt is required"
        if (settings.width <= 0 || settings.height <= 0) errors += "Width and height must be positive"
        if (settings.width % VAE_DOWNSCALE != 0 || settings.height % VAE_DOWNSCALE != 0) {
            errors += "Width and height must be multiples of $VAE_DOWNSCALE for latent-space pipelines"
        }
        if (settings.steps <= 0) errors += "Step count must be positive"
        if (settings.denoisingStrength < 0.0 || settings.denoisingStrength > 1.0) {
            errors += "Denoising strength must be within 0..1"
        }
        if (settings.mode == InferenceRequest.ImageGenMode.IMAGE_TO_IMAGE && !settings.sourceImageSelected) {
            errors += "Image-to-image requires a source image"
        }

        val pipelineCapable = capabilities?.supportsImagePipeline == true
        if (capabilities == null) {
            blockers += "No backend is selected, so nothing can be executed"
        } else if (!pipelineCapable) {
            blockers += "The selected backend reports no image-generation pipeline capability. " +
                "This build ships a CPU micro-op engine only; it cannot run a diffusion pipeline."
        }

        if (settings.modelId == null) {
            blockers += "No model selected. Import a compatible model first."
        } else if (inspection == null) {
            blockers += "The selected model has no inspection record; re-import it to verify its format"
        } else {
            when (inspection.format) {
                ModelFormat.SAFETENSORS, ModelFormat.PICKLE_CHECKPOINT -> {
                    blockers +=
                        "${inspection.format.displayName} is a weights bundle. Running it needs a " +
                            "diffusion pipeline engine (sampler, UNet, VAE, text encoder), which this " +
                            "build does not ship — importing the file is not the same as executing it."
                    if (!inspection.isSinglePipelineFile) {
                        blockers += "This file does not contain all of UNet + VAE + text encoder, " +
                            "so even with an engine it would need additional component files"
                    }
                }
                ModelFormat.ONNX, ModelFormat.TFLITE ->
                    blockers += "${inspection.format.displayName} requires a runtime engine that is not bundled in this build"
                ModelFormat.QNN_CONTEXT_BINARY, ModelFormat.QNN_DLC ->
                    if (pipelineCapable) {
                        // keep blocker-free path available for a future HTP engine
                    } else {
                        blockers += "${inspection.format.displayName} needs the QNN runtime and a graph " +
                            "wiring that this build does not implement"
                    }
                ModelFormat.GGUF ->
                    blockers += "GGUF containers are not used by any engine in this build"
                ModelFormat.UNKNOWN ->
                    blockers += "Unrecognised model format; refusing to run it"
            }
        }

        // Per-field support. Note the order: capability first, then model parts.
        support[FIELD_PROMPT] = if (pipelineCapable) {
            if (inspection != null && !inspection.hasComponent(PipelineComponent.TEXT_ENCODER)) {
                SettingSupport.MISSING_MODEL_PART
            } else {
                SettingSupport.HONOURED
            }
        } else {
            SettingSupport.NOT_SUPPORTED
        }
        support[FIELD_NEGATIVE_PROMPT] = if (pipelineCapable) {
            if (inspection != null && settings.negativePrompt.isNotBlank() &&
                !inspection.hasComponent(PipelineComponent.TEXT_ENCODER)
            ) {
                SettingSupport.MISSING_MODEL_PART
            } else {
                SettingSupport.HONOURED
            }
        } else {
            SettingSupport.NOT_SUPPORTED
        }
        support[FIELD_MODE] = if (pipelineCapable) SettingSupport.HONOURED else SettingSupport.NOT_SUPPORTED
        support[FIELD_DENOISING] = if (pipelineCapable) SettingSupport.HONOURED else SettingSupport.NOT_SUPPORTED
        support[FIELD_SOURCE_IMAGE] = if (pipelineCapable) {
            if (inspection != null && !inspection.hasComponent(PipelineComponent.VAE)) {
                SettingSupport.MISSING_MODEL_PART
            } else {
                SettingSupport.HONOURED
            }
        } else {
            SettingSupport.NOT_SUPPORTED
        }
        support[FIELD_WIDTH] = if (pipelineCapable) SettingSupport.HONOURED else SettingSupport.NOT_SUPPORTED
        support[FIELD_HEIGHT] = if (pipelineCapable) SettingSupport.HONOURED else SettingSupport.NOT_SUPPORTED
        support[FIELD_STEPS] = if (pipelineCapable) SettingSupport.HONOURED else SettingSupport.NOT_SUPPORTED
        support[FIELD_SEED] =
            if (pipelineCapable) SettingSupport.HONOURED else SettingSupport.NOT_SUPPORTED

        return ValidatedRequest(support, errors, blockers)
    }

    private fun ModelInspection.hasComponent(component: PipelineComponent): Boolean =
        components.any { it.component == component }
}
