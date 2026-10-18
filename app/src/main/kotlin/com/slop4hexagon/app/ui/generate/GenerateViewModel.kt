package com.slop4hexagon.app.ui.generate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slop4hexagon.app.AppContainer
import com.slop4hexagon.app.generate.GenerationController
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.model.ModelDescriptor
import com.slop4hexagon.core.pipeline.PipelineSettings
import com.slop4hexagon.core.pipeline.PipelineRequestValidator
import com.slop4hexagon.core.pipeline.SettingSupport
import com.slop4hexagon.core.pipeline.ValidatedRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the generate form, derives the honest "what can actually run" view of
 * it, and drives [GenerationController]. Nothing here pretends a run happened:
 * the outcome is exactly what the controller (i.e. the backend) reported.
 */
class GenerateViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val settings: PipelineSettings = PipelineSettings(),
        val models: List<ModelDescriptor> = emptyList(),
        val running: Boolean = false,
        val progress: GenerationController.Progress? = null,
        val outcome: GenerationController.Outcome? = null,
        val validation: ValidatedRequest? = null,
        val backendLabel: String = "no backend selected",
        val backendStatus: BackendStatus = BackendStatus.UNAVAILABLE,
        val sourceImageUri: android.net.Uri? = null,
        val sourceImageName: String? = null,
        val savedMessage: String? = null,
    ) {
        val fieldSupport: Map<String, SettingSupport> get() = validation?.fieldSupport ?: emptyMap()
        fun supportOf(field: String): SettingSupport =
            fieldSupport[field] ?: SettingSupport.NOT_SUPPORTED
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            container.models.models.collect { models ->
                _state.update { it.copy(models = models).revalidate() }
            }
        }
        viewModelScope.launch {
            container.backends.state.collect { backends ->
                val chosen = backends.selection?.chosen
                _state.update {
                    it.copy(
                        backendLabel = chosen?.displayName ?: "no usable backend",
                        backendStatus = backends.selection?.status ?: BackendStatus.UNAVAILABLE,
                    ).revalidate()
                }
            }
        }
    }

    private fun UiState.revalidate(): UiState = copy(
        validation = PipelineRequestValidator.validate(
            settings = settings,
            capabilities = container.backends.currentBackend()?.capabilities(),
            inspection = settings.modelId?.let { id -> models.firstOrNull { it.id == id }?.inspection },
        ),
    )

    fun updateSettings(transform: (PipelineSettings) -> PipelineSettings) {
        _state.update { it.copy(settings = transform(it.settings)).revalidate() }
    }

    fun selectModel(id: String?) = updateSettings { it.copy(modelId = id) }

    fun onSourceImagePicked(uri: android.net.Uri?, displayName: String?) {
        _state.update {
            it.copy(
                sourceImageUri = uri,
                sourceImageName = displayName,
                settings = it.settings.copy(
                    sourceImageSelected = uri != null,
                    mode = if (uri != null) {
                        com.slop4hexagon.core.backend.InferenceRequest.ImageGenMode.IMAGE_TO_IMAGE
                    } else {
                        it.settings.mode
                    },
                ),
            ).revalidate()
        }
    }

    fun run() {
        if (_state.value.running) return
        val snapshot = _state.value
        runJob = viewModelScope.launch {
            _state.update { it.copy(running = true, outcome = null, savedMessage = null) }
            val outcome = container.generation.generate(snapshot.settings, snapshot.sourceImageUri)
            _state.update { it.copy(running = false, progress = null, outcome = outcome) }
        }
    }

    fun cancel() {
        container.generation.requestCancel()
        runJob?.cancel()
        _state.update { it.copy(running = false, progress = null) }
    }

    fun saveToPictures() {
        val outcome = _state.value.outcome as? GenerationController.Outcome.Executed ?: return
        viewModelScope.launch {
            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.slop4hexagon.app.platform.ImageStore.savePngToMediaStore(
                    container.appContext,
                    "slop4hexagon-${System.currentTimeMillis()}.png",
                    outcome.pngBytes,
                )
            }
            _state.update {
                it.copy(
                    savedMessage = if (uri != null) {
                        "Saved to system Pictures: $uri"
                    } else {
                        "The system refused the MediaStore write; use \"Save to folder…\" instead"
                    },
                )
            }
        }
    }

    fun saveToUri(uri: android.net.Uri?) {
        val outcome = _state.value.outcome as? GenerationController.Outcome.Executed ?: return
        if (uri == null) return
        viewModelScope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.slop4hexagon.app.platform.ImageStore.writeBytes(container.appContext, uri, outcome.pngBytes)
                }
            }.onSuccess {
                _state.update { it.copy(savedMessage = "Saved ${outcome.pngBytes.size} bytes to the chosen document") }
            }.onFailure { t ->
                _state.update { it.copy(savedMessage = "Save failed: ${t::class.java.simpleName}: ${t.message}") }
            }
        }
    }

    override fun onCleared() {
        runJob?.cancel()
        super.onCleared()
    }
}
