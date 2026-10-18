@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "ClassName")

package android.content.pm

class PackageInfo {
    var versionName: String? = null
    var longVersionCode: Long = 0L
}

class PackageManager {
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo = PackageInfo()
}
