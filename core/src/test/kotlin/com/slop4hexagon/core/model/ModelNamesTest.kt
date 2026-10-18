package com.slop4hexagon.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelNamesTest {

    @Test
    fun displayNamesStripExtensionAndAddFormat() {
        assertEquals(
            "v1-5-primitive [safetensors]",
            ModelNames.displayNameFor("v1-5-primitive.safetensors", ModelFormat.SAFETENSORS),
        )
    }

    @Test
    fun weirdNamesCollapseWhitespaceAndNeverGoBlank() {
        assertEquals("a b c [onnx]", ModelNames.displayNameFor("a__b   c.onnx", ModelFormat.ONNX))
        assertEquals("model [gguf]", ModelNames.displayNameFor(".", ModelFormat.GGUF))
        assertEquals("model", ModelNames.displayNameFor("", null))
    }

    @Test
    fun unknownFormatGetsNoSuffix() {
        assertEquals("mystery", ModelNames.displayNameFor("mystery.dat", ModelFormat.UNKNOWN))
    }

    @Test
    fun humanSizesUseBinaryUnits() {
        assertEquals("unknown size", ModelNames.humanSize(0))
        assertEquals("unknown size", ModelNames.humanSize(-5))
        assertEquals("512 B", ModelNames.humanSize(512))
        assertEquals("1.0 KiB", ModelNames.humanSize(1024))
        assertEquals("1.5 MiB", ModelNames.humanSize(1024 * 1024 + 512 * 1024))
        assertEquals("2.00 GiB", ModelNames.humanSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun shortFormatNamesAreStableLabels() {
        assertEquals("qnn-ctx", ModelNames.shortFormatName(ModelFormat.QNN_CONTEXT_BINARY))
        assertEquals("ckpt", ModelNames.shortFormatName(ModelFormat.PICKLE_CHECKPOINT))
        for (format in ModelFormat.entries) {
            assertTrue(format.name, ModelNames.shortFormatName(format).isNotEmpty())
        }
    }
}
