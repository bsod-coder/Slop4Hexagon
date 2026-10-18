package com.slop4hexagon.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slop4hexagon.app.AppContainer
import com.slop4hexagon.app.data.ModelRepository
import com.slop4hexagon.core.model.ModelDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives SAF imports and the library list. Progress, duplicate detection and
 * rejections are surfaced verbatim from [ModelRepository]; nothing is smoothed
 * over, and removing a model only ever deletes the app's own copy.
 */
class ModelLibraryViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val models: List<ModelDescriptor> = emptyList(),
        val progress: ModelRepository.ImportProgress? = null,
        val importing: Boolean = false,
        val messages: List<UiMessage> = emptyList(),
        val detailsFor: String? = null,
    )

    data class UiMessage(val text: String, val tone: Tone) {
        enum class Tone { INFO, WARN, ERROR }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.models.models.collect { models -> _state.update { it.copy(models = models) } }
        }
        viewModelScope.launch {
            container.models.progress.collect { p -> _state.update { it.copy(progress = p, importing = p != null) } }
        }
    }

    fun import(uri: Uri) {
        if (_state.value.importing) {
            postMessage("An import is already in progress; wait for it to finish", UiMessage.Tone.WARN)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(importing = true) }
            when (val result = container.models.import(uri)) {
                is ModelRepository.ImportResult.Imported -> {
                    val warnings = result.warnings
                    postMessage(
                        "Imported ${result.descriptor.displayName} " +
                            "(${result.descriptor.sizeBytes} bytes, ${result.descriptor.inspection.format.name})" +
                            if (warnings.isNotEmpty()) "\n" + warnings.joinToString("\n- ", "- ") else "",
                        if (warnings.isEmpty()) UiMessage.Tone.INFO else UiMessage.Tone.WARN,
                    )
                }
                is ModelRepository.ImportResult.Duplicate ->
                    postMessage("Duplicate not imported: ${result.reason}", UiMessage.Tone.WARN)
                is ModelRepository.ImportResult.Rejected ->
                    postMessage("Refused: ${result.reason}", UiMessage.Tone.ERROR)
                is ModelRepository.ImportResult.Failed ->
                    postMessage(result.message, UiMessage.Tone.ERROR)
            }
            _state.update { it.copy(importing = false) }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            val removed = container.models.remove(id)
            postMessage(
                if (removed) "Removed the app's copy of $id. Your original file was not touched."
                else "Nothing to remove for $id",
                UiMessage.Tone.INFO,
            )
        }
    }

    fun showDetails(id: String?) = _state.update { it.copy(detailsFor = id) }

    fun dismissMessage(index: Int) = _state.update { it.copy(messages = it.messages.filterIndexed { i, _ -> i != index }) }

    private fun postMessage(text: String, tone: UiMessage.Tone) =
        _state.update { it.copy(messages = it.messages + UiMessage(text, tone)) }
}
