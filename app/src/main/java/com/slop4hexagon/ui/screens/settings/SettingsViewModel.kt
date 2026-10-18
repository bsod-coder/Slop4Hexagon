package com.slop4hexagon.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slop4hexagon.SlopApp
import com.slop4hexagon.domain.diagnostics.DeviceDiagnostics
import com.slop4hexagon.domain.model.BackendId
import com.slop4hexagon.domain.model.BackendStatus
import com.slop4hexagon.domain.model.DeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferredBackend: BackendId = BackendId.AUTO,
    val backendStatuses: Map<BackendId, BackendStatus> = emptyMap(),
    val deviceInfo: DeviceInfo? = null,
    val logs: String = "",
    val error: String? = null
)

class SettingsViewModel(private val app: SlopApp, private val context: Context) : ViewModel() {
    private val prefs = app.prefs
    private val manager = app.backendManager
    private val diagnostics = DeviceDiagnostics(context)
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        viewModelScope.launch {
            prefs.preferredBackend.collect { pref ->
                _state.value = _state.value.copy(preferredBackend = try { BackendId.valueOf(pref) } catch (_: Exception) { BackendId.AUTO })
            }
        }
        refreshDiagnostics()
        refreshBackends()
    }

    fun refreshBackends() {
        viewModelScope.launch {
            val statuses = manager.initializeAll(context)
            _state.value = _state.value.copy(backendStatuses = statuses)
        }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val info = diagnostics.collect()
            _state.value = _state.value.copy(deviceInfo = info.copy(backendStatuses = manager.getStatusReport(context).mapKeys { it.key.name }.mapValues { it.value.name }))
        }
    }

    fun setPreferredBackend(id: BackendId) {
        viewModelScope.launch { prefs.setPreferredBackend(id.name) }
    }

    fun exportDiagnostics(): String {
        val s = _state.value
        val info = s.deviceInfo
        return buildString {
            appendLine("Slop4Hexagon Diagnostics")
            appendLine("SOC_MODEL: ${info?.socModel}")
            appendLine("HARDWARE: ${info?.hardware}")
            appendLine("ABIs: ${info?.supportedAbis}")
            appendLine("Android ${info?.androidVersion} API ${info?.apiLevel}")
            appendLine("Mem total ${info?.totalMemMb}MB avail ${info?.availMemMb}MB low=${info?.lowMemory}")
            appendLine("Thermal ${info?.thermalStatus}")
            appendLine("App ${info?.appVersion}")
            appendLine("Backends:")
            s.backendStatuses.forEach { (id, st) -> appendLine("  $id = $st") }
            appendLine("HTP notes: see docs/HTP_INTEGRATION.md")
        }
    }

    fun appendLog(msg: String) {
        _state.value = _state.value.copy(logs = _state.value.logs + "\n" + msg)
    }
}
