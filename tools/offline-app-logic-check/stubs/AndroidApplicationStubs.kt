/*
 * Compile-only stub for android.app.Application, so the app's composition root
 * (SlopApplication / AppContainer) can be type-checked too.
 */
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "ClassName")

package android.app

import android.content.Context

open class Application : Context() {
    open fun onCreate() {}
    open fun attachBaseContext(base: Context?) {}
}
