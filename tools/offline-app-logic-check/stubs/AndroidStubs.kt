/*
 * Compile-only stubs for the android.* surface used by the app's NON-UI
 * sources. See AndroidStubsLog.kt for why this exists and its limits.
 */
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "ClassName", "FunctionName")

package android.os

object Build {
    val HARDWARE: String = "stub"
    val MANUFACTURER: String = "stub"
    val BRAND: String = "stub"
    val MODEL: String = "stub"
    val SUPPORTED_ABIS: Array<String> = arrayOf("arm64-v8a")
    val SOC_MODEL: String = "stub"
    val SOC_MANUFACTURER: String = "stub"

    object VERSION {
        val SDK_INT: Int = 33
        val RELEASE: String = "13"
    }

    object VERSION_CODES {
        const val S: Int = 31
    }
}

object SystemClock {
    fun elapsedRealtime(): Long = 0L
    fun elapsedRealtimeNanos(): Long = 0L
}

object Process {
    fun myPid(): Int = 0
}

class Bundle

object Debug {
    fun getNativeHeapAllocatedSize(): Long = 0L

    class MemoryInfo {
        val totalPss: Int = 0
    }
}
