package com.slop4hexagon.domain.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.slop4hexagon.BuildConfig
import com.slop4hexagon.domain.model.DeviceInfo

class DeviceDiagnostics(private val context: Context) {
    fun collect(): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val socModel = try {
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null
        } catch (_: Exception) { null }

        val hardware = try { Build.HARDWARE } catch (_: Exception) { null }
        val abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        val thermal = try {
            if (Build.VERSION.SDK_INT >= 29) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.currentThermalStatus
            } else null
        } catch (_: Exception) { null }

        return DeviceInfo(
            socModel = socModel,
            hardware = hardware,
            supportedAbis = abis,
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            apiLevel = Build.VERSION.SDK_INT,
            totalMemMb = memInfo.totalMem / (1024 * 1024),
            availMemMb = memInfo.availMem / (1024 * 1024),
            lowMemory = memInfo.lowMemory,
            thermalStatus = thermal,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_COMMIT}",
            backendStatuses = emptyMap()
        )
    }
}
