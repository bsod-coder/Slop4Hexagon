/*
 * Compile-only stubs for androidx.datastore:preferences-core.
 */
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "ClassName")

package androidx.datastore.preferences.core

import androidx.datastore.core.DataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class PreferenceKey<T>(val name: String, val defaultValue: T)

interface Preferences {
    operator fun <T> get(key: PreferenceKey<T>): T?
}

class MutablePreferences : Preferences {
    override fun <T> get(key: PreferenceKey<T>): T? = null
    operator fun <T> set(key: PreferenceKey<T>, value: T?) {}
}

fun stringPreferencesKey(name: String): PreferenceKey<String> = PreferenceKey(name, "")
fun booleanPreferencesKey(name: String): PreferenceKey<Boolean> = PreferenceKey(name, false)
fun intPreferencesKey(name: String): PreferenceKey<Int> = PreferenceKey(name, 0)
fun longPreferencesKey(name: String): PreferenceKey<Long> = PreferenceKey(name, 0L)
fun floatPreferencesKey(name: String): PreferenceKey<Float> = PreferenceKey(name, 0f)
fun stringSetPreferencesKey(name: String): PreferenceKey<Set<String>> = PreferenceKey(name, emptySet())

class EmptyPreferences : Preferences {
    override fun <T> get(key: PreferenceKey<T>): T? = null
}

fun emptyPreferences(): Preferences = EmptyPreferences()

suspend fun DataStore<Preferences>.edit(transform: (MutablePreferences) -> Unit): Preferences =
    updateData { EmptyPreferences() }

object PreferenceDataStoreFactory {
    fun create(scope: CoroutineScope? = null, produceFile: () -> File): DataStore<Preferences> = StubStore()

    private class StubStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(EmptyPreferences())
        override suspend fun updateData(mutation: suspend (curr: Preferences) -> Preferences): Preferences = EmptyPreferences()
        override suspend fun setData(value: Preferences) {}
    }
}
