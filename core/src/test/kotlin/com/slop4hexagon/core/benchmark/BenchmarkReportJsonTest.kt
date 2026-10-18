package com.slop4hexagon.core.benchmark

import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.backend.OpKind
import com.slop4hexagon.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkReportJsonTest {

    private fun sampleReport() = BenchmarkReport(
        id = "bench-001",
        createdAtEpochMs = 1_760_000_000_000L,
        mode = BenchmarkMode.MICRO_OP,
        config = BenchmarkConfig(
            mode = BenchmarkMode.MICRO_OP,
            warmupIterations = 1,
            measuredIterations = 5,
            op = OpKind.MATMUL_F32,
            rows = 64,
            inner = 256,
            cols = 64,
            seed = 42L,
        ),
        backendId = BackendId.CPU,
        backendDisplayName = "CPU reference engine",
        backendStatusAtStart = BackendStatus.INITIALIZED,
        backendStatusAtEnd = BackendStatus.VERIFIED_EXECUTION,
        modelId = null,
        modelName = null,
        phases = PhaseTimings(
            coldStartNanos = 12_000_000L,
            initNanos = 3_500_000L,
            warmupNanos = listOf(2_000_000L),
            inferenceNanos = listOf(1_000_000L, 1_200_000L, 1_100_000L, 1_300_000L, 1_050_000L),
        ),
        stats = LatencyStats(
            count = 5,
            minNanos = 1_000_000.0,
            medianNanos = 1_100_000.0,
            maxNanos = 1_300_000.0,
            meanNanos = 1_130_000.0,
            p95Nanos = 1_300_000.0,
        ),
        memory = MemorySnapshot(
            javaHeapUsedBytes = 8_000_000L,
            nativeHeapUsedBytes = 2_000_000L,
            totalPssBytes = 30_000_000L,
            peakRssBytes = 45_000_000L,
        ),
        thermal = ThermalSnapshot(thermalStatus = 2, thermalStatusName = "MODERATE"),
        device = DeviceReport(
            socModel = "SM7325-AF",
            hardware = "qcom",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            sdkInt = 33,
            androidRelease = "13",
            manufacturer = "Nothing",
            model = "A063",
            totalMemBytes = 8L * 1024 * 1024 * 1024,
            availMemBytes = 3L * 1024 * 1024 * 1024,
            appVersion = "0.1.0",
            runtimeDescription = "ART",
        ),
        executionVerified = true,
        failures = emptyList(),
        notes = listOf("Timing via SystemClock.elapsedRealtimeNanos"),
    )

    @Test
    fun roundTripsAllFields() {
        val report = sampleReport()
        val text = BenchmarkReportJson.encodeToString(report)
        val decoded = BenchmarkReportJson.decodeFromString(text)
        assertEquals(report, decoded)
    }

    @Test
    fun roundTripsFailuresAndNullStats() {
        val report = sampleReport().copy(
            stats = null,
            failures = listOf("run 3 failed: backend error", "run 4 failed: cancelled"),
            notes = listOf("partial run"),
            executionVerified = false,
            phases = PhaseTimings(1L, null, emptyList(), listOf(5L)),
        )
        val decoded = BenchmarkReportJson.decodeFromString(BenchmarkReportJson.encodeToString(report))
        assertEquals(report, decoded)
        assertEquals(2, decoded.failures.size)
        assertEquals(null, decoded.stats)
    }

    @Test
    fun exportedJsonIsPlainStandardJson() {
        val text = BenchmarkReportJson.encodeToString(sampleReport())
        val reparsed = JsonValue.parseObject(text)
        assertEquals(1, reparsed.getInt("schemaVersion"))
        assertEquals("bench-001", reparsed.getString("id"))
        assertEquals("CPU", reparsed.getString("backendId"))
        assertEquals(true, reparsed.getBool("executionVerified"))
        assertTrue(text.contains("medianNanos"))
        assertTrue(text.contains("SM7325-AF"))
    }

    @Test
    fun decodeToleratesMinimalDocument() {
        val minimal = """
            {"schemaVersion":1,"id":"x","createdAtEpochMs":0,"mode":"FULL_PIPELINE",
             "backendId":"HTP_QNN","backendDisplayName":"htp","backendStatusAtStart":"UNAVAILABLE",
             "backendStatusAtEnd":"UNAVAILABLE","config":{"mode":"FULL_PIPELINE"},
             "phases":{"warmupNanos":[],"inferenceNanos":[]},
             "executionVerified":false,"failures":["no pipeline engine"],"notes":[]}
        """.trimIndent()
        val decoded = BenchmarkReportJson.decodeFromString(minimal)
        assertEquals("x", decoded.id)
        assertEquals(BenchmarkMode.FULL_PIPELINE, decoded.mode)
        assertEquals(BackendId.HTP_QNN, decoded.backendId)
        assertEquals(listOf("no pipeline engine"), decoded.failures)
        assertEquals(null, decoded.stats)
    }

    @Test
    fun nanosecondPrecisionSurvivesRoundTrip() {
        val report = sampleReport().copy(
            phases = PhaseTimings(
                coldStartNanos = 9_007_199_254_740_991L, // 2^53-1, exact in double
                initNanos = 1L,
                warmupNanos = emptyList(),
                inferenceNanos = listOf(8_123_456_789L),
            ),
        )
        val decoded = BenchmarkReportJson.decodeFromString(BenchmarkReportJson.encodeToString(report))
        assertEquals(9_007_199_254_740_991L, decoded.phases.coldStartNanos)
        assertEquals(8_123_456_789L, decoded.phases.inferenceNanos[0])
    }
}
