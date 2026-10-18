package com.slop4hexagon.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val originalFileName: String,
    val storedPath: String,
    val fileSizeBytes: Long,
    val format: String, // ModelFormat name
    val sha256: String?,
    val importTimestampMillis: Long,
    val estimatedMemoryBytes: Long?
)

@Entity(tableName = "benchmarks")
data class BenchmarkEntity(
    @PrimaryKey val id: String,
    val json: String,
    val timestampMillis: Long,
    val backendId: String,
    val mode: String
)
