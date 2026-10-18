package com.slop4hexagon.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slop4hexagon.app.AppContainer
import com.slop4hexagon.app.platform.DeviceInfo
import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.backend.BackendPreference
import com.slop4hexagon.core.backend.BackendStatusInfo
import com.slop4hexagon.core.diagnostics.DeviceSnapshot
import com.slop4hexagon.core.settings.AppSettings
import com.slop4hexagon.core.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings + diagnostics. The backend table and the device snapshot report
 * exactly what the app knows — including its own failures — with no optimistic
 * defaults.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val statuses: List<BackendStatusInfo> = emptyList(),
        val selectionReasons: List<String> = emptyList(),
        val fallbackOccurred: Boolean = false,
        val snapshot: DeviceSnapshot? = null,
        val busy: Boolean = false,
        val message: String? = null,
        val storeErrors: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settings.settings.collect { s ->
                _state.update {
                    it.copy(
                        settings = s,
                        storeErrors = buildList {
                            container.settings.lastReadError.value?.let { e -> add("settings: $e") }
                            container.modelIndexStore.lastError?.let { e -> add("model index: $e") }
                            container.benchmarkIndexStore.lastError?.let { e -> add("benchmark index: $e") }
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            container.backends.state.collect { b ->
                _state.update {
                    it.copy(
                        statuses = b.statuses,
                        selectionReasons = b.selection?.reasons ?: emptyList(),
                        fallbackOccurred = b.selection?.fallbackOccurred ?: false,
                    )
                }
            }
        }
    }

    fun setBackendPreference(value: BackendPreference) {
        container.settings.setBackendPreference(value)
        container.backends.refresh(value)
        _state.update { it.copy(message = "Re-probing backends for preference $value…") }
    }

    fun setTheme(mode: ThemeMode) {
        container.settings.setThemeMode(mode)
    }

    fun setDynamicColor(enabled: Boolean) {
        container.settings.setDynamicColor(enabled)
    }

    fun setIterations(warmup: Int, measured: Int) {
        container.settings.setWarmupIterations(warmup)
        container.settings.setMeasuredIterations(measured)
    }

    /**
     * Collects the device snapshot. [DeviceSnapshot.hexagonVerified] is only
     * ever set when HTP inference has genuinely been executed and validated in
     * this process — which this build cannot do — so it stays false and the
     * export says why.
     */
    fun collectDiagnostics() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "collecting…") }
            val snapshot = withContext(Dispatchers.Default) {
                DeviceInfo.snapshot(
                    context = container.appContext,
                    appVersion = container.appVersion,
                    backendStatuses = container.backends.statusSnapshot(),
                    engine = container.backends.nativeEngine,
                    hexagonVerified = container.backends.currentBackend()?.id == BackendId.HTP_QNN &&
                        container.backends.executionVerified(),
                )
            }
            _state.update { it.copy(busy = false, snapshot = snapshot, message = null) }
        }
    }

    fun reprobeBackends() {
        container.backends.refresh(_state.value.settings.backendPreference)
        _state.update { it.copy(message = "backend probe restarted") }
    }

    fun diagnosticJson(): String = _state.value.snapshot?.toPrettyJson()
        ?: "no snapshot collected yet — press \"Collect diagnostics\""

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
