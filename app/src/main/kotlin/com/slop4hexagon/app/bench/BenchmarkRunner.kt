package com.slop4hexagon.app.bench

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.slop4hexagon.app.backend.BackendManagerHolder
import com.slop4hexagon.app.data.BenchmarkIndexStore
import com.slop4hexagon.app.platform.DeviceInfo
import com.slop4hexagon.app.platform.ImageStore
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.backend.InferenceBackend
import com.slop4hexagon.core.backend.InferenceError
import com.slop4hexagon.core.backend.InferenceRequest
import com.slop4hexagon.core.backend.InferenceResult
import com.slop4hexagon.core.benchmark.BenchmarkAggregator
import com.slop4hexagon.core.benchmark.BenchmarkConfig
import com.slop4hexagon.core.benchmark.BenchmarkMode
import com.slop4hexagon.core.benchmark.BenchmarkReport
import com.slop4hexagon.core.benchmark.BenchmarkReportJson
import com.slop4hexagon.core.benchmark.MicroOpPlans
import com.slop4hexagon.core.benchmark.PhaseTimings
import com.slop4hexagon.core.model.ModelDescriptor
import com.slop4hexagon.core.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Executes benchmark runs and records only what was actually measured.
 *
 * Rules enforced here (they come from the product requirements, not taste):
 *  * every duration comes from [SystemClock.elapsedRealtimeNanos], a monotonic
 *    clock that ignores wall-clock changes;
 *  * cold start (first touch of the runtime) and initialization are timed and
 *    reported *separately* from steady-state inference;
 *  * warm-up runs execute but never enter the latency statistics;
 *  * a run that cannot happen returns a report with `failures` and *no* stats
 *    object, so nothing fabricated can be exported;
 *  * the backend's status is sampled before and after, so a fallback or a
 *    failure to reach [BackendStatus.VERIFIED_EXECUTION] is visible in the report.
 */
class BenchmarkRunner(
    private val context: Context,
    private val holder: BackendManagerHolder,
    private val indexStore: BenchmarkIndexStore,
    private val appVersion: String,
    private val scope: CoroutineScope,
) {

    data class Progress(
        val phase: Phase,
        val iteration: Int,
        val totalIterations: Int,
        val lastLatencyMillis: Double?,
    )

    enum class Phase { PREPARING, WARMUP, MEASURING, POST_PROCESSING, DONE }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    @Volatile
    var cancelRequested: Boolean = false

    /** Set by [failureReport] so only real runs (not argument errors) get stored. */
    @Volatile
    private var persistFailed: Boolean = false

    fun requestCancel() {
        cancelRequested = true
    }

    /**
     * @param engineProvider supplies the backend to benchmark; separated so the
     * caller can pre-initialize it (which is itself part of cold-start timing).
     */
    suspend fun run(
        config: BenchmarkConfig,
        model: ModelDescriptor?,
        settings: AppSettings,
    ): BenchmarkReport = withContext(Dispatchers.Default) {
        cancelRequested = false
        persistFailed = false
        val validation = config.validate()
        val backend: InferenceBackend? = holder.currentBackend()
        val notes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        if (validation.isNotEmpty()) {
            return@withContext failureReport(config, backend, model, validation + "config validation failed", notes)
        }
        val statusBefore = backend?.status() ?: BackendStatus.UNAVAILABLE
        val capabilityCheck = holder.canRun(config, settings)
        if (!capabilityCheck.runnable) {
            notes += capabilityCheck.explanation
            notes += "Prerequisite: an engine that implements ${config.mode} for the installed model format"
            return@withContext failureReport(
                config,
                backend,
                model,
                listOf("benchmark cannot run: ${capabilityCheck.explanation}"),
                notes,
            )
        }
        val chosen = backend!!

        // ---- cold start: first time this process touches the engine ----------
        _progress.value = Progress(Phase.PREPARING, 0, 0, null)
        val coldStartBegin = SystemClock.elapsedRealtimeNanos()
        val needsInit = chosen.status() == BackendStatus.AVAILABLE
        val initOutcome = if (needsInit) chosen.initialize() else null
        val coldStartEnd = SystemClock.elapsedRealtimeNanos()
        val coldStartNanos = coldStartEnd - coldStartBegin
        if (initOutcome is com.slop4hexagon.core.backend.InitOutcome.Failure) {
            failures += "backend initialization failed: ${initOutcome.detail}"
            return@withContext failureReport(config, chosen, model, failures.toList(), notes, coldStartNanos)
        }

        // ---- warm-up: executed, timed separately, excluded from stats -------
        val warmupNanos = ArrayList<Long>(config.warmupIterations)
        for (i in 0 until config.warmupIterations) {
            coroutineContext.ensureActive()
            if (cancelRequested) throw CancellationException("cancelled by user")
            _progress.value = Progress(Phase.WARMUP, i + 1, config.warmupIterations, null)
            val started = SystemClock.elapsedRealtimeNanos()
            val result = executeOnce(config, chosen)
            val took = SystemClock.elapsedRealtimeNanos() - started
            warmupNanos += took
            if (result is InferenceResult.Failure) {
                failures += "warm-up run ${i + 1} failed: ${result.error.describe()}"
                return@withContext failureReport(
                    config, chosen, model, failures.toList(), notes, coldStartNanos, warmupNanos,
                )
            }
            // A warm-up that produced output proves execution, but steady-state
            // numbers are what the report's stats describe.
            delay(WARMUP_GAP_MS)
        }

        // ---- measured runs --------------------------------------------------
        val inferenceNanos = ArrayList<Long>(config.measuredIterations)
        val digests = ArrayList<Double>(config.measuredIterations)
        for (i in 0 until config.measuredIterations) {
            coroutineContext.ensureActive()
            if (cancelRequested) throw CancellationException("cancelled by user")
            _progress.value = Progress(Phase.MEASURING, i + 1, config.measuredIterations, null)
            val started = SystemClock.elapsedRealtimeNanos()
            val result = executeOnce(config, chosen)
            val took = SystemClock.elapsedRealtimeNanos() - started
            when (result) {
                is InferenceResult.MicroOpSuccess -> {
                    inferenceNanos += took
                    digests += result.checksum
                    _progress.value = Progress(Phase.MEASURING, i + 1, config.measuredIterations, took / 1e6)
                }
                is InferenceResult.Failure -> {
                    failures += "measured run ${i + 1} failed: ${result.error.describe()}"
                    _progress.value = Progress(Phase.MEASURING, i + 1, config.measuredIterations, took / 1e6)
                }
                is InferenceResult.ImageSuccess -> {
                    inferenceNanos += took
                    _progress.value = Progress(Phase.MEASURING, i + 1, config.measuredIterations, took / 1e6)
                }
            }
        }

        if (inferenceNanos.isEmpty()) {
            failures += "no measured run completed successfully"
            return@withContext failureReport(
                config, chosen, model, failures.toList(), notes, coldStartNanos, warmupNanos, emptyList(),
            )
        }

        _progress.value = Progress(Phase.POST_PROCESSING, 0, 0, null)
        val stats = BenchmarkAggregator.stats(inferenceNanos)
        notes += "warm-up excluded from statistics: ${warmupNanos.size} run(s) took " +
            warmupNanos.joinToString(", ") { "%.3f ms".format(it / 1e6) }
        if (digests.isNotEmpty() && digests.distinct().size > 1) {
            notes += "WARNING: ${digests.distinct().size} distinct output digests across measured runs of the " +
                "same fixed-shape op; results are not reproducible on this backend"
        } else if (digests.isNotEmpty()) {
            notes += "all measured runs produced the identical output digest (${fmtDigest(digests[0])}): " +
                "the op demonstrably computed the same result each time"
        }
        notes += "timing source: SystemClock.elapsedRealtimeNanos(); config ${config.mode} " +
            "${config.rows}x${config.inner}x${config.cols} op=${config.op}"

        val report = BenchmarkReport(
            id = "bench-${System.currentTimeMillis()}",
            createdAtEpochMs = System.currentTimeMillis(),
            mode = config.mode,
            config = config,
            backendId = chosen.id,
            backendDisplayName = chosen.displayName,
            backendStatusAtStart = statusBefore,
            backendStatusAtEnd = chosen.status(),
            modelId = model?.id,
            modelName = model?.displayName,
            phases = PhaseTimings(
                coldStartNanos = coldStartNanos,
                initNanos = if (needsInit) coldStartNanos else null,
                warmupNanos = warmupNanos,
                inferenceNanos = inferenceNanos,
            ),
            stats = stats,
            memory = DeviceInfo.memorySnapshot(null),
            thermal = DeviceInfo.thermalSnapshot(context),
            device = DeviceInfo.report(context, appVersion, "ART (Android ${android.os.Build.VERSION.RELEASE})"),
            executionVerified = chosen.status() == BackendStatus.VERIFIED_EXECUTION,
            failures = failures.toList(),
            notes = notes.toList(),
        )
        indexStore.update { list -> (list + report).takeLast(MAX_STORED) }
        _progress.value = Progress(Phase.DONE, 0, 0, null)
        _progress.value = null
        report
    }

    private suspend fun executeOnce(config: BenchmarkConfig, backend: InferenceBackend): InferenceResult {
        val request = when (config.mode) {
            BenchmarkMode.MICRO_OP -> {
                val op = config.op ?: return InferenceResult.Failure(InferenceError.InvalidRequest("no op configured"))
                val plan = MicroOpPlans.plan(op, config.rows, config.inner, config.cols)
                val a = FloatArray(plan.inputAElements)
                val b = FloatArray(plan.inputBElements)
                MicroOpPlans.fillDeterministic(a, seedFor(config, 0))
                MicroOpPlans.fillDeterministic(b, seedFor(config, 1))
                InferenceRequest.MicroOpRequest(op, config.rows, config.inner, config.cols, a, b)
            }
            BenchmarkMode.FULL_PIPELINE -> InferenceRequest.ImagePipelineRequest(
                modelId = config.modelId ?: "",
                mode = InferenceRequest.ImageGenMode.TEXT_TO_IMAGE,
                prompt = "benchmark prompt",
                negativePrompt = null,
                width = config.width,
                height = config.height,
                steps = config.steps,
                seed = config.seed,
                denoisingStrength = config.denoisingStrength,
                sourceImage = null,
            )
        }
        return backend.execute(request)
    }

    private fun seedFor(config: BenchmarkConfig, lane: Int): Long =
        (config.op?.ordinal?.toLong() ?: 0L) * 1_000_003L +
            config.rows * 31L + config.inner * 131L + config.cols * 1031L + lane * 7919L + config.seed

    private suspend fun failureReport(
        config: BenchmarkConfig,
        backend: InferenceBackend?,
        model: ModelDescriptor?,
        failures: List<String>,
        notes: List<String>,
        coldStartNanos: Long? = null,
        warmupNanos: List<Long> = emptyList(),
        inferenceNanos: List<Long>? = null,
    ): BenchmarkReport {
        // Failed runs are stored as well: "backend initialization failed" is a
        // first-class benchmark result the user must be able to inspect/export.
        persistFailed = true
        val status = backend?.status() ?: BackendStatus.UNAVAILABLE
        val report = BenchmarkReport(
            id = "bench-${System.currentTimeMillis()}",
            createdAtEpochMs = System.currentTimeMillis(),
            mode = config.mode,
            config = config,
            backendId = backend?.id ?: com.slop4hexagon.core.backend.BackendId.CPU,
            backendDisplayName = backend?.displayName ?: "no backend",
            backendStatusAtStart = status,
            backendStatusAtEnd = status,
            modelId = model?.id,
            modelName = model?.displayName,
            phases = PhaseTimings(coldStartNanos, null, warmupNanos, inferenceNanos ?: emptyList()),
            stats = null,
            memory = null,
            thermal = null,
            device = null,
            executionVerified = false,
            failures = failures,
            notes = notes + "no latency statistics: this run never completed a measured inference",
        )
        if (persistFailed) {
            indexStore.update { list -> (list + report).takeLast(MAX_STORED) }
        }
        return report
    }

    private fun fmtDigest(value: Double): String = "%.6e".format(value)

    companion object {
        const val MAX_STORED = 200
        private const val WARMUP_GAP_MS = 40L

        fun toJson(report: BenchmarkReport): String = BenchmarkReportJson.encodeToString(report)

        fun exportTo(context: Context, target: Uri, report: BenchmarkReport): Result<Unit> = runCatching {
            ImageStore.writeBytes(context, target, toJson(report).toByteArray())
        }
    }
}
