/*
 * Compile-only stubs — android.content / net / graphics / provider / app.
 */
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "ClassName")

package android.content

import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream

open class Context {
    open val applicationContext: Context get() = this
    open val filesDir: File get() = File("/tmp")
    open val cacheDir: File get() = File("/tmp")
    open val packageName: String get() = "com.slop4hexagon.app"
    open val contentResolver: ContentResolver get() = ContentResolver()
    open val packageManager: PackageManager get() = PackageManager()

    open fun getSystemService(name: String): Any? = null

    companion object {
        const val ACTIVITY_SERVICE = "activity"
        const val POWER_SERVICE = "power"
    }
}

class ContentResolver {
    fun openInputStream(uri: Uri): InputStream? = null
    fun openOutputStream(uri: Uri): OutputStream? = null
    fun openOutputStream(uri: Uri, mode: String): OutputStream? = null
    fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    fun insert(uri: Uri, values: ContentValues?): Uri? = null
    fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
}

interface Cursor : java.io.Closeable {
    fun moveToFirst(): Boolean
    fun getString(index: Int): String
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int
    fun isNull(index: Int): Boolean
    fun getColumnIndex(columnName: String): Int
}

class ContentValues {
    fun put(key: String, value: String?) {}
    fun put(key: String, value: Int?) {}
    fun put(key: String, value: Long?) {}
    fun put(key: String, value: Boolean?) {}
}

