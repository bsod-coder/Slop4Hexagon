package com.slop4hexagon.app.ui.benchmark

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slop4hexagon.app.AppContainer
import com.slop4hexagon.core.backend.BackendStatusInfo
import com.slop4hexagon.core.backend.BackendPreference
import com.slop4hexagon.core.backend.OpKind
import com.slop4hexagon.core.benchmark.BenchmarkComparator
import com.slop4hexagon.core.benchmark.BenchmarkConfig
import com.slop4hexagon.core.benchmark.BenchmarkMode
import com.slop4hexagon.core.benchmark.BenchmarkReport
import com.slop4hexagon.core.benchmark.MicroOpPlan
import com.slop4hexagon.core.benchmark.MicroOpPlans
import com.slop4hexagon.core.model.ModelDescriptor
import com.slop4hexagon.core.model.ModelNames
import com.slop4hexagon.core.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BenchmarkViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val models: List<ModelDescriptor> = emptyList(),
        val backendStatuses: List<BackendStatusInfo> = emptyList(),
        val supportedOps: Set<OpKind> = emptySet(),
        val mode: BenchmarkMode = BenchmarkMode.MICRO_OP,
        val op: OpKind = OpKind.MATMUL_F32,
        val rows: Int = 64,
        val inner: Int = 256,
        val cols: Int = 64,
        val modelId: String? = null,
        val backendPreference: BackendPreference = BackendPreference.AUTO,
        val running: Boolean = false,
        val progress: com.slop4hexagon.app.bench.BenchmarkRunner.Progress? = null,
        val lastReport: BenchmarkReport? = null,
        val history: List<BenchmarkReport> = emptyList(),
        val comparison: Pair<String, String>? = null,
        val comparisonSummary: com.slop4hexagon.core.benchmark.ComparisonSummary? = null,
        val message: String? = null,
        val exportedText: String? = null,
    ) {
        fun config(): BenchmarkConfig = when (mode) {
            BenchmarkMode.MICRO_OP -> BenchmarkConfig(
                mode = BenchmarkMode.MICRO_OP,
                warmupIterations = settings.warmupIterations,
                measuredIterations = settings.measuredIterations,
                op = op,
                rows = rows,
                inner = inner,
                cols = cols,
            )
            BenchmarkMode.FULL_PIPELINE -> BenchmarkConfig(
                mode = BenchmarkMode.FULL_PIPELINE,
                warmupIterations = settings.warmupIterations,
                measuredIterations = settings.measuredIterations,
                modelId = modelId,
                width = 512,
                height = 512,
                steps = 20,
                seed = 0,
            )
        }

        fun planOrNull(): MicroOpPlan? =
            if (rows > 0 && inner > 0 && cols > 0) runCatching { MicroOpPlans.plan(op, rows, inner, cols) }.getOrNull() else null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settings.settings.collect { s -> _state.update { it.copy(settings = s, backendPreference = s.backendPreference) } }
        }
        viewModelScope.launch {
            container.benchmarkHistory.reports.collect { list ->
                _state.update { it.copy(history = list.reversed()) }
            }
        }
        viewModelScope.launch {
            container.runner.progress.collect { p -> _state.update { it.copy(progress = p) } }
        }
        viewModelScope.launch {
            container.models.models.collect { list -> _state.update { it.copy(models = list) } }
        }
        viewModelScope.launch {
            container.backends.state.collect { backends ->
                val statuses = backends.statuses
                _state.update {
                    it.copy(
                        backendStatuses = statuses,
                        supportedOps = statuses.flatMap { s -> s.capabilities?.supportedOps ?: emptyList() }.toSet(),
                    )
                }
            }
        }
    }

    fun setMode(value: BenchmarkMode) = _state.update { it.copy(mode = value) }
    fun setOp(value: OpKind) = _state.update { it.copy(op = value) }
    fun setShape(rows: Int, inner: Int, cols: Int) = _state.update { it.copy(rows = rows, inner = inner, cols = cols) }
    fun setModel(id: String?) = _state.update { it.copy(modelId = id) }
    fun setIterations(warmup: Int, measured: Int) = viewModelScope.launch {
        container.settings.setWarmupIterations(warmup)
        container.settings.setMeasuredIterations(measured)
    }

    fun setBackendPreference(value: BackendPreference) {
        container.settings.setBackendPreference(value)
        container.backends.refresh(value)
    }

    fun capabilityExplanation(): String {
        val s = _state.value
        return container.backends.canRun(s.config(), s.settings).explanation
    }

    fun run() {
        val snapshot = _state.value
        if (snapshot.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, message = null, lastReport = null) }
            val config = snapshot.config()
            val configErrors = config.validate()
            if (configErrors.isNotEmpty()) {
                _state.update { it.copy(running = false, message = "Fix the configuration:\n- " + configErrors.joinToString("\n- ")) }
                return@launch
            }
            if (config.mode == BenchmarkMode.MICRO_OP) {
                val plan = config.op?.let { MicroOpPlans.plan(it, config.rows, config.inner, config.cols) }
                val planErrors = plan?.validate(MAX_TENSOR_ELEMENTS) ?: emptyList()
                if (planErrors.isNotEmpty()) {
                    _state.update { it.copy(running = false, message = "Fix the configuration:\n- " + planErrors.joinToString("\n- ")) }
                    return@launch
                }
                if (plan != null && !MicroOpPlans.isAllocatable(plan, MAX_ALLOC_ELEMENTS)) {
                    _state.update {
                        it.copy(
                            running = false,
                            message = "Refusing to allocate ${plan.inputAElements} + ${plan.inputBElements} elements; " +
                                "reduce the shape (device memory guard)",
                        )
                    }
                    return@launch
                }
            }
            val model = snapshot.modelId?.let { id -> container.models.models.value.firstOrNull { it.id == id } }
            try {
                val report = container.runner.run(config, model, snapshot.settings)
                _state.update {
                    it.copy(
                        running = false,
                        lastReport = report,
                        message = if (report.failures.isEmpty()) {
                            null
                        } else {
                            "Run recorded with failures:\n- " + report.failures.joinToString("\n- ")
                        },
                    )
                }
            } catch (c: CancellationException) {
                _state.update { it.copy(running = false, message = "Cancelled by user") }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(running = false, message = "Benchmark threw ${t::class.java.simpleName}: ${t.message}")
                }
            }
        }
    }

    fun cancel() {
        container.runner.requestCancel()
        _state.update { it.copy(message = "cancellation requested") }
    }

    fun jsonFor(report: BenchmarkReport): String = com.slop4hexagon.app.bench.BenchmarkRunner.toJson(report)

    fun previewJson(report: BenchmarkReport) = _state.update { it.copy(exportedText = jsonFor(report)) }

    fun export(report: BenchmarkReport, uri: Uri?) {
        if (uri == null) {
            _state.update { it.copy(message = "Export cancelled") }
            return
        }
        viewModelScope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.slop4hexagon.app.platform.ImageStore.writeBytes(container.appContext, uri, jsonFor(report).toByteArray())
                }
            }.onSuccess {
                _state.update { it.copy(message = "Exported ${report.id} (${ModelNames.humanSize(jsonFor(report).length.toLong())})") }
            }.onFailure { t ->
                _state.update { it.copy(message = "Export failed: ${t::class.java.simpleName}: ${t.message}") }
            }
        }
    }

    fun deleteReport(id: String) {
        viewModelScope.launch { container.benchmarkHistory.delete(id) }
    }

    /** Toggle-style selection: first click picks the left, second the right. */
    fun pickForComparison(id: String, allIds: List<String>) {
        val current = _state.value.comparison
        when {
            current == null -> _state.update {
                it.copy(comparison = id to (allIds.firstOrNull { other -> other != id } ?: id))
                    .withComparison()
            }
            current.first == id -> _state.update { it.copy(comparison = null, comparisonSummary = null) }
            current.second == id -> _state.update { it.copy(comparison = null, comparisonSummary = null) }
            else -> _state.update { it.copy(comparison = current.first to id).withComparison() }
        }
    }

    private fun UiState.withComparison(): UiState {
        val pair = comparison ?: return this
        val left = history.firstOrNull { it.id == pair.first }
        val right = history.firstOrNull { it.id == pair.second }
        return if (left == null || right == null) {
            copy(comparisonSummary = null)
        } else {
            copy(comparisonSummary = BenchmarkComparator.compare(left, right))
        }
    }

    fun selectForComparison(leftId: String?, rightId: String?) {
        if (leftId == null || rightId == null || leftId == rightId) {
            _state.update { it.copy(comparison = null, comparisonSummary = null) }
            return
        }
        val left = _state.value.history.firstOrNull { it.id == leftId }
        val right = _state.value.history.firstOrNull { it.id == rightId }
        if (left == null || right == null) return
        _state.update {
            it.copy(comparison = leftId to rightId, comparisonSummary = BenchmarkComparator.compare(left, right))
        }
    }

    /** Ops the currently selected backend can execute (empty => nothing runs). */
    fun opsSupportedBySelection(): Set<OpKind> =
        container.backends.currentBackend()?.capabilities()?.supportedOps ?: emptySet()

    companion object {
        /** Matches the CPU backend's advertised element budget. */
        private const val MAX_TENSOR_ELEMENTS = 1L shl 24

        /** Allocating more than this per buffer would risk an OOM kill on 8 GB devices. */
        private const val MAX_ALLOC_ELEMENTS = 8 * 1024 * 1024
    }
}
