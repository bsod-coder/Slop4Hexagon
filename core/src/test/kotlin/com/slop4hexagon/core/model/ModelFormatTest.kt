package com.slop4hexagon.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFormatTest {

    private fun safetensorsHeader(prefix: ByteArray = ByteArray(0)): ByteArray {
        // 8-byte little-endian length + '{' start of JSON
        val json = "{\"x\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}"
        val len = json.length.toLong()
        val out = ByteArray(8 + json.length)
        for (i in 0 until 8) out[i] = ((len shr (8 * i)) and 0xFF).toByte()
        System.arraycopy(json.toByteArray(), 0, out, 8, json.length)
        return prefix + out
    }

    @Test
    fun detectsSafetensorsByMagic() {
        val header = safetensorsHeader()
        val d = ModelFormatRegistry.detect("model.safetensors", header)
        assertEquals(ModelFormat.SAFETENSORS, d.format)
        assertTrue(d.magicConfirmed)
    }

    @Test
    fun detectsGgufByMagic() {
        val bytes = byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x03, 0x00, 0x00, 0x00)
        val d = ModelFormatRegistry.detect("whatever.gguf", bytes)
        assertEquals(ModelFormat.GGUF, d.format)
        assertTrue(d.magicConfirmed)
    }

    @Test
    fun detectsTfliteByMagic() {
        val bytes = byteArrayOf(0x18, 0x00, 0x00, 0x00, 0x54, 0x46, 0x4C, 0x33)
        val d = ModelFormatRegistry.detect("net.tflite", bytes)
        assertEquals(ModelFormat.TFLITE, d.format)
        assertTrue(d.magicConfirmed)
    }

    @Test
    fun detectsPickleByMagic() {
        val bytes = byteArrayOf(0x80.toByte(), 0x02, 0x0A, 0x00)
        val d = ModelFormatRegistry.detect("legacy.ckpt", bytes)
        assertEquals(ModelFormat.PICKLE_CHECKPOINT, d.format)
        assertTrue(d.magicConfirmed)
    }

    @Test
    fun magicWinsOverConflictingExtension() {
        val gguf = byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x00, 0x00, 0x00, 0x00)
        val d = ModelFormatRegistry.detect("misnamed.safetensors", gguf)
        assertEquals(ModelFormat.GGUF, d.format)
        assertTrue(d.warnings.isNotEmpty())
    }

    @Test
    fun extensionOnlyDetectionIsNotMagicConfirmed() {
        val d = ModelFormatRegistry.detect("net.onnx", byteArrayOf(0x12, 0x34))
        assertEquals(ModelFormat.ONNX, d.format)
        assertFalse(d.magicConfirmed)
        assertTrue(d.warnings.isNotEmpty())
    }

    @Test
    fun qnnArtifactsWarnAboutMissingMagic() {
        val d = ModelFormatRegistry.detect("compiled.serialized", byteArrayOf(0x01, 0x02, 0x03))
        assertEquals(ModelFormat.QNN_CONTEXT_BINARY, d.format)
        assertFalse(d.magicConfirmed)
        assertTrue(d.warnings.any { it.contains("no stable public magic") })
    }

    @Test
    fun unknownContentIsReportedAsUnknown() {
        val d = ModelFormatRegistry.detect("random.dat", byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00))
        assertEquals(ModelFormat.UNKNOWN, d.format)
        assertTrue(d.warnings.isNotEmpty())
    }

    @Test
    fun binExtensionAloneDoesNotInventAFormat() {
        val d = ModelFormatRegistry.detect("weights.bin", byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))
        assertEquals(ModelFormat.UNKNOWN, d.format)
    }

    @Test
    fun readLittleEndianLongParsesCorrectly() {
        val bytes = byteArrayOf(0x39, 0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(0x3039L, ModelFormatRegistry.readLittleEndianLong(bytes, 0))
    }
}
