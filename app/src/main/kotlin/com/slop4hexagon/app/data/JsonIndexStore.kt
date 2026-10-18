package com.slop4hexagon.app.data

import android.util.Log
import com.slop4hexagon.core.benchmark.BenchmarkReport
import com.slop4hexagon.core.benchmark.BenchmarkReportJson
import com.slop4hexagon.core.json.JsonValue
import com.slop4hexagon.core.model.ModelDescriptor
import com.slop4hexagon.core.model.ModelDescriptorJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small, file-backed persistence for the model index and benchmark history.
 *
 * Room/DataStore were considered and deliberately not used here: both records
 * are tiny JSON documents that the domain layer already knows how to serialize
 * (and unit-tests round-trip), while adding annotation processing would pull a
 * codegen dependency into a project that must also build in restricted
 * environments. The on-disk format is a single JSON object per file and is
 * versioned by `schemaVersion` inside the document.
 *
 * All I/O happens on [Dispatchers.IO] behind a mutex; the in-memory list is
 * exposed as a [StateFlow] for the UI.
 */
abstract class JsonIndexStore<T>(
    private val directory: File,
    private val fileName: String,
    private val scope: CoroutineScope,
    private val tag: String,
    private val encodeIndex: (List<T>) -> JsonValue.JsonObject,
    private val decodeIndex: (JsonValue.JsonObject) -> List<T>,
) {

    private val mutex = Mutex()
    private val file: File get() = File(directory, fileName)

    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    @Volatile
    private var loaded = false

    @Volatile
    var lastError: String? = null
        private set

    init {
        scope.launch { ensureLoaded() }
    }

    suspend fun ensureLoaded(): List<T> = mutex.withLock {
        if (loaded) return@withLock _items.value
        loaded = true
        if (!file.exists()) return@withLock emptyList<T>().also { _items.value = it }
        val loadedItems = try {
            val text = file.readText()
            if (text.isBlank()) emptyList() else decodeIndex(JsonValue.parseObject(text))
        } catch (t: Throwable) {
            Log.w(tag, "index unreadable, starting empty: ${t.message}")
            lastError = "index file was unreadable (${t::class.java.simpleName}); starting with an empty list. " +
                "The unreadable file was kept at ${file.absolutePath}"
            copyCorruptAside()
            emptyList()
        }
        _items.value = loadedItems
        loadedItems
    }

    private fun copyCorruptAside() {
        runCatching {
            val backup = File(directory, "$fileName.corrupt-${System.currentTimeMillis()}")
            file.copyTo(backup, overwrite = true)
            file.delete()
        }
    }

    /** Applies [mutate] to the current list and persists the result atomically. */
    suspend fun update(mutate: (List<T>) -> List<T>): List<T> = mutex.withLock {
        ensureLoadedLocked()
        val next = mutate(_items.value)
        persistLocked(next)
        _items.value = next
        next
    }

    private suspend fun ensureLoadedLocked() {
        if (!loaded) {
            loaded = true
            if (file.exists()) {
                _items.value = try {
                    val text = file.readText()
                    if (text.isBlank()) emptyList() else decodeIndex(JsonValue.parseObject(text))
                } catch (t: Throwable) {
                    Log.w(tag, "index unreadable: ${t.message}")
                    lastError = "index file unreadable: ${t.message}"
                    emptyList()
                }
            }
        }
    }

    private suspend fun persistLocked(items: List<T>) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val tmp = File(directory, "$fileName.tmp")
        try {
            tmp.writeText(encodeIndex(items).toPrettyString())
            if (!tmp.renameTo(file)) {
                // renameTo fails across filesystems or when the target exists on
                // some devices: fall back to a full write.
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            lastError = "could not persist $fileName: ${t.message}"
            Log.e(tag, "persist failed", t)
            throw t
        }
    }
}

/** Model library index (`filesDir/models/index.json`). */
class ModelIndexStore(rootDir: File, scope: CoroutineScope) : JsonIndexStore<ModelDescriptor>(
    directory = File(rootDir, "models"),
    fileName = "index.json",
    scope = scope,
    tag = "ModelIndexStore",
    encodeIndex = { ModelDescriptorJson.encodeIndex(it) },
    decodeIndex = { ModelDescriptorJson.decodeIndex(it) },
)

/** Benchmark report history (`filesDir/benchmarks/index.json`). */
class BenchmarkIndexStore(rootDir: File, scope: CoroutineScope) : JsonIndexStore<BenchmarkReport>(
    directory = File(rootDir, "benchmarks"),
    fileName = "index.json",
    scope = scope,
    tag = "BenchmarkIndexStore",
    encodeIndex = { reports ->
        com.slop4hexagon.core.json.JsonObjectBuilder()
            .put("schemaVersion", BenchmarkReport.SCHEMA_VERSION)
            .put("reports", JsonValue.JsonArray(reports.map { BenchmarkReportJson.encode(it) }))
            .build()
    },
    decodeIndex = { obj ->
        obj.getArray("reports")?.items?.map {
            BenchmarkReportJson.decode(it as JsonValue.JsonObject)
        } ?: emptyList()
    },
)
