package com.slop4hexagon.core.benchmark

import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.backend.OpKind

/**
 * Timing samples are taken with a monotonic clock
 * (`SystemClock.elapsedRealtimeNanos()` on Android, `System.nanoTime()` on the
 * JVM). Values are durations in nanoseconds; they are never wall-clock based.
 */
enum class BenchmarkMode {
    /** Fixed-shape single-operation timing (backend microbenchmark). */
    MICRO_OP,

    /** End-to-end pipeline timing (requires an installed pipeline engine). */
    FULL_PIPELINE,
}

data class BenchmarkConfig(
    val mode: BenchmarkMode,
    /** Warm-up runs executed and recorded separately; default 1. */
    val warmupIterations: Int = DEFAULT_WARMUP,
    /** Steady-state measured runs; default 5. */
    val measuredIterations: Int = DEFAULT_MEASURED,
    /** Fixed-shape operation for [BenchmarkMode.MICRO_OP]. */
    val op: OpKind? = null,
    val rows: Int = 0,
    val inner: Int = 0,
    val cols: Int = 0,
    /** Model id for [BenchmarkMode.FULL_PIPELINE]. */
    val modelId: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val steps: Int = 0,
    val seed: Long = 0,
    val denoisingStrength: Double? = null,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (warmupIterations < 0 || warmupIterations > MAX_ITERATIONS) {
            errors += "warmupIterations must be within 0..$MAX_ITERATIONS"
        }
        if (measuredIterations < 1 || measuredIterations > MAX_ITERATIONS) {
            errors += "measuredIterations must be within 1..$MAX_ITERATIONS"
        }
        when (mode) {
            BenchmarkMode.MICRO_OP -> {
                if (op == null) errors += "MICRO_OP mode requires an operation"
                if (rows <= 0 || inner <= 0 || cols <= 0) {
                    errors += "MICRO_OP mode requires positive rows/inner/cols"
                }
                if (rows > MAX_DIM || inner > MAX_DIM || cols > MAX_DIM) {
                    errors += "Each dimension must be <= $MAX_DIM"
                }
            }
            BenchmarkMode.FULL_PIPELINE -> {
                if (modelId.isNullOrBlank()) errors += "FULL_PIPELINE mode requires a modelId"
                if (width <= 0 || height <= 0) errors += "FULL_PIPELINE mode requires positive width/height"
                if (steps <= 0) errors += "FULL_PIPELINE mode requires a positive step count"
                if (denoisingStrength != null && (denoisingStrength < 0.0 || denoisingStrength > 1.0)) {
                    errors += "denoisingStrength must be within 0..1"
                }
            }
        }
        return errors
    }

    companion object {
        const val DEFAULT_WARMUP = 1
        const val DEFAULT_MEASURED = 5
        const val MAX_ITERATIONS = 1000
        const val MAX_DIM = 4096

        fun defaultMicroOp(op: OpKind): BenchmarkConfig =
            BenchmarkConfig(
                mode = BenchmarkMode.MICRO_OP,
                op = op,
                rows = 64,
                inner = 256,
                cols = 64,
            )
    }
}

/** Per-phase durations. All values in nanoseconds. */
data class PhaseTimings(
    /** Time until the runtime/library is first touched (cold start). */
    val coldStartNanos: Long?,
    /** Model/backend initialization time. */
    val initNanos: Long?,
    val warmupNanos: List<Long>,
    val inferenceNanos: List<Long>,
)

data class LatencyStats(
    val count: Int,
    val minNanos: Double,
    val medianNanos: Double,
    val maxNanos: Double,
    val meanNanos: Double,
    val p95Nanos: Double,
)

object BenchmarkAggregator {

    /** Statistics over steady-state samples. Throws on empty input. */
    fun stats(samples: List<Long>): LatencyStats {
        require(samples.isNotEmpty()) { "Cannot aggregate an empty sample list" }
        val sorted = samples.sorted()
        val n = sorted.size
        val min = sorted.first().toDouble()
        val max = sorted.last().toDouble()
        val mean = sorted.sumOf { it.toDouble() } / n
        val median = if (n % 2 == 1) {
            sorted[n / 2].toDouble()
        } else {
            (sorted[n / 2 - 1].toDouble() + sorted[n / 2].toDouble()) / 2.0
        }
        val p95Index = kotlin.math.ceil(0.95 * n).toInt().coerceIn(1, n) - 1
        val p95 = sorted[p95Index].toDouble()
        return LatencyStats(count = n, minNanos = min, medianNanos = median, maxNanos = max, meanNanos = mean, p95Nanos = p95)
    }
}

data class MemorySnapshot(
    val javaHeapUsedBytes: Long?,
    val nativeHeapUsedBytes: Long?,
    val totalPssBytes: Long?,
    val peakRssBytes: Long?,
    val note: String = "Peak values are sampled, not guaranteed maxima",
)

data class ThermalSnapshot(
    /** PowerManager.getCurrentThermalStatus() when available. */
    val thermalStatus: Int?,
    val thermalStatusName: String?,
    val note: String = "Reported by Android; sampling is point-in-time",
)

data class DeviceReport(
    val socModel: String?,
    val hardware: String?,
    val supportedAbis: List<String>,
    val sdkInt: Int,
    val androidRelease: String,
    val manufacturer: String?,
    val model: String?,
    val totalMemBytes: Long?,
    val availMemBytes: Long?,
    val appVersion: String,
    val runtimeDescription: String,
)

data class BenchmarkReport(
    val id: String,
    val createdAtEpochMs: Long,
    val mode: BenchmarkMode,
    val config: BenchmarkConfig,
    val backendId: BackendId,
    val backendDisplayName: String,
    val backendStatusAtStart: BackendStatus,
    val backendStatusAtEnd: BackendStatus,
    /** Set when a full-pipeline benchmark ran against an imported model. */
    val modelId: String?,
    val modelName: String?,
    val phases: PhaseTimings,
    /** Steady-state inference statistics, null when no measured run completed. */
    val stats: LatencyStats?,
    val memory: MemorySnapshot?,
    val thermal: ThermalSnapshot?,
    val device: DeviceReport?,
    /** True when [BackendStatus.VERIFIED_EXECUTION] was earned by this run. */
    val executionVerified: Boolean,
    val failures: List<String>,
    val notes: List<String>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class ComparisonRow(
    val metric: String,
    val left: String,
    val right: String,
    val deltaPercent: Double?,
)

data class ComparisonSummary(
    val leftId: String,
    val rightId: String,
    val rows: List<ComparisonRow>,
    val verdict: String,
)

object BenchmarkComparator {

    fun compare(left: BenchmarkReport, right: BenchmarkReport): ComparisonSummary {
        val rows = mutableListOf<ComparisonRow>()

        fun addLatencyRow(metric: String, a: Double?, b: Double?) {
            if (a == null || b == null) {
                rows += ComparisonRow(metric, a?.let { fmt(it) } ?: "n/a", b?.let { fmt(it) } ?: "n/a", null)
                return
            }
            val delta = if (a > 0) (b - a) / a * 100.0 else null
            rows += ComparisonRow(metric, fmt(a), fmt(b), delta)
        }

        addLatencyRow("Median latency", left.stats?.medianNanos, right.stats?.medianNanos)
        addLatencyRow("Min latency", left.stats?.minNanos, right.stats?.minNanos)
        addLatencyRow("Max latency", left.stats?.maxNanos, right.stats?.maxNanos)
        addLatencyRow("Cold start", left.phases.coldStartNanos?.toDouble(), right.phases.coldStartNanos?.toDouble())
        addLatencyRow("Init", left.phases.initNanos?.toDouble(), right.phases.initNanos?.toDouble())

        val verdict = buildString {
            val a = left.stats?.medianNanos
            val b = right.stats?.medianNanos
            if (a != null && b != null && a > 0 && b > 0) {
                when {
                    b < a -> append("Right run is faster (median x${"%.2f".format(a / b)})")
                    b > a -> append("Left run is faster (median x${"%.2f".format(b / a)})")
                    else -> append("Median latencies are identical")
                }
            } else {
                append("Not enough measured samples to compare")
            }
            if (left.backendId != right.backendId) {
                append(" across different backends (${left.backendId} vs ${right.backendId})")
            }
        }

        return ComparisonSummary(left.id, right.id, rows, verdict)
    }

    private fun fmt(nanos: Double): String = "%.3f ms".format(nanos / 1_000_000.0)
}
