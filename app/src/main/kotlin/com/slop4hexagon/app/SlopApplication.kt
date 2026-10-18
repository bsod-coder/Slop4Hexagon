package com.slop4hexagon.app

import android.app.Application
import com.slop4hexagon.app.backend.BackendManagerHolder
import com.slop4hexagon.app.bench.BenchmarkRunner
import com.slop4hexagon.app.data.BenchmarkIndexStore
import com.slop4hexagon.app.data.ModelIndexStore
import com.slop4hexagon.app.data.ModelRepository
import com.slop4hexagon.app.generate.GenerationController
import com.slop4hexagon.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container.
 *
 * No DI framework is pulled in for four screens: this file *is* the graph, it
 * stays greppable, and it keeps the app buildable in restricted environments.
 * Everything long-lived is created lazily on first use so app start stays cheap
 * and the JNI library is never loaded implicitly on the main thread.
 */
class SlopApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Kick off backend discovery off the main thread; screens read a StateFlow.
        applicationScope.launch { container.backends.refresh(container.settings.settings.value.backendPreference) }
    }
}

class AppContainer(app: Application) {

    private val context = app.applicationContext

    val scope = (app as? SlopApplication)?.applicationScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appVersion: String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    val settings: SettingsRepository by lazy { SettingsRepository(context, scope) }

    val modelIndexStore: ModelIndexStore by lazy { ModelIndexStore(context.filesDir, scope) }

    val benchmarkIndexStore: BenchmarkIndexStore by lazy { BenchmarkIndexStore(context.filesDir, scope) }

    val models: ModelRepository by lazy { ModelRepository(context, modelIndexStore, scope) }

    val backends: BackendManagerHolder by lazy { BackendManagerHolder(context, models, scope) }

    val runner: BenchmarkRunner by lazy {
        BenchmarkRunner(context, backends, benchmarkIndexStore, appVersion, scope)
    }

    val generation: GenerationController by lazy { GenerationController(context, backends, models) }

    val benchmarkHistory: BenchmarkHistory by lazy { BenchmarkHistory(context, benchmarkIndexStore) }
}

/** Small façade over the benchmark index for the UI: export, delete, compare. */
class BenchmarkHistory(
    private val context: android.content.Context,
    private val store: BenchmarkIndexStore,
) {
    val reports = store.items

    fun jsonFor(id: String): String? = reports.value.firstOrNull { it.id == id }?.let { BenchmarkRunner.toJson(it) }

    suspend fun delete(id: String) {
        store.update { list -> list.filterNot { it.id == id } }
    }

    suspend fun clear() {
        store.update { emptyList() }
    }

    fun export(id: String, uri: android.net.Uri): Result<Unit> {
        val report = reports.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalStateException("report $id is no longer available"))
        return runCatching {
            com.slop4hexagon.app.platform.ImageStore.writeBytes(context, uri, BenchmarkRunner.toJson(report).toByteArray())
        }
    }

    companion object {
        /** Default file name offered by the export document picker. */
        fun suggestedFileName(id: String): String = "slop4hexagon-$id.json"
    }
}
