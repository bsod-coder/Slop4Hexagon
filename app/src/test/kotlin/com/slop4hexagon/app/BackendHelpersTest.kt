package com.slop4hexagon.app

import com.slop4hexagon.app.backend.CpuInferenceBackend
import com.slop4hexagon.app.nativebridge.NativeError
import com.slop4hexagon.app.nativebridge.NativeSelfTestResult
import com.slop4hexagon.app.platform.ImageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the parts of the app layer that carry real logic and need no
 * Android framework: INT8 quantization, native error mapping, bitmap
 * downscale-factor maths and export file naming.
 *
 * These run under `./gradlew :app:testDebugUnitTest` and also in restricted
 * environments via tools/offline-app-logic-check/run-app-logic-tests.sh.
 */
class BackendHelpersTest {

    @Test
    fun int8QuantizationClampsAndRounds() {
        val input = floatArrayOf(0f, 1f, -1f, 2f, -3f, 0.5f, -0.5f, Float.NaN)
        val q = CpuInferenceBackend.quantizeToInt8(input)
        assertEquals(0, q[0].toInt())
        assertEquals(127, q[1].toInt())
        assertEquals(-127, q[2].toInt())
        assertEquals(127, q[3].toInt())
        assertEquals(-127, q[4].toInt())
        // Math.round(x) is floor(x + 0.5): ties round up, so the negative side
        // lands on -63 rather than -64. Asserted here so the asymmetry is
        // deliberate and visible, not an accident someone "fixes" blindly.
        assertEquals(64, q[5].toInt())
        assertEquals(-63, q[6].toInt())
        assertEquals(0, q[7].toInt())     // NaN must never become garbage
    }

    @Test
    fun nativeErrorCodesMapToDistinctDescriptions() {
        val expected = listOf(
            -1 to NativeError.NullPointer,
            -2 to NativeError.BadDimensions,
            -3 to NativeError.BufferTooSmall,
            -4 to NativeError.Overflow,
            -5 to NativeError.Unsupported,
        )
        for ((code, error) in expected) {
            val mapped = NativeError.fromCode(code)
            assertEquals(error::class.simpleName, mapped::class.simpleName)
            assertTrue(mapped.describe().isNotEmpty())
        }
        val unknown = NativeError.fromCode(-99)
        assertTrue(unknown is NativeError.Unknown)
        assertEquals(-99, (unknown as NativeError.Unknown).code)
        assertTrue(unknown.describe().contains("-99"))
    }

    @Test
    fun libraryMissingErrorNamesTheLibrary() {
        assertTrue(NativeError.LibraryMissing.describe().contains("slop_native"))
    }

    @Test
    fun selfTestResultDistinguishesUnavailableFromFailed() {
        val missing = NativeSelfTestResult(-1, "native library not loaded")
        assertTrue(!missing.passed)
        assertTrue(!missing.available)
        val failed = NativeSelfTestResult(3, null)
        assertTrue(!failed.passed)
        assertTrue(failed.available)
        val ok = NativeSelfTestResult(0, null)
        assertTrue(ok.passed)
        assertTrue(ok.available)
    }

    @Test
    fun inSampleSizeIsPowerOfTwoAndNeverZero() {
        assertEquals(1, ImageStore.computeInSampleSize(512, 512, 512))
        assertEquals(2, ImageStore.computeInSampleSize(2048, 1536, 1024))
        assertEquals(4, ImageStore.computeInSampleSize(4096, 4096, 1024))
        for (side in intArrayOf(1, 3, 17, 1024, 8192, 65536)) {
            val sample = ImageStore.computeInSampleSize(side, side, 512)
            assertTrue("sample=$sample", sample >= 1 && (sample and (sample - 1)) == 0)
        }
    }

    @Test
    fun tinyImagesDoNotOverDownscale() {
        assertEquals(1, ImageStore.computeInSampleSize(8, 8, 1024))
        assertEquals(1, ImageStore.computeInSampleSize(64, 48, 512))
    }
}
