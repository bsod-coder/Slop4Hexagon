package com.slop4hexagon

import android.app.Application
import androidx.room.Room
import com.slop4hexagon.data.db.AppDatabase
import com.slop4hexagon.data.prefs.PreferencesManager
import com.slop4hexagon.data.repo.ModelRepository
import com.slop4hexagon.domain.benchmark.BenchmarkEngine
import com.slop4hexagon.domain.engine.BackendManager
import com.slop4hexagon.domain.engine.CpuBackend
import com.slop4hexagon.domain.engine.GpuBackend
import com.slop4hexagon.domain.engine.QnnHtpBackend

class SlopApp : Application() {

    val db by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "slop.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val prefs by lazy { PreferencesManager(this) }

    val backendManager by lazy {
        BackendManager(listOf(CpuBackend(), GpuBackend(), QnnHtpBackend()))
    }

    val modelRepository by lazy {
        ModelRepository(this, db.modelDao())
    }

    val benchmarkEngine by lazy {
        BenchmarkEngine(backendManager)
    }

    override fun onCreate() {
        super.onCreate()
        // Eager init CPU for diagnostics
        backendManager.initializeAll(this)
    }
}
