package com.slop4hexagon.ui.screens.generate

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slop4hexagon.SlopApp
import com.slop4hexagon.data.db.ModelEntity
import com.slop4hexagon.domain.model.BackendId
import com.slop4hexagon.domain.model.GenerationMode
import com.slop4hexagon.domain.model.InferenceRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class GenerateUiState(
    val models: List<ModelEntity> = emptyList(),
    val selectedModelId: String? = null,
    val prompt: String = "",
    val negativePrompt: String = "",
    val mode: GenerationMode = GenerationMode.TEXT_TO_IMAGE,
    val sourceImageUri: Uri? = null,
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val seed: String = "",
    val denoisingStrength: String = "0.75",
    val preferredBackend: BackendId = BackendId.AUTO,
    val isRunning: Boolean = false,
    val progress: Float? = null,
    val elapsedMillis: Long? = null,
    val resultUri: String? = null,
    val error: String? = null,
    val backendUsed: String? = null
)

class GenerateViewModel(private val app: SlopApp) : ViewModel() {
    private val repo = app.modelRepository
    private val backendManager = app.backendManager
    private val _state = MutableStateFlow(GenerateUiState())
    val state: StateFlow<GenerateUiState> = _state

    private var job: Job? = null

    init {
        viewModelScope.launch {
            repo.observeModels().collect { list ->
                _state.value = _state.value.copy(models = list)
            }
        }
        viewModelScope.launch {
            app.prefs.preferredBackend.collect { pref ->
                try {
                    _state.value = _state.value.copy(preferredBackend = BackendId.valueOf(pref))
                } catch (_: Exception) {}
            }
        }
    }

    fun updatePrompt(v: String) { _state.value = _state.value.copy(prompt = v) }
    fun updateNegative(v: String) { _state.value = _state.value.copy(negativePrompt = v) }
    fun selectModel(id: String) { _state.value = _state.value.copy(selectedModelId = id) }
    fun updateMode(m: GenerationMode) { _state.value = _state.value.copy(mode = m) }
    fun updateSourceUri(uri: Uri?) { _state.value = _state.value.copy(sourceImageUri = uri) }
    fun updateSize(w: Int, h: Int) { _state.value = _state.value.copy(width = w, height = h) }
    fun updateSteps(s: Int) { _state.value = _state.value.copy(steps = s) }
    fun updateSeed(s: String) { _state.value = _state.value.copy(seed = s) }
    fun updateDenoising(v: String) { _state.value = _state.value.copy(denoisingStrength = v) }

    fun generate(context: Context) {
        val s = _state.value
        if (s.selectedModelId == null) {
            _state.value = s.copy(error = "Select a model first. Import one in Model Library.")
            return
        }
        if (s.prompt.isBlank()) {
            _state.value = s.copy(error = "Prompt is required.")
            return
        }
        // Validate dimensions
        if (s.width !in 64..1024 || s.height !in 64..1024 || s.width % 8 != 0 || s.height % 8 != 0) {
            _state.value = s.copy(error = "Width/height must be 64..1024 and multiple of 8.")
            return
        }
        // SAF image-to-image requires source
        if (s.mode == GenerationMode.IMAGE_TO_IMAGE && s.sourceImageUri == null) {
            _state.value = s.copy(error = "Image-to-image requires a source image.")
            return
        }

        job?.cancel()
        job = viewModelScope.launch {
            _state.value = _state.value.copy(isRunning = true, error = null, resultUri = null, elapsedMillis = null)
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                val backend = backendManager.resolvePreferred(s.preferredBackend, context)
                if (backend == null) {
                    _state.value = _state.value.copy(isRunning = false, error = "No backend available for ${s.preferredBackend}.")
                    return@launch
                }
                // Check compatibility via format
                val model = repo.getModel(s.selectedModelId)
                if (model == null) {
                    _state.value = _state.value.copy(isRunning = false, error = "Model not found. Re-import.")
                    return@launch
                }
                // Build request - denoising only if backend supports
                val denoising = s.denoisingStrength.toFloatOrNull()?.takeIf { backend.capabilities.supportsDenoisingStrength }
                if (s.denoisingStrength.isNotBlank() && denoising == null && s.mode == GenerationMode.IMAGE_TO_IMAGE) {
                    // Warn but allow: UI should have hidden field, but we validate here
                    // We don't fail, just ignore
                }
                val req = InferenceRequest(
                    modelId = s.selectedModelId,
                    prompt = s.prompt,
                    negativePrompt = s.negativePrompt.ifBlank { null },
                    mode = s.mode,
                    sourceImageUri = s.sourceImageUri?.toString(),
                    width = s.width,
                    height = s.height,
                    steps = s.steps,
                    seed = s.seed.toLongOrNull(),
                    denoisingStrength = denoising,
                    preferredBackend = s.preferredBackend
                )
                // Dispatch to backend if generative
                val result = if (backend is com.slop4hexagon.domain.engine.GenerativeBackend) {
                    backend.generate(req)
                } else {
                    com.slop4hexagon.domain.model.InferenceResult.Failure(
                        reason = "Backend ${backend.id} does not support generative pipeline yet. Use CPU microbenchmark or wait for ONNX Runtime integration.",
                        backendStatus = backend.getStatus(),
                        backendAttempted = backend.id
                    )
                }
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                when (result) {
                    is com.slop4hexagon.domain.model.InferenceResult.Success -> {
                        _state.value = _state.value.copy(isRunning = false, resultUri = result.outputUri, elapsedMillis = elapsed, backendUsed = result.backendUsed.name)
                    }
                    is com.slop4hexagon.domain.model.InferenceResult.Failure -> {
                        _state.value = _state.value.copy(isRunning = false, error = result.reason, elapsedMillis = elapsed, backendUsed = result.backendAttempted?.name)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = _state.value.copy(isRunning = false, error = "Cancelled")
            } catch (e: Exception) {
                _state.value = _state.value.copy(isRunning = false, error = e.message ?: "Unknown error")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(isRunning = false)
    }

    fun saveToMediaStore(context: Context, onDone: (String) -> Unit) {
        // Stub: actual save uses MediaStore API
        onDone("Saved to gallery (stub)")
    }
}
