package com.slop4hexagon.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.slop4hexagon.data.db.ModelDao
import com.slop4hexagon.data.db.ModelEntity
import com.slop4hexagon.domain.model.ModelFormat
import com.slop4hexagon.domain.model.ModelFormatRegistry
import com.slop4hexagon.domain.model.estimateMemoryRequirementBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

class ModelRepository(
    private val context: Context,
    private val dao: ModelDao
) {
    fun observeModels(): Flow<List<ModelEntity>> = dao.observeAll()

    suspend fun getModel(id: String) = dao.getById(id)

    /**
     * Import via SAF without loading whole file into heap.
     * Streams in 8k chunks, computes hash on-the-fly for dedup.
     */
    suspend fun importModel(uri: Uri, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Result<ModelEntity> = withContext(Dispatchers.IO) {
        try {
            val cr = context.contentResolver
            val fileName = queryDisplayName(uri) ?: "model-${UUID.randomUUID()}"
            val size = querySize(uri) // may be -1

            // Read header for format detection: first 256 bytes
            val header = ByteArray(256)
            var headerRead = 0
            cr.openInputStream(uri)?.use { input ->
                headerRead = input.read(header)
            }
            val headerBytes = if (headerRead > 0) header.copyOf(headerRead) else null
            val format = ModelFormatRegistry.detect(fileName, headerBytes, size ?: -1)

            if (format == ModelFormat.UNKNOWN) {
                // Still import but warn - store as UNKNOWN
                Log.w("ModelRepository", "Unknown format for $fileName")
            }

            // Prepare destination
            val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val destFile = File(modelsDir, "${id}_${sanitizeFileName(fileName)}")

            // Stream copy + hash
            val digest = MessageDigest.getInstance("SHA-256")
            var totalCopied = 0L
            var fileSizeAccum = 0L
            cr.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        // Only hash first 64k + size for dup detection (avoid huge hash cost)
                        if (fileSizeAccum < 65536) {
                            val toHash = minOf(read, (65536 - fileSizeAccum).toInt())
                            digest.update(buf, 0, toHash)
                        }
                        output.write(buf, 0, read)
                        fileSizeAccum += read
                        totalCopied += read
                        if (totalCopied % (512 * 1024) == 0L) onProgress(totalCopied, size)
                    }
                }
            } ?: return@withContext Result.failure(IllegalArgumentException("Cannot open $uri"))

            // If SAF size was unknown, use actual
            val finalSize = if (size == null || size <= 0) fileSizeAccum else size
            val hashHex = digest.digest().joinToString("") { "%02x".format(it) }

            // Duplicate detection: same hash+size
            val duplicate = dao.findDuplicate(hashHex, finalSize)
            if (duplicate != null) {
                destFile.delete()
                return@withContext Result.failure(IllegalStateException("Duplicate of ${duplicate.displayName} (id=${duplicate.id})"))
            }

            // Large file guard: 8GB RAM device -> warn > 2GB
            if (finalSize > 2L * 1024 * 1024 * 1024) {
                Log.w("ModelRepository", "Large model ${finalSize} bytes may OOM")
            }

            val estimated = estimateMemoryRequirementBytes(format, finalSize)
            val entity = ModelEntity(
                id = id,
                displayName = fileName.substringBeforeLast('.'),
                originalFileName = fileName,
                storedPath = destFile.absolutePath,
                fileSizeBytes = finalSize,
                format = format.name,
                sha256 = hashHex,
                importTimestampMillis = System.currentTimeMillis(),
                estimatedMemoryBytes = estimated
            )
            dao.insert(entity)
            Result.success(entity)
        } catch (e: Exception) {
            Log.e("ModelRepository", "import failed", e)
            Result.failure(e)
        }
    }

    suspend fun deleteModel(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entity = dao.getById(id) ?: return@withContext Result.failure(NoSuchElementException("Model $id not found"))
            File(entity.storedPath).takeIf { it.exists() }?.delete()
            dao.delete(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun querySize(uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) c.getLong(idx).takeIf { it > 0 } else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
    }

    suspend fun readHeaderBytes(path: String, max: Int = 256): ByteArray? = withContext(Dispatchers.IO) {
        try {
            FileInputStream(File(path)).use { it.readBytes().take(max).toByteArray() }
        } catch (_: Exception) { null }
    }
}
