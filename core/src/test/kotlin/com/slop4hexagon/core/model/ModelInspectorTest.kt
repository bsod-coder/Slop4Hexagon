package com.slop4hexagon.core.model

import com.slop4hexagon.core.backend.BackendId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelInspectorTest {

    private fun buildHeader(json: String): ByteArray {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val out = ByteArray(8 + bytes.size)
        var len = bytes.size.toLong()
        for (i in 0 until 8) {
            out[i] = (len and 0xFF).toByte()
            len = len shr 8
        }
        System.arraycopy(bytes, 0, out, 8, bytes.size)
        return out
    }

    private val sdLikeJson = """
        {"model.diffusion_model.input_blocks.0.weight":{"dtype":"F16","shape":[320,4,3,3],"data_offsets":[0,23040]},
         "first_stage_model.decoder.conv_in.weight":{"dtype":"F16","shape":[512,4,3,3],"data_offsets":[23040,60192]},
         "cond_stage_model.transformer.text_model.embeddings.token_embedding.weight":{"dtype":"F32","shape":[49408,768],"data_offsets":[60192,157733280]},
         "__metadata__":{"modelspec.architecture":"stable-diffusion-v1"}}
    """.trimIndent()

    @Test
    fun parsesSafetensorsHeaderAndSumsWeights() {
        val header = buildHeader(sdLikeJson)
        val parsed = ModelInspector.parseSafetensorsHeader(header)
        assertEquals(3, parsed.tensors.size)
        val expectedTotal = 23040L + (60192L - 23040L) + (157733280L - 60192L)
        assertEquals(expectedTotal, parsed.totalDataBytes)
    }

    @Test
    fun detectsStableDiffusionComponents() {
        val header = buildHeader(sdLikeJson)
        val inspection = ModelInspector.inspect("sd15.safetensors", 160_000_000, header)
        val byComponent = inspection.components.associateBy { it.component }
        assertTrue(byComponent.containsKey(PipelineComponent.UNET))
        assertTrue(byComponent.containsKey(PipelineComponent.VAE))
        assertTrue(byComponent.containsKey(PipelineComponent.TEXT_ENCODER))
        assertTrue(inspection.isSinglePipelineFile)
        assertTrue(inspection.weightBytes > 0)
        assertTrue((inspection.estimatedMemoryBytes ?: 0) >= inspection.weightBytes)
    }

    @Test
    fun weightsBundlesAreNeverReportedExecutable() {
        val header = buildHeader(sdLikeJson)
        val inspection = ModelInspector.inspect("sd15.safetensors", 160_000_000, header)
        for (compat in inspection.compatibility) {
            assertTrue(
                "Weights bundle must not be LIKELY_SUPPORTED on ${compat.backend}",
                compat.status != CompatibilityStatus.LIKELY_SUPPORTED,
            )
        }
        val htp = inspection.compatibilityFor(BackendId.HTP_QNN)!!
        assertEquals(CompatibilityStatus.REQUIRES_CONVERSION, htp.status)
        assertTrue(htp.reason.contains("QNN"))
    }

    @Test
    fun unknownFormatIsUnsupportedEverywhere() {
        val inspection = ModelInspector.inspect("mystery.bin", 10, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08))
        assertEquals(ModelFormat.UNKNOWN, inspection.format)
        for (compat in inspection.compatibility) {
            assertEquals(CompatibilityStatus.UNSUPPORTED, compat.status)
        }
    }

    @Test
    fun rejectsTruncatedSafetensorsHeader() {
        try {
            ModelInspector.parseSafetensorsHeader(byteArrayOf(0x00, 0x01))
            fail("expected failure on truncated header")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("too short") || expected.message!!.contains("truncated"))
        }
    }

    @Test
    fun rejectsMalformedHeaderJson() {
        val json = "{not json}"
        val header = buildHeader(json)
        try {
            ModelInspector.parseSafetensorsHeader(header)
            fail("expected failure on malformed JSON")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("JSON"))
        }
    }

    @Test
    fun rejectsInvalidDataOffsets() {
        val json = """{"t":{"dtype":"F32","shape":[1],"data_offsets":[10,4]}}"""
        try {
            ModelInspector.parseSafetensorsHeader(buildHeader(json))
            fail("expected failure on invalid offsets")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("data_offsets"))
        }
    }

    @Test
    fun classifiesTensorNames() {
        assertEquals(PipelineComponent.UNET, ModelInspector.classifyTensorName("model.diffusion_model.x"))
        assertEquals(PipelineComponent.VAE, ModelInspector.classifyTensorName("first_stage_model.decoder.up.0"))
        assertEquals(PipelineComponent.TEXT_ENCODER, ModelInspector.classifyTensorName("cond_stage_model.transformer.x"))
        assertEquals(PipelineComponent.CONTROLNET, ModelInspector.classifyTensorName("controlnet.input_blocks"))
        assertEquals(PipelineComponent.OTHER, ModelInspector.classifyTensorName("random.weight"))
    }

    @Test
    fun pickleCheckpointsCarrySafetyWarning() {
        val inspection = ModelInspector.inspect("model.ckpt", 100, byteArrayOf(0x80.toByte(), 0x02, 0x00, 0x00))
        assertEquals(ModelFormat.PICKLE_CHECKPOINT, inspection.format)
        assertTrue(inspection.inspectionWarnings.any { it.contains("arbitrary code") })
    }
}
