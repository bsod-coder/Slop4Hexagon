package com.slop4hexagon.ui.screens.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slop4hexagon.SlopApp
import com.slop4hexagon.data.db.ModelEntity
import com.slop4hexagon.domain.model.BackendCapabilities
import com.slop4hexagon.domain.model.ModelFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(
    val models: List<ModelEntity> = emptyList(),
    val importing: Boolean = false,
    val importProgress: String? = null,
    val error: String? = null,
    val selectedId: String? = null
)

class LibraryViewModel(private val app: SlopApp) : ViewModel() {
    private val repo = app.modelRepository
    private val backendManager = app.backendManager
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state

    init {
        viewModelScope.launch {
            repo.observeModels().collect { list ->
                _state.value = _state.value.copy(models = list, importing = false)
            }
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, error = null, importProgress = "Copying...")
            val result = repo.importModel(uri) { copied, total ->
                viewModelScope.launch {
                    _state.value = _state.value.copy(importProgress = "Copied ${copied / 1024} KB ${total?.let { "/ ${it / 1024} KB" } ?: ""}")
                }
            }
            result.onSuccess {
                _state.value = _state.value.copy(importing = false, importProgress = "Imported ${it.displayName} [${it.format}]")
            }.onFailure {
                _state.value = _state.value.copy(importing = false, error = it.message ?: "Import failed", importProgress = null)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.deleteModel(id).onFailure {
                _state.value = _state.value.copy(error = it.message)
            }
        }
    }

    fun compatibilityFor(entity: ModelEntity): Map<String, String> {
        val format = try { ModelFormat.valueOf(entity.format) } catch (_: Exception) { ModelFormat.UNKNOWN }
        return backendManager.listBackends().associate { b ->
            val cap = b.capabilities
            val status = when {
                format in cap.supportedFormats -> "Compatible"
                format == ModelFormat.SAFETENSORS && b.id.name == "HTP" -> "Needs conversion (DLC)"
                format == ModelFormat.CKPT && b.id.name == "HTP" -> "Needs conversion (DLC)"
                format == ModelFormat.UNKNOWN -> "Unknown format - unsupported"
                else -> "Incompatible format"
            }
            b.id.name to status
        }
    }

    fun showDetails(id: String) { _state.value = _state.value.copy(selectedId = id) }
    fun dismissDetails() { _state.value = _state.value.copy(selectedId = null) }
}
