package com.slop4hexagon.domain.benchmark

import kotlinx.serialization.Serializable

enum class BenchmarkMode { MICRO, FULL_PIPELINE }

@Serializable
data class BenchmarkConfig(
    val modelId: String? = null,
    val backendId: String,
    val mode: String, // MICRO or FULL_PIPELINE
    val warmupIterations: Int = 1,
    val measuredIterations: Int = 5,
    val inputWidth: Int = 512,
    val inputHeight: Int = 512
)

@Serializable
data class BenchmarkResult(
    val id: String,
    val config: BenchmarkConfig,
    val timestampMillis: Long,
    val deviceInfo: String? = null,
    val coldStartNanos: Long? = null,
    val initNanos: Long? = null,
    val warmupNanos: List<Long> = emptyList(),
    val inferenceNanos: List<Long> = emptyList(),
    val medianNanos: Long? = null,
    val minNanos: Long? = null,
    val maxNanos: Long? = null,
    val peakMemoryBytes: Long? = null,
    val thermalStatusBefore: Int? = null,
    val thermalStatusAfter: Int? = null,
    val error: String? = null,
    val backendStatus: String,
    val backendVerified: Boolean = false
)

object BenchmarkAggregator {
    /**
     * Median via sorted copy; handles even count by averaging middle two (integer division).
     * Uses elapsedRealtimeNanos timing; ignores warmup in median.
     */
    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    fun min(values: List<Long>): Long? = values.minOrNull()
    fun max(values: List<Long>): Long? = values.maxOrNull()

    fun summarize(inferenceNanos: List<Long>): Triple<Long?, Long?, Long?> {
        return Triple(median(inferenceNanos), min(inferenceNanos), max(inferenceNanos))
    }

    fun toDisplayMs(nanos: Long?): String {
        if (nanos == null) return "—"
        return String.format("%.1f ms", nanos / 1_000_000.0)
    }
}
