/*
 * Compile-only stub for android.os.PowerManager (thermal status API).
 */
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "ClassName")

package android.os

class PowerManager {
    val currentThermalStatus: Int get() = 0

    companion object {
        const val THERMAL_STATUS_NONE = 0
        const val THERMAL_STATUS_LIGHT = 1
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
        const val THERMAL_STATUS_CRITICAL = 4
        const val THERMAL_STATUS_EMERGENCY = 5
        const val THERMAL_STATUS_SHUTDOWN = 6
    }
}
