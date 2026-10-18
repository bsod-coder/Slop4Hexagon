package com.slop4hexagon

import com.slop4hexagon.domain.model.ModelFormat
import com.slop4hexagon.domain.model.ModelFormatRegistry
import org.junit.Assert.*
import org.junit.Test

class ModelFormatTest {
    @Test
    fun `detect by extension`() {
        assertEquals(ModelFormat.ONNX, ModelFormatRegistry.detect("model.onnx"))
        assertEquals(ModelFormat.TFLITE, ModelFormatRegistry.detect("model.tflite"))
        assertEquals(ModelFormat.DLC, ModelFormatRegistry.detect("model.dlc"))
        assertEquals(ModelFormat.SAFETENSORS, ModelFormatRegistry.detect("sd.safetensors"))
        assertEquals(ModelFormat.CKPT, ModelFormatRegistry.detect("model.ckpt"))
        assertEquals(ModelFormat.GGUF, ModelFormatRegistry.detect("llama.gguf"))
        assertEquals(ModelFormat.UNKNOWN, ModelFormatRegistry.detect("unknown.xyz"))
    }

    @Test
    fun `magic overrides for tflite`() {
        val tfliteMagic = byteArrayOf(0x54, 0x46, 0x4C, 0x33, 0x00)
        assertEquals(ModelFormat.TFLITE, ModelFormatRegistry.detect("renamed.bin", tfliteMagic))
    }

    @Test
    fun `gguf magic`() {
        val ggufMagic = byteArrayOf(0x47, 0x47, 0x55, 0x46)
        assertEquals(ModelFormat.GGUF, ModelFormatRegistry.detect("weird.dat", ggufMagic))
    }

    @Test
    fun `safetensors header heuristic`() {
        // 8 byte LE len + json start
        val bytes = ByteArray(16)
        bytes[0] = 10 // len 10
        bytes[8] = '{'.code.toByte()
        assertEquals(ModelFormat.SAFETENSORS, ModelFormatRegistry.detect("a.safetensors", bytes))
    }

    @Test
    fun `unknown stays unknown`() {
        assertEquals(ModelFormat.UNKNOWN, ModelFormatRegistry.detect("file.unknown", ByteArray(4)))
    }

    @Test
    fun `fromExtension case insensitive`() {
        assertEquals(ModelFormat.ONNX, ModelFormat.fromExtension("ONNX"))
        assertEquals(ModelFormat.TFLITE, ModelFormat.fromExtension(".tflite"))
    }
}
