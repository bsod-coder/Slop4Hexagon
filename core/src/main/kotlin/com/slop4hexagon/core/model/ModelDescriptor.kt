package com.slop4hexagon.core.model

import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.json.JsonObjectBuilder
import com.slop4hexagon.core.json.JsonValue

/** Persistence record for one imported model file. */
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    /** Original file name as chosen by the user (for duplicate hints). */
    val originalFileName: String,
    /** Size of the imported copy in app-managed storage. */
    val sizeBytes: Long,
    /** SHA-256 of the imported copy, lowercase hex; null when not computed. */
    val sha256: String?,
    val importedAtEpochMs: Long,
    val inspection: ModelInspection,
    /** Free-form user note. */
    val note: String? = null,
) {
    fun formatDisplayName(): String = inspection.format.displayName

    fun estimatedMemoryDisplayBytes(): Long? = inspection.estimatedMemoryBytes

    fun compatibilityFor(backend: BackendId): BackendCompatibility? =
        inspection.compatibility.firstOrNull { it.backend == backend }
}

/** Outcome of planning an import against the current library. */
sealed class ImportDecision {
    data class Proceed(val duplicateWarnings: List<String>) : ImportDecision()

    /** Same content already imported (identical SHA-256 + size). */
    data class Duplicate(val existingId: String, val reason: String) : ImportDecision()

    /** Unrecognised format: import is refused for execution purposes. */
    data class UnsupportedFormat(val reason: String) : ImportDecision()
}

object ImportPlanner {

    /**
     * Decide whether a file should be imported. Identical content (hash + size)
     * is a hard duplicate; same original name + size is a soft warning only —
     * user files are never deleted or touched by this app.
     */
    fun plan(
        originalFileName: String,
        sizeBytes: Long,
        sha256: String?,
        inspection: ModelInspection,
        existing: List<ModelDescriptor>,
    ): ImportDecision {
        if (inspection.format == ModelFormat.UNKNOWN) {
            return ImportDecision.UnsupportedFormat(
                "Unrecognised model format. This app only accepts model files it can " +
                    "identify; unknown files are not stored as executable models."
            )
        }

        val hash = sha256?.lowercase()
        val hardDuplicate = existing.firstOrNull {
            it.sha256 != null && hash != null && it.sha256.lowercase() == hash && it.sizeBytes == sizeBytes
        }
        if (hardDuplicate != null) {
            return ImportDecision.Duplicate(
                existingId = hardDuplicate.id,
                reason = "Identical content already imported as '${hardDuplicate.displayName}' " +
                    "(same SHA-256 and size $sizeBytes bytes)",
            )
        }

        val warnings = mutableListOf<String>()
        val softDuplicate = existing.firstOrNull {
            it.originalFileName.equals(originalFileName, ignoreCase = true) && it.sizeBytes == sizeBytes
        }
        if (softDuplicate != null) {
            warnings += "A model with the same file name and size ('${softDuplicate.displayName}') " +
                "already exists; imported anyway because contents could not be proven identical"
        }
        if (inspection.format.isWeightsBundle) {
            warnings += "This file is a weights bundle. Importing it does not mean any " +
                "engine in this build can execute it — see the compatibility table."
        }
        return ImportDecision.Proceed(warnings)
    }
}

/** JSON (de)serialization for [ModelDescriptor] used by the library index. */
object ModelDescriptorJson {

    const val SCHEMA_VERSION = 1

    fun encode(descriptor: ModelDescriptor): JsonValue.JsonObject {
        val b = JsonObjectBuilder()
        b.put("schemaVersion", SCHEMA_VERSION)
        b.put("id", descriptor.id)
        b.put("displayName", descriptor.displayName)
        b.put("originalFileName", descriptor.originalFileName)
        b.put("sizeBytes", descriptor.sizeBytes)
        b.put("sha256", descriptor.sha256)
        b.put("importedAtEpochMs", descriptor.importedAtEpochMs)
        b.put("note", descriptor.note)

        val insp = JsonObjectBuilder()
        insp.put("format", descriptor.inspection.format.name)
        insp.put("magicConfirmed", descriptor.inspection.magicConfirmed)
        insp.put("weightBytes", descriptor.inspection.weightBytes)
        insp.put("estimatedMemoryBytes", descriptor.inspection.estimatedMemoryBytes)
        insp.put("tensorCount", descriptor.inspection.tensorCount)
        insp.put("isSinglePipelineFile", descriptor.inspection.isSinglePipelineFile)
        insp.putStrings("formatWarnings", descriptor.inspection.formatWarnings)
        insp.putStrings("inspectionWarnings", descriptor.inspection.inspectionWarnings)

        insp.put("tensors", JsonValue.JsonArray(descriptor.inspection.tensors.map { t ->
            val tb = JsonObjectBuilder()
            tb.put("name", t.name)
            tb.put("dtype", t.dtype)
            tb.put("shape", JsonValue.JsonArray(t.shape.map { JsonValue.JsonNumber(it.toDouble()) }))
            tb.put("dataBytes", t.dataBytes)
            tb.build()
        }))
        insp.put("components", JsonValue.JsonArray(descriptor.inspection.components.map { c ->
            val cb = JsonObjectBuilder()
            cb.put("component", c.component.name)
            cb.put("tensorCount", c.tensorCount)
            cb.put("bytes", c.bytes)
            cb.build()
        }))
        insp.put("compatibility", JsonValue.JsonArray(descriptor.inspection.compatibility.map { c ->
            val cb = JsonObjectBuilder()
            cb.put("backend", c.backend.name)
            cb.put("status", c.status.name)
            cb.put("reason", c.reason)
            cb.build()
        }))
        b.put("inspection", insp.build())
        return b.build()
    }

    fun decode(obj: JsonValue.JsonObject): ModelDescriptor {
        val id = obj.getString("id") ?: throw IllegalArgumentException("Model record missing id")
        val inspObj = obj.getObject("inspection")
            ?: throw IllegalArgumentException("Model record missing inspection")

        val formatName = inspObj.getString("format")
            ?: throw IllegalArgumentException("Model record missing inspection.format")
        val format = ModelFormat.entries.firstOrNull { it.name == formatName } ?: ModelFormat.UNKNOWN

        val tensors = inspObj.getArray("tensors")?.items?.map { item ->
            val t = item as? JsonValue.JsonObject ?: throw IllegalArgumentException("Bad tensor record")
            TensorInfo(
                name = t.getString("name") ?: "",
                dtype = t.getString("dtype") ?: "UNKNOWN",
                shape = t.getArray("shape")?.items?.map {
                    (it as? JsonValue.JsonNumber)?.value?.toLong() ?: 0L
                } ?: emptyList(),
                dataBytes = t.getLong("dataBytes") ?: 0L,
            )
        } ?: emptyList()

        val components = inspObj.getArray("components")?.items?.map { item ->
            val c = item as? JsonValue.JsonObject ?: throw IllegalArgumentException("Bad component record")
            ComponentHint(
                component = PipelineComponent.entries.firstOrNull { it.name == c.getString("component") }
                    ?: PipelineComponent.OTHER,
                tensorCount = c.getInt("tensorCount") ?: 0,
                bytes = c.getLong("bytes") ?: 0L,
            )
        } ?: emptyList()

        val compatibility = inspObj.getArray("compatibility")?.items?.map { item ->
            val c = item as? JsonValue.JsonObject ?: throw IllegalArgumentException("Bad compatibility record")
            BackendCompatibility(
                backend = BackendId.entries.firstOrNull { it.name == c.getString("backend") }
                    ?: throw IllegalArgumentException("Unknown backend in record"),
                status = CompatibilityStatus.entries.firstOrNull { it.name == c.getString("status") }
                    ?: CompatibilityStatus.UNKNOWN,
                reason = c.getString("reason") ?: "",
            )
        } ?: emptyList()

        val inspection = ModelInspection(
            format = format,
            magicConfirmed = inspObj.getBool("magicConfirmed") ?: false,
            weightBytes = inspObj.getLong("weightBytes") ?: 0L,
            estimatedMemoryBytes = inspObj.getLong("estimatedMemoryBytes"),
            tensorCount = inspObj.getInt("tensorCount") ?: tensors.size,
            tensors = tensors,
            components = components,
            formatWarnings = inspObj.getStringList("formatWarnings") ?: emptyList(),
            inspectionWarnings = inspObj.getStringList("inspectionWarnings") ?: emptyList(),
            compatibility = compatibility,
            isSinglePipelineFile = inspObj.getBool("isSinglePipelineFile") ?: false,
        )

        return ModelDescriptor(
            id = id,
            displayName = obj.getString("displayName") ?: "Unnamed model",
            originalFileName = obj.getString("originalFileName") ?: "",
            sizeBytes = obj.getLong("sizeBytes") ?: 0L,
            sha256 = obj.getString("sha256"),
            importedAtEpochMs = obj.getLong("importedAtEpochMs") ?: 0L,
            inspection = inspection,
            note = obj.getString("note"),
        )
    }

    fun encodeIndex(models: List<ModelDescriptor>): JsonValue.JsonObject {
        val b = JsonObjectBuilder()
        b.put("schemaVersion", SCHEMA_VERSION)
        b.put("models", JsonValue.JsonArray(models.map { encode(it) }))
        return b.build()
    }

    fun decodeIndex(obj: JsonValue.JsonObject): List<ModelDescriptor> =
        obj.getArray("models")?.items?.map {
            decode(it as? JsonValue.JsonObject ?: throw IllegalArgumentException("Bad model record"))
        } ?: emptyList()
}
