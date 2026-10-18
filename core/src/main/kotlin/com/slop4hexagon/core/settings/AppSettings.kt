package com.slop4hexagon.core.settings

import com.slop4hexagon.core.backend.BackendPreference
import com.slop4hexagon.core.benchmark.BenchmarkConfig

/** Theme selection; SYSTEM follows the Android system setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Small persistent app preferences. Values are validated on load; invalid
 * values fall back to defaults instead of crashing.
 */
data class AppSettings(
    val backendPreference: BackendPreference = BackendPreference.AUTO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val warmupIterations: Int = BenchmarkConfig.DEFAULT_WARMUP,
    val measuredIterations: Int = BenchmarkConfig.DEFAULT_MEASURED,
    /** How many exported benchmark reports to keep locally. */
    val maxStoredReports: Int = 50,
) {
    fun validate(): AppSettings = copy(
        warmupIterations = warmupIterations.coerceIn(0, BenchmarkConfig.MAX_ITERATIONS),
        measuredIterations = measuredIterations.coerceIn(1, BenchmarkConfig.MAX_ITERATIONS),
        maxStoredReports = maxStoredReports.coerceIn(1, 1000),
    )

    companion object {
        fun fromRaw(
            backendPreference: String?,
            themeMode: String?,
            dynamicColor: Boolean?,
            warmupIterations: Int?,
            measuredIterations: Int?,
            maxStoredReports: Int?,
        ): AppSettings = AppSettings(
            backendPreference = BackendPreference.entries.firstOrNull { it.name == backendPreference }
                ?: BackendPreference.AUTO,
            themeMode = ThemeMode.entries.firstOrNull { it.name == themeMode } ?: ThemeMode.SYSTEM,
            dynamicColor = dynamicColor ?: true,
            warmupIterations = warmupIterations ?: BenchmarkConfig.DEFAULT_WARMUP,
            measuredIterations = measuredIterations ?: BenchmarkConfig.DEFAULT_MEASURED,
            maxStoredReports = maxStoredReports ?: 50,
        ).validate()
    }
}
