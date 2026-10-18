package com.slop4hexagon

import com.slop4hexagon.domain.benchmark.BenchmarkAggregator
import org.junit.Assert.*
import org.junit.Test

class BenchmarkAggregationTest {
    @Test
    fun `median odd`() {
        assertEquals(20L, BenchmarkAggregator.median(listOf(10, 20, 30)))
        assertEquals(15L, BenchmarkAggregator.median(listOf(15)))
    }

    @Test
    fun `median even averages`() {
        assertEquals(25L, BenchmarkAggregator.median(listOf(10, 20, 30, 40))) // (20+30)/2=25
        assertEquals(15L, BenchmarkAggregator.median(listOf(10, 20))) // (10+20)/2=15
    }

    @Test
    fun `median empty null`() {
        assertNull(BenchmarkAggregator.median(emptyList()))
    }

    @Test
    fun `median handles unsorted`() {
        assertEquals(20L, BenchmarkAggregator.median(listOf(30, 10, 20)))
    }

    @Test
    fun `min max`() {
        assertEquals(5L, BenchmarkAggregator.min(listOf(5, 10, 15)))
        assertEquals(15L, BenchmarkAggregator.max(listOf(5, 10, 15)))
        assertNull(BenchmarkAggregator.min(emptyList()))
    }

    @Test
    fun `summarize`() {
        val (median, min, max) = BenchmarkAggregator.summarize(listOf(10, 20, 30, 40, 50))
        assertEquals(30L, median)
        assertEquals(10L, min)
        assertEquals(50L, max)
    }

    @Test
    fun `toDisplayMs`() {
        assertEquals("10.0 ms", BenchmarkAggregator.toDisplayMs(10_000_000))
        assertEquals("—", BenchmarkAggregator.toDisplayMs(null))
    }
}
