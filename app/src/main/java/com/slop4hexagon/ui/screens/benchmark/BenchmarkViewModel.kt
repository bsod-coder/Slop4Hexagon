package com.slop4hexagon.ui.screens.benchmark

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slop4hexagon.SlopApp
import com.slop4hexagon.data.db.BenchmarkEntity
import com.slop4hexagon.data.db.ModelEntity
import com.slop4hexagon.domain.benchmark.BenchmarkConfig
import com.slop4hexagon.domain.benchmark.BenchmarkMode
import com.slop4hexagon.domain.benchmark.BenchmarkResult
import com.slop4hexagon.domain.model.BackendId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class BenchmarkUiState(
    val models: List<ModelEntity> = emptyList(),
    val benchmarks: List<BenchmarkEntity> = emptyList(),
    val selectedModelId: String? = null,
    val selectedBackend: BackendId = BackendId.CPU,
    val mode: BenchmarkMode = BenchmarkMode.MICRO,
    val warmup: String = "1",
    val measured: String = "5",
    val isRunning: Boolean = false,
    val progress: String? = null,
    val lastResult: BenchmarkResult? = null,
    val error: String? = null
)

class BenchmarkViewModel(private val app: SlopApp) : ViewModel() {
    private val dao = app.db.modelDao()
    private val engine = app.benchmarkEngine
    private val _state = MutableStateFlow(BenchmarkUiState())
    val state: StateFlow<BenchmarkUiState> = _state

    private var job: Job? = null
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init {
        viewModelScope.launch {
            app.modelRepository.observeModels().collect { list -> _state.value = _state.value.copy(models = list) }
        }
        viewModelScope.launch {
            dao.observeBenchmarks().collect { list -> _state.value = _state.value.copy(benchmarks = list) }
        }
    }

    fun selectModel(id: String?) { _state.value = _state.value.copy(selectedModelId = id) }
    fun selectBackend(id: BackendId) { _state.value = _state.value.copy(selectedBackend = id) }
    fun selectMode(m: BenchmarkMode) { _state.value = _state.value.copy(mode = m) }
    fun setWarmup(v: String) { _state.value = _state.value.copy(warmup = v) }
    fun setMeasured(v: String) { _state.value = _state.value.copy(measured = v) }

    fun run(context: Context) {
        val s = _state.value
        val w = s.warmup.toIntOrNull() ?: 1
        val m = s.measured.toIntOrNull() ?: 5
        if (s.mode == BenchmarkMode.FULL_PIPELINE && s.selectedModelId == null) {
            _state.value = s.copy(error = "Full pipeline requires a model. Select one or switch to Microbenchmark.")
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(isRunning = true, error = null, progress = "Starting…")
            try {
                val result = engine.runBenchmark(
                    modelId = if (s.mode == BenchmarkMode.MICRO) s.selectedModelId else s.selectedModelId,
                    backendId = s.selectedBackend,
                    mode = s.mode,
                    warmupIterations = w,
                    measuredIterations = m,
                    onProgress = { p -> _state.value = _state.value.copy(progress = p) }
                )
                // Persist
                val entity = BenchmarkEntity(
                    id = result.id,
                    json = json.encodeToString(result),
                    timestampMillis = result.timestampMillis,
                    backendId = result.config.backendId,
                    mode = result.config.mode
                )
                dao.insertBenchmark(entity)
                _state.value = _state.value.copy(isRunning = false, lastResult = result, progress = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isRunning = false, error = e.message, progress = null)
            }
        }
    }

    fun cancel() { job?.cancel(); _state.value = _state.value.copy(isRunning = false, progress = "Cancelled") }

    fun exportJson(result: BenchmarkResult): String = json.encodeToString(result)

    fun compare(ids: List<String>): List<BenchmarkResult> {
        return ids.mapNotNull { id ->
            _state.value.benchmarks.find { it.id == id }?.let { entity ->
                try { json.decodeFromString<BenchmarkResult>(entity.json) } catch (_: Exception) { null }
            }
        }
    }
}
