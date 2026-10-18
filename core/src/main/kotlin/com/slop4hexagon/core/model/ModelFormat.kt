package com.slop4hexagon.core.model

import com.slop4hexagon.core.backend.BackendId

/**
 * Registry of model container formats this app can recognise.
 *
 * Recognising a format is NOT the same as being able to execute it. Each format
 * describes whether it is a weights bundle (needs a pipeline engine) or an
 * executable artifact (needs a specific runtime), and [ModelFormatRegistry]
 * only reports facts derived from the file header — never guesses success.
 */
enum class ModelFormat(
    val displayName: String,
    val isWeightsBundle: Boolean,
    val isExecutableArtifact: Boolean,
    val extensions: Set<String>,
) {
    SAFETENSORS(
        displayName = "SafeTensors weights bundle",
        isWeightsBundle = true,
        isExecutableArtifact = false,
        extensions = setOf(".safetensors"),
    ),
    PICKLE_CHECKPOINT(
        displayName = "Legacy pickle checkpoint (.ckpt/.pt/.pth)",
        isWeightsBundle = true,
        isExecutableArtifact = false,
        extensions = setOf(".ckpt", ".pt", ".pth"),
    ),
    ONNX(
        displayName = "ONNX model graph",
        isWeightsBundle = false,
        isExecutableArtifact = true,
        extensions = setOf(".onnx"),
    ),
    GGUF(
        displayName = "GGUF container (llama.cpp family)",
        isWeightsBundle = true,
        isExecutableArtifact = true,
        extensions = setOf(".gguf"),
    ),
    TFLITE(
        displayName = "TensorFlow Lite flatbuffer",
        isWeightsBundle = false,
        isExecutableArtifact = true,
        extensions = setOf(".tflite"),
    ),
    QNN_CONTEXT_BINARY(
        displayName = "QNN context binary (HTP-compiled artifact)",
        isWeightsBundle = false,
        isExecutableArtifact = true,
        extensions = setOf(".serialized", ".bin", ".qnnbin"),
    ),
    QNN_DLC(
        displayName = "Qualcomm Deep Learning Container (.dlc)",
        isWeightsBundle = false,
        isExecutableArtifact = true,
        extensions = setOf(".dlc"),
    ),
    UNKNOWN(
        displayName = "Unrecognised format",
        isWeightsBundle = false,
        isExecutableArtifact = false,
        extensions = emptySet(),
    );

    fun matchesExtension(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return extensions.any { lower.endsWith(it) }
    }
}

data class FormatDetection(
    val format: ModelFormat,
    /** True when a magic-number check confirmed the format, not just the name. */
    val magicConfirmed: Boolean,
    val warnings: List<String> = emptyList(),
)

object ModelFormatRegistry {

    /** Upper bound on the safetensors JSON header we are willing to read (64 MiB). */
    const val MAX_SAFETENSORS_HEADER_BYTES = 64L * 1024 * 1024

    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"
    private val TFLITE_MAGIC = byteArrayOf(0x54, 0x46, 0x4C, 0x33) // "TFL3" at offset 4
    private const val PICKLE_PROTOCOL_MARKER = 0x80

    /**
     * Detect the container format from the file name and the first bytes of the
     * file. Detection conflicts (extension vs magic) are reported as warnings;
     * unknown content is [ModelFormat.UNKNOWN], never silently accepted.
     */
    fun detect(fileName: String, header: ByteArray): FormatDetection {
        val warnings = mutableListOf<String>()
        val extFormat = byExtension(fileName)
        val magicFormat = byMagic(header)

        if (magicFormat != null && extFormat != null && magicFormat != extFormat) {
            warnings += "Extension suggests ${extFormat.name} but file magic matches ${magicFormat.name}; treating as ${magicFormat.name}"
            return FormatDetection(magicFormat, magicConfirmed = true, warnings = warnings)
        }

        if (magicFormat != null) {
            return FormatDetection(magicFormat, magicConfirmed = true, warnings = warnings)
        }

        if (extFormat != null) {
            // ".bin" is a generic extension (Torch weights, QNN context binaries,
            // arbitrary blobs). Never classify it without a magic confirmation.
            if (extFormat == ModelFormat.QNN_CONTEXT_BINARY && fileName.lowercase().endsWith(".bin")) {
                warnings += "'.bin' is ambiguous (PyTorch weights, QNN context binary, generic blob); " +
                    "cannot classify without a known magic number"
                return FormatDetection(ModelFormat.UNKNOWN, magicConfirmed = false, warnings = warnings)
            }
            // Extension-only detections that cannot be confirmed by magic bytes.
            when (extFormat) {
                ModelFormat.QNN_CONTEXT_BINARY, ModelFormat.QNN_DLC -> warnings +=
                    "${extFormat.displayName} has no stable public magic number; " +
                        "the file can only be verified by loading it with the QNN runtime"
                ModelFormat.ONNX -> warnings +=
                    "ONNX magic not confirmed (protobuf header looks unusual); " +
                        "the file can only be verified by an ONNX/QNN parser"
                else -> warnings += "Format identified from file extension only"
            }
            return FormatDetection(extFormat, magicConfirmed = false, warnings = warnings)
        }

        warnings += "Unrecognised file format: no known magic number and no known extension"
        return FormatDetection(ModelFormat.UNKNOWN, magicConfirmed = false, warnings = warnings)
    }

    fun byExtension(fileName: String): ModelFormat? = ModelFormat.entries.firstOrNull { it.matchesExtension(fileName) }

    fun byMagic(header: ByteArray): ModelFormat? {
        if (header.isEmpty()) return null
        if (header.size >= 4 && header.copyOfRange(0, 4).contentEquals(GGUF_MAGIC)) return ModelFormat.GGUF
        if (header.size >= 8 && header.copyOfRange(4, 8).contentEquals(TFLITE_MAGIC)) return ModelFormat.TFLITE
        if (header.size >= 8) {
            val headerSize = readLittleEndianLong(header, 0)
            if (headerSize in 2..MAX_SAFETENSORS_HEADER_BYTES) {
                // A well-formed safetensors file is: u64le header length, then '{'.
                // Only treat as safetensors when we can actually see the '{' or the
                // file is shorter than the declared header (still plausible).
                if (header.size < 8 + 1 || header[8] == '{'.code.toByte()) return ModelFormat.SAFETENSORS
            }
        }
        if (header.isNotEmpty() && (header[0].toInt() and 0xFF) == PICKLE_PROTOCOL_MARKER) {
            return ModelFormat.PICKLE_CHECKPOINT
        }
        if (header.isNotEmpty() && header[0] == 0x08.toByte()) {
            // Protobuf: ONNX models almost always start with field 1 (ir_version).
            return ModelFormat.ONNX
        }
        return null
    }

    fun readLittleEndianLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}

/** Per-backend verdict for a detected model format. */
enum class CompatibilityStatus {
    /** The backend can execute this artifact (subject to runtime availability). */
    LIKELY_SUPPORTED,

    /** Weights/graph need a pipeline or model engine this build does not ship. */
    REQUIRES_ENGINE,

    /** Must be converted/compiled first (e.g. safetensors → QNN context binary). */
    REQUIRES_CONVERSION,

    /** This backend can never run this artifact. */
    UNSUPPORTED,

    /** Not enough information; do not claim support. */
    UNKNOWN,
}

data class BackendCompatibility(
    val backend: BackendId,
    val status: CompatibilityStatus,
    val reason: String,
)
