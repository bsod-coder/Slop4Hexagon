package com.slop4hexagon.app.nativebridge

import android.util.Log

/**
 * Kotlin half of the isolated QNN / QAIRT boundary.
 *
 * What this can honestly tell you:
 *  * whether a Qualcomm QNN backend library can be `dlopen`ed in this process,
 *  * whether it exports the documented entry point `QnnInterface_getProviders`.
 *
 * What it must never be used to claim: that the Hexagon HTP executed anything.
 * No QNN context is created and no operator is executed here, so the strongest
 * status the adapter may report is
 * [AVAILABLE][com.slop4hexagon.core.backend.BackendStatus.AVAILABLE].
 */
object QnnNativeProbe {

    private const val TAG = "QnnNativeProbe"

    /** Probed entry point, per Qualcomm's "loading a backend" documentation. */
    const val QNN_ENTRY_POINT = "QnnInterface_getProviders"

    /**
     * Backend libraries in preference order. `libQairtHtp.so` is the QAIRT-era
     * rename of `libQnnHtp.so`; both are probed because SDK versions differ.
     * The Hexagon-side skeleton (libQnnHtpV<arch>Skel.so) is deliberately not
     * probed here: it is loaded by the QNN runtime on the DSP, not by the app.
     */
    val HTP_LIBRARY_CANDIDATES = listOf("libQnnHtp.so", "libQairtHtp.so")

    enum class Outcome {
        /** Probe inputs rejected by the native validator. */
        INVALID,

        /** Library not present / not loadable in this process. */
        LIBRARY_NOT_FOUND,

        /** Library loaded but the QNN entry point is missing. */
        ENTRY_POINT_MISSING,

        /** Library loaded and the documented entry point resolved. */
        ENTRY_POINT_FOUND,
    }

    data class Probe(val outcome: Outcome, val detail: String)

    /**
     * Loading the probe entry point needs the same shared library as the CPU
     * engine. Resolved once (lazy): [System.loadLibrary] is idempotent, but the
     * probe should not repeat it on every call.
     */
    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("slop_native")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "slop_native not loadable, QNN probe unavailable: ${t.message}")
            false
        }
    }

    /**
     * Probes every candidate library and returns the best outcome found,
     * together with per-library details for the diagnostics screen.
     */
    fun probeHtpBackend(candidates: List<String> = HTP_LIBRARY_CANDIDATES): QnnProbeSummary {
        if (!loaded) {
            return QnnProbeSummary(
                Outcome.INVALID,
                "native library not loaded; cannot probe for QNN runtime",
                candidates.map { Probe(Outcome.INVALID, "$it: probe unavailable (native library missing)") },
            )
        }
        val details = candidates.map { name ->
            val raw: Array<String>? = try {
                nativeProbeLibrary(name, QNN_ENTRY_POINT)
            } catch (t: Throwable) {
                arrayOf("-2", "probe threw ${t::class.java.simpleName}: ${t.message}")
            }
            val code = raw?.getOrNull(0)?.toIntOrNull() ?: -2
            val detail = raw?.getOrNull(1) ?: "no detail returned"
            Probe(fromCode(code), detail)
        }
        val best = details.maxByOrNull { it.outcome.rank() } ?: Probe(Outcome.INVALID, "no candidates")
        return QnnProbeSummary(best.outcome, details.joinToString("; ") { "${it.outcome}: ${it.detail}" }, details)
    }

    private fun fromCode(code: Int): Outcome = when (code) {
        2 -> Outcome.ENTRY_POINT_FOUND
        1 -> Outcome.ENTRY_POINT_MISSING
        0 -> Outcome.LIBRARY_NOT_FOUND
        else -> Outcome.INVALID
    }

    private fun Outcome.rank(): Int = when (this) {
        Outcome.INVALID -> 0
        Outcome.LIBRARY_NOT_FOUND -> 1
        Outcome.ENTRY_POINT_MISSING -> 2
        Outcome.ENTRY_POINT_FOUND -> 3
    }

    private external fun nativeProbeLibrary(libraryName: String, symbolName: String): Array<String>?

    data class QnnProbeSummary(val outcome: Outcome, val headline: String, val perLibrary: List<Probe>)
}
