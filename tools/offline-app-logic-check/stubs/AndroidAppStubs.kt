@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "ClassName")

package android.app

import android.content.Context
import android.os.Debug

class ActivityManager(private val context: Context? = null) {
    class MemoryInfo {
        var totalMem: Long = 0L
        var availMem: Long = 0L
        var lowBytes: Long = 0L
    }

    fun getMemoryInfo(outInfo: MemoryInfo) {}
    fun getProcessMemoryInfo(pids: IntArray): Array<Debug.MemoryInfo>? = null

    companion object {
        const val MEMORY_CATEGORY_HIDDEN = 5
    }
}
