package com.slop4hexagon.domain.model

/**
 * Registry of model file formats known to the lab.
 * Supporting a file in the library ≠ executable by any backend.
 */
enum class ModelFormat(val extensions: List<String>, val displayName: String) {
    ONNX(listOf("onnx"), "ONNX"),
    TFLITE(listOf("tflite"), "TFLite"),
    DLC(listOf("dlc", "bin"), "QNN DLC (HTP)"),
    SAFETENSORS(listOf("safetensors"), "Safetensors (SD checkpoint)"),
    CKPT(listOf("ckpt", "pt", "pth"), "PyTorch Checkpoint"),
    GGUF(listOf("gguf"), "GGUF (llama.cpp)"),
    UNKNOWN(emptyList(), "Unknown");

    companion object {
        fun fromExtension(ext: String): ModelFormat {
            val lower = ext.lowercase().trimStart('.')
            return values().firstOrNull { lower in it.extensions } ?: UNKNOWN
        }
    }
}

/**
 * Determines format from extension + optional header bytes.
 * Never loads whole file into heap; headerBytes is first 64-256 bytes max.
 */
object ModelFormatRegistry {
    fun detect(fileName: String, headerBytes: ByteArray? = null, fileSize: Long = -1): ModelFormat {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val byExt = ModelFormat.fromExtension(ext)
        // Magic checks to avoid misclassifying renamed files
        if (headerBytes != null && headerBytes.size >= 4) {
            val magic = headerBytes.take(4).toByteArray()
            // TFLite: "TFL3"
            if (magic.contentEquals(byteArrayOf(0x54, 0x46, 0x4C, 0x33))) return ModelFormat.TFLITE
            // GGUF: "GGUF"
            if (magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) return ModelFormat.GGUF
            // ONNX: protobuf often starts with 0x08, but not reliable; keep ext-based
            // safetensors: first 8 bytes LE uint64 header length, then JSON; heuristic
            if (byExt == ModelFormat.SAFETENSORS || isLikelySafetensors(headerBytes)) {
                return ModelFormat.SAFETENSORS
            }
        }
        // DLC: QNN compiled artifacts often .bin but with specific header "DLC\0" or ELF
        if (byExt == ModelFormat.DLC) return ModelFormat.DLC
        return byExt
    }

    private fun isLikelySafetensors(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        // First 8 bytes LE length < 10MB for typical header
        val len = (bytes[0].toLong() and 0xFF) or
            ((bytes[1].toLong() and 0xFF) shl 8) or
            ((bytes[2].toLong() and 0xFF) shl 16) or
            ((bytes[3].toLong() and 0xFF) shl 24)
        if (len <= 0 || len > 20_000_000) return false
        // Next bytes should be '{' JSON
        val jsonStart = if (bytes.size > 8) bytes[8].toInt().toChar() else ' '
        return jsonStart == '{' || jsonStart == '"'
    }
}

/**
 * Estimated memory: very rough heuristic for UI display.
 * Real requirement depends on backend, quantization, and operator coverage.
 */
fun estimateMemoryRequirementBytes(format: ModelFormat, fileSizeBytes: Long): Long? {
    if (fileSizeBytes <= 0) return null
    return when (format) {
        ModelFormat.ONNX, ModelFormat.TFLITE -> fileSizeBytes * 4 // model + intermediate
        ModelFormat.DLC -> fileSizeBytes * 2
        ModelFormat.SAFETENSORS, ModelFormat.CKPT -> fileSizeBytes * 3 // SD UNet+VAE+text encoder unpacked
        ModelFormat.GGUF -> fileSizeBytes * 2
        ModelFormat.UNKNOWN -> null
    }
}
