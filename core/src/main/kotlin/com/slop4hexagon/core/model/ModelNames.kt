package com.slop4hexagon.core.model

/** Pure helpers for human-readable model naming, unit-tested on the JVM. */
object ModelNames {

    /**
     * Library display name for an imported file: the base name without its
     * extension, whitespace collapsed, and a format suffix appended so two
     * files with the same base name remain distinguishable.
     */
    fun displayNameFor(originalFileName: String, format: ModelFormat?): String {
        val base = originalFileName.substringBeforeLast('.', originalFileName)
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "model" }
        val formatSuffix = when (format) {
            null, ModelFormat.UNKNOWN -> ""
            else -> " [${shortFormatName(format)}]"
        }
        return "$base$formatSuffix"
    }

    fun shortFormatName(format: ModelFormat): String = when (format) {
        ModelFormat.SAFETENSORS -> "safetensors"
        ModelFormat.PICKLE_CHECKPOINT -> "ckpt"
        ModelFormat.ONNX -> "onnx"
        ModelFormat.GGUF -> "gguf"
        ModelFormat.TFLITE -> "tflite"
        ModelFormat.QNN_CONTEXT_BINARY -> "qnn-ctx"
        ModelFormat.QNN_DLC -> "dlc"
        ModelFormat.UNKNOWN -> "unknown"
    }

    /** Byte counts the way the library list shows them. */
    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "unknown size"
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KiB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
