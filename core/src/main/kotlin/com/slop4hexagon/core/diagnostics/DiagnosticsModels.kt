package com.slop4hexagon.core.diagnostics

import com.slop4hexagon.core.backend.BackendStatusInfo
import com.slop4hexagon.core.json.JsonObjectBuilder
import com.slop4hexagon.core.json.JsonValue

/**
 * Device/runtime facts collected through public Android APIs. Every field is
 * nullable where the underlying API can legitimately be absent (e.g.
 * `Build.SOC_MODEL` is API 31+ and may still return an empty string).
 *
 * [hexagonVerified] must only be true when a real inference has been executed
 * and validated on the HTP in this process. Merely reading `Build.HARDWARE`
 * or initializing QNN is NOT verification.
 */
data class DeviceSnapshot(
    val socModel: String?,
    val hardware: String?,
    val supportedAbis: List<String>,
    val sdkInt: Int,
    val androidRelease: String,
    val manufacturer: String?,
    val brand: String?,
    val model: String?,
    val totalMemBytes: Long?,
    val availMemBytes: Long?,
    val appVersion: String,
    val backendStatuses: List<BackendStatusInfo>,
    val hexagonVerified: Boolean,
    val notes: List<String>,
) {

    fun toJsonObject(): JsonValue.JsonObject {
        val b = JsonObjectBuilder()
        b.put("schemaVersion", SCHEMA_VERSION)
        b.put("socModel", socModel)
        b.put("hardware", hardware)
        b.putStrings("supportedAbis", supportedAbis)
        b.put("sdkInt", sdkInt)
        b.put("androidRelease", androidRelease)
        b.put("manufacturer", manufacturer)
        b.put("brand", brand)
        b.put("model", model)
        b.put("totalMemBytes", totalMemBytes)
        b.put("availMemBytes", availMemBytes)
        b.put("appVersion", appVersion)
        b.put("hexagonVerified", hexagonVerified)
        b.put("backends", JsonValue.JsonArray(backendStatuses.map { s ->
            val sb = JsonObjectBuilder()
            sb.put("id", s.id.name)
            sb.put("displayName", s.displayName)
            sb.put("status", s.status.name)
            sb.put("detail", s.detail)
            sb.put("fallback", s.fallback)
            sb.put("lastError", s.lastError)
            sb.put("capabilities", s.capabilities?.let { c ->
                val cb = JsonObjectBuilder()
                cb.putStrings("supportedOps", c.supportedOps.map { it.name })
                cb.put("supportsMicroBenchmarks", c.supportsMicroBenchmarks)
                cb.put("supportsImagePipeline", c.supportsImagePipeline)
                cb.put("maxTensorElements", c.maxTensorElements)
                cb.putStrings("notes", c.notes)
                cb.build()
            })
            sb.build()
        }))
        b.putStrings("notes", notes)
        return b.build()
    }

    fun toPrettyJson(): String = toJsonObject().toPrettyString()

    companion object {
        const val SCHEMA_VERSION = 1

        /** Standard disclaimer appended to every export. */
        val STANDARD_NOTES = listOf(
            "Device identification uses public Android APIs only. " +
                "Build.SOC_MODEL and Build.HARDWARE describe the SoC, NOT the Hexagon " +
                "architecture version; no Hexagon/HTP version is confirmed unless " +
                "hexagonVerified is true.",
            "Missing or restricted fields are reported as null and are normal on " +
                "older Android versions or restricted builds.",
        )
    }
}
