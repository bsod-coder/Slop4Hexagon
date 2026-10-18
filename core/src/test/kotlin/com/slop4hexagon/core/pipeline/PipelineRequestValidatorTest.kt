package com.slop4hexagon.core.pipeline

import com.slop4hexagon.core.backend.BackendCapabilities
import com.slop4hexagon.core.backend.OpKind
import com.slop4hexagon.core.model.CompatibilityStatus
import com.slop4hexagon.core.model.ModelFormat
import com.slop4hexagon.core.model.ModelInspection
import com.slop4hexagon.core.model.PipelineComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineRequestValidatorTest {

    private val noPipelineEngine = BackendCapabilities(
        supportedOps = setOf(OpKind.MATMUL_F32),
        supportsMicroBenchmarks = true,
        supportsImagePipeline = false,
        maxTensorElements = 1L shl 24,
    )

    private val hypotheticalPipelineEngine = noPipelineEngine.copy(supportsImagePipeline = true)

    private fun inspection(
        format: ModelFormat,
        components: List<PipelineComponent>,
    ): ModelInspection = ModelInspection(
        format = format,
        magicConfirmed = true,
        weightBytes = 1_000,
        estimatedMemoryBytes = 1_150,
        tensorCount = components.size,
        tensors = emptyList(),
        components = components.map { PipelineComponentHint(it) },
        formatWarnings = emptyList(),
        inspectionWarnings = emptyList(),
        compatibility = emptyList(),
        isSinglePipelineFile = components.containsAll(
            listOf(PipelineComponent.UNET, PipelineComponent.VAE, PipelineComponent.TEXT_ENCODER),
        ),
    )

    private fun PipelineComponentHint(component: PipelineComponent) =
        com.slop4hexagon.core.model.ComponentHint(component, 1, 100)

    @Test
    fun nothingIsHonouredWhenBackendHasNoPipeline() {
        val result = PipelineRequestValidator.validate(
            settings = PipelineSettings(prompt = "a cat", modelId = "m1"),
            capabilities = noPipelineEngine,
            inspection = inspection(ModelFormat.SAFETENSORS, listOf(PipelineComponent.UNET)),
        )
        assertFalse(result.executable)
        assertTrue(result.blockers.any { it.contains("does not ship") })
        assertEquals(
            SettingSupport.NOT_SUPPORTED,
            result.fieldSupport[PipelineRequestValidator.FIELD_NEGATIVE_PROMPT],
        )
        assertEquals(SettingSupport.NOT_SUPPORTED, result.fieldSupport[PipelineRequestValidator.FIELD_STEPS])
        assertEquals(SettingSupport.NOT_SUPPORTED, result.fieldSupport[PipelineRequestValidator.FIELD_WIDTH])
    }

    @Test
    fun weightsBundleIsNeverTreatedAsRunnable() {
        val result = PipelineRequestValidator.validate(
            settings = PipelineSettings(prompt = "a cat", modelId = "m1"),
            capabilities = hypotheticalPipelineEngine,
            inspection = inspection(ModelFormat.SAFETENSORS, listOf(PipelineComponent.UNET)),
        )
        assertFalse(result.executable)
        assertTrue(result.blockers.any { it.contains("weights bundle") })
        assertTrue(result.blockers.any { it.contains("UNet + VAE + text encoder") })
    }

    @Test
    fun invalidGeometryIsRejectedIndependentlyOfBackend() {
        val result = PipelineRequestValidator.validate(
            settings = PipelineSettings(prompt = "x", width = 500, height = 500, steps = 0, modelId = "m1"),
            capabilities = noPipelineEngine,
            inspection = null,
        )
        assertTrue(result.errors.any { it.contains("multiples of 8") })
        assertTrue(result.errors.any { it.contains("Step count") })
    }

    @Test
    fun imageToImageWithoutSourceImageIsInvalid() {
        val result = PipelineRequestValidator.validate(
            settings = PipelineSettings(
                prompt = "x",
                mode = com.slop4hexagon.core.backend.InferenceRequest.ImageGenMode.IMAGE_TO_IMAGE,
                sourceImageSelected = false,
                modelId = "m1",
            ),
            capabilities = hypotheticalPipelineEngine,
            inspection = null,
        )
        assertTrue(result.errors.any { it.contains("source image") })
    }

    @Test
    fun missingModelSelectionIsABlocker() {
        val result = PipelineRequestValidator.validate(
            settings = PipelineSettings(prompt = "x", modelId = null),
            capabilities = hypotheticalPipelineEngine,
            inspection = null,
        )
        assertTrue(result.blockers.any { it.contains("No model selected") })
    }

    @Test
    fun promptHonouredOnlyWhenTextEncoderPresent() {
        val withoutTextEncoder = PipelineRequestValidator.validate(
            settings = PipelineSettings(prompt = "x", modelId = "m1"),
            capabilities = hypotheticalPipelineEngine,
            inspection = inspection(ModelFormat.QNN_CONTEXT_BINARY, listOf(PipelineComponent.UNET)),
        )
        assertEquals(
            SettingSupport.MISSING_MODEL_PART,
            withoutTextEncoder.fieldSupport[PipelineRequestValidator.FIELD_PROMPT],
        )

        val withTextEncoder = PipelineRequestValidator.validate(
            settings = PipelineSettings(prompt = "x", modelId = "m1"),
            capabilities = hypotheticalPipelineEngine,
            inspection = inspection(
                ModelFormat.QNN_CONTEXT_BINARY,
                listOf(PipelineComponent.UNET, PipelineComponent.VAE, PipelineComponent.TEXT_ENCODER),
            ),
        )
        assertEquals(
            SettingSupport.HONOURED,
            withTextEncoder.fieldSupport[PipelineRequestValidator.FIELD_PROMPT],
        )
        assertEquals(
            SettingSupport.HONOURED,
            withTextEncoder.fieldSupport[PipelineRequestValidator.FIELD_SOURCE_IMAGE],
        )
    }

    @Test
    fun unknownFormatIsRefused() {
        val result = PipelineRequestValidator.validate(
            settings = PipelineSettings(prompt = "x", modelId = "m1"),
            capabilities = hypotheticalPipelineEngine,
            inspection = inspection(ModelFormat.UNKNOWN, emptyList()),
        )
        assertTrue(result.blockers.any { it.contains("Unrecognised model format") })
    }

    @Test
    fun compatibilityEntriesAreAttachedPerBackend() {
        val inspection = inspection(ModelFormat.SAFETENSORS, listOf(PipelineComponent.UNET))
        val matrix = com.slop4hexagon.core.model.CompatibilityMatrix.forFormat(ModelFormat.SAFETENSORS, true)
        assertFalse(matrix.isEmpty())
        assertEquals(CompatibilityStatus.REQUIRES_ENGINE, matrix.first { it.backend == com.slop4hexagon.core.backend.BackendId.CPU }.status)
        assertEquals(1_150L, inspection.estimatedMemoryBytes)
    }
}
