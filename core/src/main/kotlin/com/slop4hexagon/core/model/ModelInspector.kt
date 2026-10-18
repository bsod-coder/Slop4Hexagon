package com.slop4hexagon.core.model

import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.json.JsonParseException
import com.slop4hexagon.core.json.JsonValue

/** One tensor entry of a SafeTensors header. */
data class TensorInfo(
    val name: String,
    val dtype: String,
    val shape: List<Long>,
    val dataBytes: Long,
)

/** Pipeline components hinted at by tensor naming inside a weights bundle. */
enum class PipelineComponent(val displayName: String) {
    UNET("Denoiser / UNet"),
    VAE("VAE decoder"),
    TEXT_ENCODER("Text encoder"),
    CONTROLNET("ControlNet"),
    OTHER("Other weights"),
}

data class ComponentHint(
    val component: PipelineComponent,
    val tensorCount: Int,
    val bytes: Long,
)

data class ModelInspection(
    val format: ModelFormat,
    val magicConfirmed: Boolean,
    /** Total bytes of tensor data declared by the file (weights payload). */
    val weightBytes: Long,
    /** Rough lower bound for keeping weights resident; excludes activations. */
    val estimatedMemoryBytes: Long?,
    val tensorCount: Int,
    val tensors: List<TensorInfo>,
    val components: List<ComponentHint>,
    val formatWarnings: List<String>,
    val inspectionWarnings: List<String>,
    /** The format registry's compatibility matrix for this file. */
    val compatibility: List<BackendCompatibility>,
    /** True when a full generative pipeline would need more than one file. */
    val isSinglePipelineFile: Boolean,
) {
    fun compatibilityFor(backend: BackendId): BackendCompatibility? =
        compatibility.firstOrNull { it.backend == backend }
}

/**
 * Inspects model files far enough to describe them truthfully: container
 * format, weight payload size, probable pipeline components and per-backend
 * compatibility. It never claims that a weights bundle can be executed
 * directly.
 */
object ModelInspector {

    /** Extra bytes estimated for allocator/tensor overhead on top of weights. */
    private const val MEMORY_OVERHEAD_FACTOR = 1.15

    fun inspect(fileName: String, fileSizeBytes: Long, header: ByteArray): ModelInspection {
        val detection = ModelFormatRegistry.detect(fileName, header)
        val inspectionWarnings = mutableListOf<String>()

        var tensors: List<TensorInfo> = emptyList()
        var components: List<ComponentHint> = emptyList()
        var weightBytes = 0L
        var isSinglePipelineFile = false

        when (detection.format) {
            ModelFormat.SAFETENSORS -> {
                val parsed = parseSafetensorsHeader(header)
                tensors = parsed.tensors
                weightBytes = parsed.totalDataBytes
                components = groupComponents(tensors)
                isSinglePipelineFile =
                    components.any { it.component == PipelineComponent.UNET } &&
                    components.any { it.component == PipelineComponent.VAE } &&
                    components.any { it.component == PipelineComponent.TEXT_ENCODER }
                if (tensors.isEmpty()) {
                    inspectionWarnings += "SafeTensors header contains no tensors"
                }
                if (fileSizeBytes > 0 && weightBytes > 0 && fileSizeBytes < weightBytes) {
                    inspectionWarnings +=
                        "Declared tensor payload ($weightBytes bytes) exceeds file size ($fileSizeBytes bytes); header is inconsistent"
                }
                if (detection.magicConfirmed.not()) {
                    inspectionWarnings += "SafeTensors structure inferred from header only"
                }
            }
            ModelFormat.PICKLE_CHECKPOINT -> {
                inspectionWarnings +=
                    "Pickle-based checkpoints can execute arbitrary code when loaded " +
                        "and are not parsed by this app; convert to SafeTensors first"
                inspectionWarnings += "Weight payload size cannot be determined without loading the pickle"
            }
            ModelFormat.UNKNOWN -> {
                inspectionWarnings += "Unknown formats are not accepted as executable models"
            }
            else -> {
                // Executable artifacts: payload = file size; internals need the owning runtime.
                weightBytes = fileSizeBytes
                inspectionWarnings += "Internal structure not inspected; verification requires loading the artifact in its runtime"
            }
        }

        val estimated = if (weightBytes > 0) {
            (weightBytes * MEMORY_OVERHEAD_FACTOR).toLong()
        } else {
            null
        }

        return ModelInspection(
            format = detection.format,
            magicConfirmed = detection.magicConfirmed,
            weightBytes = weightBytes,
            estimatedMemoryBytes = estimated,
            tensorCount = tensors.size,
            tensors = tensors,
            components = components,
            formatWarnings = detection.warnings,
            inspectionWarnings = inspectionWarnings,
            compatibility = CompatibilityMatrix.forFormat(detection.format, detection.magicConfirmed),
            isSinglePipelineFile = isSinglePipelineFile,
        )
    }

    data class SafetensorsHeader(val tensors: List<TensorInfo>, val totalDataBytes: Long)

    /**
     * Parse the JSON header of a SafeTensors file from its leading bytes:
     * u64le header length N followed by N bytes of JSON.
     *
     * @throws IllegalArgumentException when the header is malformed.
     */
    fun parseSafetensorsHeader(header: ByteArray): SafetensorsHeader {
        if (header.size < 8) throw IllegalArgumentException("SafeTensors header too short")
        val n = ModelFormatRegistry.readLittleEndianLong(header, 0)
        if (n < 2 || n > ModelFormatRegistry.MAX_SAFETENSORS_HEADER_BYTES) {
            throw IllegalArgumentException("SafeTensors header length out of range: $n")
        }
        if (header.size < 8 + n) {
            throw IllegalArgumentException("SafeTensors header truncated: need ${8 + n} bytes, have ${header.size}")
        }
        val jsonText = header.copyOfRange(8, 8 + n.toInt()).toString(Charsets.UTF_8)
        val root = try {
            JsonValue.parseObject(jsonText)
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("SafeTensors header is not valid JSON: ${e.message}")
        }

        val tensors = ArrayList<TensorInfo>()
        var total = 0L
        for ((name, value) in root.fields) {
            if (name == "__metadata__") continue
            val obj = value as? JsonValue.JsonObject
                ?: throw IllegalArgumentException("Tensor entry '$name' is not an object")
            val dtype = obj.getString("dtype") ?: "UNKNOWN"
            val shape = obj.getArray("shape")?.items?.map {
                (it as? JsonValue.JsonNumber)?.value?.toLong()
                    ?: throw IllegalArgumentException("Tensor '$name' has a non-numeric shape")
            } ?: emptyList()
            val offsets = obj.getArray("data_offsets")?.items
                ?: throw IllegalArgumentException("Tensor '$name' is missing data_offsets")
            if (offsets.size != 2) throw IllegalArgumentException("Tensor '$name' has malformed data_offsets")
            val begin = (offsets[0] as? JsonValue.JsonNumber)?.value?.toLong()
                ?: throw IllegalArgumentException("Tensor '$name' has non-numeric data_offsets")
            val end = (offsets[1] as? JsonValue.JsonNumber)?.value?.toLong()
                ?: throw IllegalArgumentException("Tensor '$name' has non-numeric data_offsets")
            if (end < begin || begin < 0) {
                throw IllegalArgumentException("Tensor '$name' has invalid data_offsets [$begin, $end)")
            }
            val bytes = end - begin
            tensors += TensorInfo(name = name, dtype = dtype, shape = shape, dataBytes = bytes)
            total += bytes
        }
        tensors.sortBy { it.name }
        return SafetensorsHeader(tensors, total)
    }

    private fun groupComponents(tensors: List<TensorInfo>): List<ComponentHint> {
        val grouped = LinkedHashMap<PipelineComponent, Pair<Int, Long>>()
        for (t in tensors) {
            val component = classifyTensorName(t.name)
            val current = grouped[component] ?: (0 to 0L)
            grouped[component] = (current.first + 1) to (current.second + t.dataBytes)
        }
        return grouped.map { (component, countAndBytes) ->
            ComponentHint(component, countAndBytes.first, countAndBytes.second)
        }
    }

    fun classifyTensorName(name: String): PipelineComponent {
        val n = name.lowercase()
        return when {
            n.contains("first_stage_model") || n.contains("vae") || n.contains("decoder") && n.contains("up") ->
                PipelineComponent.VAE
            n.contains("cond_stage_model") || n.contains("text_encoder") || n.contains("clip") ||
                n.contains("transformer") && n.contains("text") ->
                PipelineComponent.TEXT_ENCODER
            n.contains("controlnet") -> PipelineComponent.CONTROLNET
            n.contains("unet") || n.contains("model.diffusion") || n.contains("denoiser") ->
                PipelineComponent.UNET
            else -> PipelineComponent.OTHER
        }
    }
}

/**
 * Honest per-format compatibility verdicts per backend.
 *
 * The matrix intentionally errs on the side of "not supported": no format is
 * listed as executable unless an engine in this build can actually run it.
 */
object CompatibilityMatrix {

    fun forFormat(format: ModelFormat, magicConfirmed: Boolean): List<BackendCompatibility> = when (format) {
        ModelFormat.SAFETENSORS, ModelFormat.PICKLE_CHECKPOINT -> listOf(
            BackendCompatibility(
                BackendId.CPU,
                CompatibilityStatus.REQUIRES_ENGINE,
                "Weights bundle only. This build ships no diffusion pipeline engine " +
                    "(UNet+VAE+text encoder) that can execute Stable Diffusion checkpoints.",
            ),
            BackendCompatibility(
                BackendId.GPU,
                CompatibilityStatus.REQUIRES_ENGINE,
                "No GPU pipeline engine is implemented in this build.",
            ),
            BackendCompatibility(
                BackendId.HTP_QNN,
                CompatibilityStatus.REQUIRES_CONVERSION,
                "Stable Diffusion weights cannot run on HTP directly. They must be " +
                    "converted and compiled with the Qualcomm AI Engine Direct (QNN) SDK " +
                    "into a context binary with supported operators first.",
            ),
        )
        ModelFormat.ONNX -> listOf(
            BackendCompatibility(
                BackendId.CPU,
                CompatibilityStatus.REQUIRES_ENGINE,
                "Requires an ONNX Runtime engine; none is bundled in this build.",
            ),
            BackendCompatibility(
                BackendId.GPU,
                CompatibilityStatus.REQUIRES_ENGINE,
                "Requires a GPU ONNX/OpenCL engine; none is bundled in this build.",
            ),
            BackendCompatibility(
                BackendId.HTP_QNN,
                CompatibilityStatus.REQUIRES_CONVERSION,
                "ONNX graphs must be converted with the QNN SDK ONNX converter and " +
                    "compiled to an HTP context binary before execution.",
            ),
        )
        ModelFormat.GGUF -> listOf(
            BackendCompatibility(BackendId.CPU, CompatibilityStatus.UNSUPPORTED,
                "GGUF is a llama.cpp-family container; no GGUF engine is bundled."),
            BackendCompatibility(BackendId.GPU, CompatibilityStatus.UNSUPPORTED,
                "No GGUF GPU engine is bundled."),
            BackendCompatibility(BackendId.HTP_QNN, CompatibilityStatus.UNSUPPORTED,
                "GGUF artifacts are not executable on QNN HTP without a dedicated converter."),
        )
        ModelFormat.TFLITE -> listOf(
            BackendCompatibility(BackendId.CPU, CompatibilityStatus.REQUIRES_ENGINE,
                "Requires a TFLite interpreter; none is bundled in this build."),
            BackendCompatibility(BackendId.GPU, CompatibilityStatus.REQUIRES_ENGINE,
                "Requires TFLite GPU delegate; none is bundled in this build."),
            BackendCompatibility(BackendId.HTP_QNN, CompatibilityStatus.REQUIRES_CONVERSION,
                "TFLite models must be converted to a QNN context binary for HTP execution."),
        )
        ModelFormat.QNN_CONTEXT_BINARY -> listOf(
            BackendCompatibility(BackendId.CPU, CompatibilityStatus.UNSUPPORTED,
                "QNN context binaries are tied to the QNN HTP runtime, not portable CPU ops."),
            BackendCompatibility(BackendId.GPU, CompatibilityStatus.UNSUPPORTED,
                "QNN context binaries target a specific QNN backend (here: HTP)."),
            BackendCompatibility(
                BackendId.HTP_QNN,
                if (magicConfirmed) CompatibilityStatus.LIKELY_SUPPORTED else CompatibilityStatus.UNKNOWN,
                "Executable QNN artifact. Loads only when the QNN runtime libraries " +
                    "(libQnnHtp.so and friends) are present in the app; loading is the only " +
                    "real verification because this file type has no public magic number.",
            ),
        )
        ModelFormat.QNN_DLC -> listOf(
            BackendCompatibility(BackendId.CPU, CompatibilityStatus.UNSUPPORTED,
                "DLC is a Qualcomm container for QNN runtimes."),
            BackendCompatibility(BackendId.GPU, CompatibilityStatus.UNSUPPORTED,
                "DLC is not portable to other engines without conversion."),
            BackendCompatibility(
                BackendId.HTP_QNN,
                CompatibilityStatus.UNKNOWN,
                "DLC artifacts require the QNN/QAIRT runtime to load; typically compiled " +
                    "to a context binary for HTP first. Loading is the only real verification.",
            ),
        )
        ModelFormat.UNKNOWN -> listOf(
            BackendCompatibility(BackendId.CPU, CompatibilityStatus.UNSUPPORTED, "Unrecognised format."),
            BackendCompatibility(BackendId.GPU, CompatibilityStatus.UNSUPPORTED, "Unrecognised format."),
            BackendCompatibility(BackendId.HTP_QNN, CompatibilityStatus.UNSUPPORTED, "Unrecognised format."),
        )
    }
}
