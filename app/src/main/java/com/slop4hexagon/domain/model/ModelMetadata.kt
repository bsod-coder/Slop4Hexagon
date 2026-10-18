package com.slop4hexagon.domain.model

import kotlinx.serialization.Serializable

/**
 * Persisted metadata for an imported model. File itself lives in app-managed storage.
 * Original user file is never deleted.
 */
@Serializable
data class ModelMetadata(
    val id: String, // UUID
    val displayName: String,
    val originalFileName: String,
    val storedPath: String, // internal app file path
    val fileSizeBytes: Long,
    val format: ModelFormat,
    val sha256: String?, // hex, optional - computed on first 64k + size for dedup
    val importTimestampMillis: Long,
    val compatibility: Map<String, CompatibilityStatus> = emptyMap(), // backendId -> status
    val estimatedMemoryBytes: Long? = null,
    val notes: String? = null
)

enum class CompatibilityStatus {
    COMPATIBLE,
    COMPATIBLE_WITH_CONVERSION, // e.g., SD checkpoint needs QNN conversion
    INCOMPATIBLE_FORMAT,
    INCOMPATIBLE_SIZE, // too large for 8GB device
    UNKNOWN
}

data class ModelDetails(
    val metadata: ModelMetadata,
    val isDuplicate: Boolean = false,
    val duplicateOfId: String? = null,
    val supportedBackends: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)
