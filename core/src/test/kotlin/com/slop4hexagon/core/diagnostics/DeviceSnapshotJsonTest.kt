package com.slop4hexagon.core.diagnostics

import com.slop4hexagon.core.backend.BackendCapabilities
import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.backend.BackendStatusInfo
import com.slop4hexagon.core.backend.OpKind
import com.slop4hexagon.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSnapshotJsonTest {

    private fun snapshot(hexagonVerified: Boolean = false) = DeviceSnapshot(
        socModel = "SM7325-AF",
        hardware = "qcom",
        supportedAbis = listOf("arm64-v8a", "x86_64"),
        sdkInt = 33,
        androidRelease = "13",
        manufacturer = "Nothing",
        brand = "Nothing",
        model = "A063",
        totalMemBytes = 8L * 1024 * 1024 * 1024,
        availMemBytes = 2_500_000_000L,
        appVersion = "0.1.0 (1)",
        backendStatuses = listOf(
            BackendStatusInfo(
                id = BackendId.CPU,
                displayName = BackendId.CPU.displayName,
                status = BackendStatus.VERIFIED_EXECUTION,
                detail = "libslop_native v1: 5 op(s) executed",
                capabilities = BackendCapabilities(
                    supportedOps = setOf(OpKind.MATMUL_F32, OpKind.CONV2D_F32),
                    supportsMicroBenchmarks = true,
                    supportsImagePipeline = false,
                    maxTensorElements = 1L shl 24,
                    notes = listOf("no pipeline engine"),
                ),
            ),
            BackendStatusInfo(
                id = BackendId.HTP_QNN,
                displayName = BackendId.HTP_QNN.displayName,
                status = BackendStatus.UNAVAILABLE,
                detail = "not loadable in this process",
                capabilities = BackendCapabilities.NONE,
            ),
        ),
        hexagonVerified = hexagonVerified,
        notes = DeviceSnapshot.STANDARD_NOTES,
    )

    @Test
    fun exportContainsFactsAndCaveats() {
        val obj = JsonValue.parseObject(snapshot().toPrettyJson())
        assertEquals(DeviceSnapshot.SCHEMA_VERSION, obj.getInt("schemaVersion"))
        assertEquals("SM7325-AF", obj.getString("socModel"))
        assertEquals(33, obj.getInt("sdkInt"))
        assertEquals(false, obj.getBool("hexagonVerified"))
        assertEquals(2, obj.getArray("backends")?.items?.size)
        val notes = obj.getStringList("notes") ?: emptyList()
        assertTrue(notes.any { it.contains("NOT the Hexagon") })
        val text = snapshot().toPrettyJson()
        assertTrue(text.contains("no pipeline engine"))
        assertTrue(text.contains("VERIFIED_EXECUTION"))
        assertTrue(text.contains("UNAVAILABLE"))
    }

    @Test
    fun nullFieldsAreSerializable() {
        val sparse = snapshot().copy(socModel = null, availMemBytes = null, hexagonVerified = true)
        val obj = JsonValue.parseObject(sparse.toPrettyJson())
        assertTrue(obj.fields.containsKey("socModel"))
        assertEquals(JsonValue.JsonNull, obj["socModel"])
        assertEquals(true, obj.getBool("hexagonVerified"))
        assertFalse(sparse.toPrettyJson().contains("\"socModel\":\""))
    }

    @Test
    fun missingMemoryInfoIsNotFatal() {
        // Only the *available* reading is missing here (ActivityManager can
        // legitimately fail for it); the total must survive untouched.
        val noAvail = snapshot().copy(availMemBytes = null)
        val obj = JsonValue.parseObject(noAvail.toPrettyJson())
        assertEquals(JsonValue.JsonNull, obj["availMemBytes"])
        assertEquals(8L * 1024 * 1024 * 1024, obj.getLong("totalMemBytes"))

        // Both missing is also fine.
        val noMem = noAvail.copy(totalMemBytes = null)
        val sparse = JsonValue.parseObject(noMem.toPrettyJson())
        assertEquals(JsonValue.JsonNull, sparse["totalMemBytes"])
        assertEquals(JsonValue.JsonNull, sparse["availMemBytes"])
    }
}
