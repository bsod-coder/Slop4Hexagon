package com.slop4hexagon.core.benchmark

import com.slop4hexagon.core.backend.OpKind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkAggregatorTest {

    @Test
    fun medianOfOddCountIsMiddleValue() {
        val stats = BenchmarkAggregator.stats(listOf(30L, 10L, 20L))
        assertEquals(20.0, stats.medianNanos, 0.0)
        assertEquals(10.0, stats.minNanos, 0.0)
        assertEquals(30.0, stats.maxNanos, 0.0)
        assertEquals(20.0, stats.meanNanos, 0.0001)
        assertEquals(3, stats.count)
    }

    @Test
    fun medianOfEvenCountIsMeanOfMiddlePair() {
        val stats = BenchmarkAggregator.stats(listOf(40L, 10L, 20L, 30L))
        assertEquals(25.0, stats.medianNanos, 0.0)
    }

    @Test
    fun minMaxAndMeanUseAllSamples() {
        val samples = listOf(5L, 5L, 5L, 5L, 100L)
        val stats = BenchmarkAggregator.stats(samples)
        assertEquals(5.0, stats.minNanos, 0.0)
        assertEquals(100.0, stats.maxNanos, 0.0)
        assertEquals(24.0, stats.meanNanos, 0.0001)
    }

    @Test
    fun p95UsesNearestRank() {
        // 20 samples 1..20 -> 95th percentile is the 19th value.
        val samples = (1L..20L).toList()
        val stats = BenchmarkAggregator.stats(samples)
        assertEquals(19.0, stats.p95Nanos, 0.0)
    }

    @Test
    fun singleSampleIsAllStats() {
        val stats = BenchmarkAggregator.stats(listOf(7L))
        assertEquals(7.0, stats.medianNanos, 0.0)
        assertEquals(7.0, stats.p95Nanos, 0.0)
        assertEquals(1, stats.count)
    }

    @Test
    fun unsortedInputIsAccepted() {
        val stats = BenchmarkAggregator.stats(listOf(50L, 10L, 20L))
        assertEquals(20.0, stats.medianNanos, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySamplesThrow() {
        BenchmarkAggregator.stats(emptyList())
    }

    @Test
    fun configValidationEnforcesDefaults() {
        val cfg = BenchmarkConfig(
            mode = BenchmarkMode.MICRO_OP,
            op = OpKind.MATMUL_F32,
            rows = 64,
            inner = 64,
            cols = 64,
        )
        assertEquals(BenchmarkConfig.DEFAULT_WARMUP, cfg.warmupIterations)
        assertEquals(BenchmarkConfig.DEFAULT_MEASURED, cfg.measuredIterations)
        assertTrue(cfg.validate().isEmpty())
    }

    @Test
    fun configValidationRejectsBadValues() {
        val cfg = BenchmarkConfig(
            mode = BenchmarkMode.MICRO_OP,
            warmupIterations = -1,
            measuredIterations = 0,
            op = null,
            rows = 0,
            inner = 0,
            cols = 0,
        )
        val errors = cfg.validate()
        assertTrue(errors.size >= 3)
    }

    @Test
    fun fullPipelineConfigRequiresModelAndSize() {
        val cfg = BenchmarkConfig(mode = BenchmarkMode.FULL_PIPELINE)
        val errors = cfg.validate()
        assertTrue(errors.any { it.contains("modelId") })
        assertTrue(errors.any { it.contains("width/height") })
        assertTrue(errors.any { it.contains("step") })
    }
}
