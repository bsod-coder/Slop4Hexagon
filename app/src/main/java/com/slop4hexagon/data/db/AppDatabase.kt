package com.slop4hexagon.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY importTimestampMillis DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ModelEntity?

    @Query("SELECT * FROM models WHERE sha256 = :hash AND fileSizeBytes = :size LIMIT 1")
    suspend fun findDuplicate(hash: String, size: Long): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ModelEntity)

    @Delete
    suspend fun delete(entity: ModelEntity)

    @Query("SELECT * FROM benchmarks ORDER BY timestampMillis DESC")
    fun observeBenchmarks(): Flow<List<BenchmarkEntity>>

    @Query("SELECT * FROM benchmarks WHERE id = :id LIMIT 1")
    suspend fun getBenchmark(id: String): BenchmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBenchmark(entity: BenchmarkEntity)

    @Query("DELETE FROM benchmarks WHERE id = :id")
    suspend fun deleteBenchmark(id: String)
}

@Database(entities = [ModelEntity::class, BenchmarkEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
}
