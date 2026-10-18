package com.slop4hexagon.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.slop4hexagon.core.model.ImportDecision
import com.slop4hexagon.core.model.ImportPlanner
import com.slop4hexagon.core.model.ModelDescriptor
import com.slop4hexagon.core.model.ModelInspection
import com.slop4hexagon.core.model.ModelInspector
import com.slop4hexagon.core.model.ModelNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Imports user-selected model files through the Storage Access Framework and
 * owns app-managed copies.
 *
 * Properties that exist because model files are large:
 *  * the file is streamed in fixed-size chunks — never fully in memory;
 *  * the format header (first [HEADER_PROBE_BYTES]) is retained from the same
 *    pass, so inspection costs no extra I/O;
 *  * SHA-256 is computed while copying, which is what makes duplicate detection
 *    reliable;
 *  * the copy lands in a `*.part` file and is only renamed after a complete,
 *    cancelled-safe write;
 *  * original user files are only ever read, never modified or deleted.
 */
class ModelRepository(
    private val context: Context,
    private val indexStore: ModelIndexStore,
    private val scope: CoroutineScope,
) {

    val models: StateFlow<List<ModelDescriptor>> = indexStore.items

    @Volatile
    var importProgress: ImportProgress? = null
        private set

    private val _progress = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = _progress.asStateFlow()

    data class ImportProgress(
        val fileName: String,
        val copiedBytes: Long,
        val totalBytes: Long,
        val phase: Phase,
    ) {
        enum class Phase { READING, WRITING, VERIFYING, DONE }

        fun fraction(): Double = if (totalBytes > 0) (copiedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0) else 0.0
    }

    sealed class ImportResult {
        data class Imported(val descriptor: ModelDescriptor, val warnings: List<String>) : ImportResult()
        data class Duplicate(val existing: ModelDescriptor, val reason: String) : ImportResult()
        data class Rejected(val reason: String) : ImportResult()
        data class Failed(val message: String) : ImportResult()
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri) ?: "model.bin"
        val declaredSize = querySize(uri)
        val targetDir = File(context.filesDir, "models").apply { mkdirs() }

        var outFile: File? = null
        try {
            _progress.value = ImportProgress(displayName, 0, declaredSize, ImportProgress.Phase.READING)
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(HEADER_PROBE_BYTES)
            var headerFilled = 0
            var total = 0L

            val tmp = File(targetDir, "import-${System.currentTimeMillis()}.part")
            outFile = tmp
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        if (headerFilled < HEADER_PROBE_BYTES) {
                            val take = minOf(read, HEADER_PROBE_BYTES - headerFilled)
                            System.arraycopy(buffer, 0, header, headerFilled, take)
                            headerFilled += take
                        }
                        total += read
                        _progress.value = ImportProgress(displayName, total, declaredSize, ImportProgress.Phase.WRITING)
                    }
                    output.flush()
                }
            } ?: return@withContext ImportResult.Failed("content provider returned no stream for $displayName")

            _progress.value = ImportProgress(displayName, total, total, ImportProgress.Phase.VERIFYING)
            val sha256 = digest.encodeHex()
            val headerPrefix = if (headerFilled < header.size) header.copyOf(headerFilled) else header

            val inspection = ModelInspector.inspect(displayName, total, headerPrefix)
            val existing = indexStore.ensureLoaded()
            when (val decision = ImportPlanner.plan(displayName, total, sha256, inspection, existing)) {
                is ImportDecision.Duplicate -> {
                    tmp.delete()
                    outFile = null
                    ImportResult.Duplicate(
                        existing = existing.first { it.id == decision.existingId },
                        reason = decision.reason,
                    )
                }
                is ImportDecision.UnsupportedFormat -> {
                    tmp.delete()
                    outFile = null
                    ImportResult.Rejected(decision.reason)
                }
                is ImportDecision.Proceed -> {
                    val id = "mdl-" + sha256.substring(0, 16)
                    val finalFile = File(targetDir, id)
                    if (finalFile.exists()) {
                        tmp.delete()
                        outFile = null
                        indexStore.update { list -> list.filter { it.id != id } + descriptorFor(id, displayName, total, sha256, inspection) }
                        return@withContext ImportResult.Duplicate(
                            existing = descriptorFor(id, displayName, total, sha256, inspection),
                            reason = "An identical file is already stored under $id",
                        )
                    }
                    if (!tmp.renameTo(finalFile)) {
                        tmp.copyTo(finalFile, overwrite = true)
                        tmp.delete()
                    }
                    outFile = null
                    val descriptor = descriptorFor(id, displayName, total, sha256, inspection)
                    indexStore.update { list -> list.filterNot { it.id == id } + descriptor }
                    Log.i(TAG, "imported $id ($total bytes, ${inspection.format.name})")
                    ImportResult.Imported(descriptor, decision.duplicateWarnings)
                }
            }
        } catch (t: Throwable) {
            outFile?.delete()
            _progress.value = null
            Log.w(TAG, "import failed", t)
            ImportResult.Failed("import failed: ${t::class.java.simpleName}: ${t.message ?: "no detail"}")
        } finally {
            _progress.value = null
        }
    }

    private fun descriptorFor(
        id: String,
        fileName: String,
        size: Long,
        sha256: String,
        inspection: ModelInspection,
    ) = ModelDescriptor(
        id = id,
        displayName = ModelNames.displayNameFor(fileName, inspection.format),
        originalFileName = fileName,
        sizeBytes = size,
        sha256 = sha256,
        importedAtEpochMs = System.currentTimeMillis(),
        inspection = inspection,
    )

    /**
     * Removes a model from app-managed storage. Only ever touches the copy this
     * app created; the user's original file is never deleted (there is no code
     * path that takes a write/delete permission on it).
     */
    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        val before = indexStore.ensureLoaded().size
        val after = indexStore.update { list -> list.filterNot { it.id == id } }.size
        runCatching { managedDelete(id) }.onFailure { Log.w(TAG, "could not delete managed copy of $id", it) }
        Log.i(TAG, "removed $id (library $before -> $after entries)")
        after < before
    }

    /** Deletes only the copy this app created inside its own private dir. */
    private fun managedDelete(id: String) {
        require(ID_PATTERN.matches(id)) { "refusing to delete unexpected model id" }
        File(context.filesDir, "models/$id").delete()
    }

    fun managedFile(descriptor: ModelDescriptor): File = File(context.filesDir, "models/${descriptor.id}")

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
            } else {
                0L
            }
        } ?: 0L
    }.getOrDefault(0L)

    private fun MessageDigest.encodeHex(): String {
        val bytes = digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "ModelRepository"
        const val HEADER_PROBE_BYTES = 64 * 1024
        const val COPY_BUFFER_BYTES = 256 * 1024
        private val HEX = "0123456789abcdef".toCharArray()

        /** Model ids are content-addressed hex strings with a fixed prefix. */
        private val ID_PATTERN = Regex("mdl-[0-9a-f]{16}")
    }
}
