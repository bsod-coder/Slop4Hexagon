package com.slop4hexagon.core.model

import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDescriptorJsonTest {

    private fun descriptor() = ModelDescriptor(
        id = "model-42",
        displayName = "SD 1.5 base",
        originalFileName = "sd15.safetensors",
        sizeBytes = 4_234_567_890L,
        sha256 = "deadbeefcafebabe",
        importedAtEpochMs = 1_760_000_000_000L,
        inspection = ModelInspection(
            format = ModelFormat.SAFETENSORS,
            magicConfirmed = true,
            weightBytes = 4_000_000_000L,
            estimatedMemoryBytes = 4_600_000_000L,
            tensorCount = 2,
            tensors = listOf(
                TensorInfo("unet.weight", "F16", listOf(320, 4, 3, 3), 23040),
                TensorInfo("vae.bias", "F32", listOf(512), 2048),
            ),
            components = listOf(
                ComponentHint(PipelineComponent.UNET, 1, 23040),
                ComponentHint(PipelineComponent.VAE, 1, 2048),
            ),
            formatWarnings = listOf("w1"),
            inspectionWarnings = listOf("w2"),
            compatibility = listOf(
                BackendCompatibility(
                    BackendId.HTP_QNN,
                    CompatibilityStatus.REQUIRES_CONVERSION,
                    "needs QNN compilation",
                ),
            ),
            isSinglePipelineFile = false,
        ),
        note = "test note",
    )

    @Test
    fun descriptorRoundTrips() {
        val original = descriptor()
        val encoded = ModelDescriptorJson.encode(original)
        val decoded = ModelDescriptorJson.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun indexRoundTrips() {
        val models = listOf(descriptor(), descriptor().copy(id = "model-43", displayName = "other"))
        val encoded = ModelDescriptorJson.encodeIndex(models)
        val decoded = ModelDescriptorJson.decodeIndex(encoded)
        assertEquals(models, decoded)
    }

    @Test
    fun wireFormatKeepsHonestCompatibilityText() {
        val text = ModelDescriptorJson.encode(descriptor()).toPrettyString()
        assertTrue(text.contains("needs QNN compilation"))
        assertTrue(text.contains("REQUIRES_CONVERSION"))
        assertTrue(text.contains("sd15.safetensors"))
    }

    @Test
    fun decodeRejectsRecordWithoutId() {
        val broken = JsonValue.parseObject("""{"displayName":"x"}""")
        try {
            ModelDescriptorJson.decode(broken)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("id"))
        }
    }

    @Test
    fun unknownEnumValuesDecodeSafely() {
        val json = ModelDescriptorJson.encode(descriptor().copy()).toPrettyString()
            .replace("SAFETENSORS", "SOME_FUTURE_FORMAT")
        val decoded = ModelDescriptorJson.decodeFromStringCompat(json)
        assertEquals(ModelFormat.UNKNOWN, decoded.inspection.format)
    }

    private fun ModelDescriptorJson.decodeFromStringCompat(text: String): ModelDescriptor =
        decode(JsonValue.parseObject(text))
}
