package com.slop4hexagon.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.slop4hexagon.core.backend.BackendPreference
import com.slop4hexagon.core.settings.AppSettings
import com.slop4hexagon.core.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Small persistent preferences on DataStore (as specified), mapped onto the
 * pure-Kotlin [AppSettings] model so the domain layer stays Android-free.
 *
 * Reads are defensive: a corrupt preferences file yields defaults instead of a
 * crash, because a settings failure must not brick model management.
 */
class SettingsRepository(context: Context, private val scope: CoroutineScope) {

    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile(STORE_NAME)
    }

    private val _lastReadError = MutableStateFlow<String?>(null)
    val lastReadError: StateFlow<String?> = _lastReadError

    val settings: StateFlow<AppSettings> = store.data
        .map { prefs ->
            AppSettings.fromRaw(
                backendPreference = prefs[KEY_BACKEND],
                themeMode = prefs[KEY_THEME],
                dynamicColor = prefs[KEY_DYNAMIC],
                warmupIterations = prefs[KEY_WARMUP],
                measuredIterations = prefs[KEY_MEASURED],
                maxStoredReports = prefs[KEY_MAX_REPORTS],
            )
        }
        .catch { t ->
            // A corrupt preferences file must not brick the app: fall back to
            // defaults and surface the reason, and emit the *settings* type the
            // flow carries (not an empty Preferences document).
            _lastReadError.value = "settings unreadable (${t::class.java.simpleName}); using defaults"
            emit(AppSettings())
        }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    fun setBackendPreference(value: BackendPreference) = write { it[KEY_BACKEND] = value.name }

    fun setThemeMode(value: ThemeMode) = write { it[KEY_THEME] = value.name }

    fun setDynamicColor(value: Boolean) = write { it[KEY_DYNAMIC] = value }

    fun setWarmupIterations(value: Int) = write { it[KEY_WARMUP] = value }

    fun setMeasuredIterations(value: Int) = write { it[KEY_MEASURED] = value }

    fun setMaxStoredReports(value: Int) = write { it[KEY_MAX_REPORTS] = value }

    private fun write(block: (MutablePreferences) -> Unit) {
        scope.launch {
            runCatching { store.edit(block) }
                .onFailure { _lastReadError.value = "could not persist settings: ${it.message}" }
        }
    }

    private companion object {
        const val STORE_NAME = "slop_settings"
        val KEY_BACKEND = stringPreferencesKey("backend_preference")
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC = booleanPreferencesKey("dynamic_color")
        val KEY_WARMUP = intPreferencesKey("warmup_iterations")
        val KEY_MEASURED = intPreferencesKey("measured_iterations")
        val KEY_MAX_REPORTS = intPreferencesKey("max_stored_reports")
    }
}
