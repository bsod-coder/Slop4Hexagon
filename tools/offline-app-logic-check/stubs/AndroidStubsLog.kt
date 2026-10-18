/*
 * Compile-only stubs for the small slice of the Android / AndroidX API that the
 * app's *non-UI* sources touch. This lets `tools/offline-app-logic-check` type
 * check the repository/adapter/backend layer in sandboxes with no Android SDK
 * and no Maven access (compileSdk jars and AARs are simply not downloadable).
 *
 * This is NOT a test double and NOT a fake runtime: nothing here is compiled
 * into the app (it lives under tools/, outside any source set), and it must
 * never be used to claim that the app was built. The real build is
 * `./gradlew :app:assembleDebug` with a normal Android SDK.
 */
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch", "RedundantVisibilityModifier")

package android.util

object Log {
    fun v(tag: String, msg: String): Int = 0
    fun d(tag: String, msg: String): Int = 0
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String, tr: Throwable?): Int = 0
    fun e(tag: String, msg: String): Int = 0
    fun e(tag: String, msg: String, tr: Throwable?): Int = 0
}
