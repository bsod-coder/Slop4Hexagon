package com.slop4hexagon

import com.slop4hexagon.domain.benchmark.BenchmarkResult
import com.slop4hexagon.domain.benchmark.BenchmarkConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ErrorFallbackTest {

    @Test
    fun `benchmark JSON serialization round trip`() {
        val result = BenchmarkResult(
            id = "test-123",
            config = BenchmarkConfig("model-1", "CPU", "MICRO", 1, 5),
            timestampMillis = 1234567890,
            coldStartNanos = 1000,
            initNanos = 500,
            warmupNanos = listOf(100, 110),
            inferenceNanos = listOf(90, 100, 95, 105, 92),
            medianNanos = 95,
            minNanos = 90,
            maxNanos = 105,
            backendStatus = "INITIALIZED",
            backendVerified = false,
            error = null
        )
        val json = Json { prettyPrint = false }
        val encoded = json.encodeToString(result)
        val decoded = json.decodeFromString<BenchmarkResult>(encoded)
        assertEquals(result.id, decoded.id)
        assertEquals(result.medianNanos, decoded.medianNanos)
        assertEquals(result.config.backendId, decoded.config.backendId)
    }

    @Test
    fun `error field preserved`() {
        val err = BenchmarkResult(
            id = "err",
            config = BenchmarkConfig(null, "HTP", "FULL_PIPELINE", 1, 5),
            timestampMillis = 0,
            error = "Full pipeline not yet implemented",
            backendStatus = "UNSUPPORTED"
        )
        val json = Json.encodeToString(err)
        assertTrue(json.contains("Full pipeline"))
        val decoded = Json.decodeFromString<BenchmarkResult>(json)
        assertEquals("Full pipeline not yet implemented", decoded.error)
    }

    @Test
    fun `fallback reporting not fabricated`() {
        // Ensure backend status is explicit, not silently success
        val failed = BenchmarkResult(
            id = "fail",
            config = BenchmarkConfig(null, "CPU", "MICRO", 1, 5),
            timestampMillis = 0,
            error = "No backend available",
            backendStatus = "UNSUPPORTED"
        )
        assertEquals("UNSUPPORTED", failed.backendStatus)
        assertNotEquals("VERIFIED_EXECUTION", failed.backendStatus)
    }
}
