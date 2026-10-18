package com.slop4hexagon.core.benchmark

import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.backend.OpKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkComparatorTest {

    private fun report(id: String, median: Double, backend: BackendId = BackendId.CPU) = BenchmarkReport(
        id = id,
        createdAtEpochMs = 0,
        mode = BenchmarkMode.MICRO_OP,
        config = BenchmarkConfig(
            mode = BenchmarkMode.MICRO_OP,
            op = OpKind.MATMUL_F32,
            rows = 1,
            inner = 1,
            cols = 1,
        ),
        backendId = backend,
        backendDisplayName = backend.displayName,
        backendStatusAtStart = BackendStatus.INITIALIZED,
        backendStatusAtEnd = BackendStatus.VERIFIED_EXECUTION,
        modelId = null,
        modelName = null,
        phases = PhaseTimings(10.0.toLong(), 20.0.toLong(), emptyList(), listOf(median.toLong())),
        stats = LatencyStats(1, median, median, median, median, median),
        memory = null,
        thermal = null,
        device = null,
        executionVerified = true,
        failures = emptyList(),
        notes = emptyList(),
    )

    @Test
    fun fasterRightRunProducesNegativeDelta() {
        val summary = BenchmarkComparator.compare(report("a", 2_000_000.0), report("b", 1_000_000.0))
        val row = summary.rows.first { it.metric == "Median latency" }
        assertEquals(-50.0, row.deltaPercent!!, 0.001)
        assertTrue(summary.verdict.contains("Right run is faster"))
    }

    @Test
    fun differentBackendsAreNotedInVerdict() {
        val summary = BenchmarkComparator.compare(
            report("a", 1_000_000.0, BackendId.CPU),
            report("b", 1_000_000.0, BackendId.HTP_QNN),
        )
        assertTrue(summary.verdict.contains("different backends"))
    }

    @Test
    fun missingSamplesYieldNaNotFabricatedNumbers() {
        val left = report("a", 1.0).copy(stats = null)
        val summary = BenchmarkComparator.compare(left, report("b", 1.0))
        val row = summary.rows.first { it.metric == "Median latency" }
        assertEquals("n/a", row.left)
        assertEquals(null, row.deltaPercent)
        assertTrue(summary.verdict.contains("Not enough measured samples"))
    }
}
