package com.slop4hexagon.core.settings

import com.slop4hexagon.core.backend.BackendPreference
import com.slop4hexagon.core.benchmark.BenchmarkConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun defaultsMatchBenchmarkDefaults() {
        val settings = AppSettings()
        assertEquals(BackendPreference.AUTO, settings.backendPreference)
        assertEquals(BenchmarkConfig.DEFAULT_WARMUP, settings.warmupIterations)
        assertEquals(BenchmarkConfig.DEFAULT_MEASURED, settings.measuredIterations)
    }

    @Test
    fun invalidValuesAreClampedNotFatal() {
        val settings = AppSettings(
            warmupIterations = -5,
            measuredIterations = 100_000,
            maxStoredReports = 0,
        ).validate()
        assertEquals(0, settings.warmupIterations)
        assertEquals(BenchmarkConfig.MAX_ITERATIONS, settings.measuredIterations)
        assertEquals(1, settings.maxStoredReports)
    }

    @Test
    fun fromRawFallsBackOnUnknownEnums() {
        val settings = AppSettings.fromRaw(
            backendPreference = "SOMETHING_ELSE",
            themeMode = "ALSO_WRONG",
            dynamicColor = null,
            warmupIterations = null,
            measuredIterations = null,
            maxStoredReports = null,
        )
        assertEquals(BackendPreference.AUTO, settings.backendPreference)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
    }

    @Test
    fun fromRawHonoursKnownValues() {
        val settings = AppSettings.fromRaw(
            backendPreference = "HTP",
            themeMode = "DARK",
            dynamicColor = false,
            warmupIterations = 3,
            measuredIterations = 10,
            maxStoredReports = 20,
        )
        assertEquals(BackendPreference.HTP, settings.backendPreference)
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(false, settings.dynamicColor)
        assertEquals(3, settings.warmupIterations)
        assertEquals(10, settings.measuredIterations)
    }
}
