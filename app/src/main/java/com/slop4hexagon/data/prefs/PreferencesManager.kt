package com.slop4hexagon.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "slop_prefs")

class PreferencesManager(private val context: Context) {
    companion object {
        val KEY_BACKEND = stringPreferencesKey("preferred_backend") // AUTO, CPU, GPU, HTP
        val KEY_MODEL_STORAGE = stringPreferencesKey("model_storage_location")
    }

    val preferredBackend: Flow<String> = context.dataStore.data.map { it[KEY_BACKEND] ?: "AUTO" }

    suspend fun setPreferredBackend(id: String) {
        context.dataStore.edit { it[KEY_BACKEND] = id }
    }

    val modelStorageLocation: Flow<String?> = context.dataStore.data.map { it[KEY_MODEL_STORAGE] }

    suspend fun setModelStorageLocation(path: String) {
        context.dataStore.edit { it[KEY_MODEL_STORAGE] = path }
    }
}
