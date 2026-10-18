package com.slop4hexagon.app.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.slop4hexagon.app.nativebridge.NativeCpuEngine
import com.slop4hexagon.core.backend.BackendStatusInfo
import com.slop4hexagon.core.benchmark.DeviceReport
import com.slop4hexagon.core.benchmark.MemorySnapshot
import com.slop4hexagon.core.benchmark.ThermalSnapshot
import com.slop4hexagon.core.diagnostics.DeviceSnapshot

/**
 * Device/runtime facts collected through public Android APIs only.
 *
 * Anything the platform will not tell us is reported as null and annotated,
 * never guessed. In particular nothing here derives a Hexagon architecture
 * version: [DeviceSnapshot.hexagonVerified] is set from real executed HTP
 * inference only, which this build cannot produce.
 */
object DeviceInfo {

    fun snapshot(
        context: Context,
        appVersion: String,
        backendStatuses: List<BackendStatusInfo>,
        engine: NativeCpuEngine?,
        hexagonVerified: Boolean,
    ): DeviceSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
        val mem = am?.let { runCatching { ActivityManager.MemoryInfo().also { info -> it.getMemoryInfo(info) } }.getOrNull() }
        return DeviceSnapshot(
            socModel = readString { if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null },
            hardware = readString { Build.HARDWARE },
            supportedAbis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            manufacturer = readString { Build.MANUFACTURER },
            brand = readString { Build.BRAND },
            model = readString { Build.MODEL },
            totalMemBytes = mem?.totalMem?.takeIf { it > 0 },
            availMemBytes = mem?.availMem?.takeIf { it > 0 },
            appVersion = appVersion,
            backendStatuses = backendStatuses,
            hexagonVerified = hexagonVerified,
            notes = buildList {
                addAll(DeviceSnapshot.STANDARD_NOTES)
                if (mem == null) add("ActivityManager memory info was unavailable on this device")
                engine?.peakRssBytes()?.takeIf { it > 0 }?.let {
                    add("Peak RSS from native getrusage(): ${it / 1024 / 1024} MiB")
                }
                add("monotonic clock in use: SystemClock.elapsedRealtimeNanos() (uptime since boot, unaffected by wall-clock changes)")
            },
        )
    }

    fun report(context: Context, appVersion: String, runtimeDescription: String): DeviceReport {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
        val mem = runCatching { ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) } }.getOrNull()
        return DeviceReport(
            socModel = readString { if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null },
            hardware = readString { Build.HARDWARE },
            supportedAbis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            manufacturer = readString { Build.MANUFACTURER },
            model = readString { Build.MODEL },
            totalMemBytes = mem?.totalMem?.takeIf { it > 0 },
            availMemBytes = mem?.availMem?.takeIf { it > 0 },
            appVersion = appVersion,
            runtimeDescription = runtimeDescription,
        )
    }

    /**
     * Point-in-time memory sample. [peakRssBytes] comes from the native
     * `getrusage` probe when the CPU engine is loaded; the rest is current, and
     * the snapshot's note field says so — we never claim to have found a true
     * peak without a sampling mechanism that reports one.
     */
    fun memorySnapshot(engine: NativeCpuEngine?): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val nativeHeap = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrNull()
        return MemorySnapshot(
            javaHeapUsedBytes = (runtime.totalMemory() - runtime.freeMemory()).takeIf { it > 0 },
            nativeHeapUsedBytes = nativeHeap?.takeIf { it > 0 },
            totalPssBytes = null,
            peakRssBytes = engine?.peakRssBytes()?.takeIf { it > 0 },
            note = if (engine != null) {
                "javaHeap/nativeHeap are current values; peakRss is getrusage(RUSAGE_SELF).ru_maxrss " +
                    "from the native engine (a real high-water mark for this process)"
            } else {
                "native engine unavailable, so no peak-RSS source exists; only current values reported"
            },
        )
    }

    /** TotalPss via the framework's own memory stats; slow, so only on request. */
    fun totalPssBytes(context: Context): Long? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager? ?: return null
        return runCatching {
            val infos = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
            infos?.firstOrNull()?.totalPss?.times(1024L)?.takeIf { it > 0 }
        }.onFailure { Log.w(TAG, "getProcessMemoryInfo failed", it) }.getOrNull()
    }

    fun thermalSnapshot(context: Context): ThermalSnapshot {
        if (Build.VERSION.SDK_INT < 29) {
            return ThermalSnapshot(null, null, "thermal status requires API 29+; this device runs ${Build.VERSION.SDK_INT}")
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return ThermalSnapshot(null, null, "PowerManager unavailable")
        val status = runCatching { pm.currentThermalStatus }.getOrNull()
        return ThermalSnapshot(
            thermalStatus = status,
            thermalStatusName = status?.let { thermalName(it) },
            note = if (status == null) {
                "PowerManager.currentThermalStatus() threw; no thermal data available"
            } else {
                "PowerManager.currentThermalStatus() at sample time " +
                    "(elapsedRealtime ${SystemClock.elapsedRealtime() / 1000}s since boot)"
            },
        )
    }

    private fun thermalName(value: Int): String = when (value) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($value)"
    }

    /** Treating missing/restricted Build fields as normal, per the spec. */
    private inline fun readString(block: () -> String?): String? = runCatching {
        block()?.takeIf { it.isNotBlank() && it != "unknown" }
    }.getOrNull()

    private const val TAG = "DeviceInfo"
}
