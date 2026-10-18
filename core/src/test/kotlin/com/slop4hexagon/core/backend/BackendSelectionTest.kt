package com.slop4hexagon.core.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSelectionTest {

    private fun manager(vararg backends: InferenceBackend) = BackendManager(backends.toList())

    @Test
    fun directPreferenceSelectsMatchingBackend() {
        val cpu = FakeBackend(BackendId.CPU)
        val htp = FakeBackend(BackendId.HTP_QNN, BackendStatus.AVAILABLE)
        val selection = manager(cpu, htp).select(BackendPreference.HTP)
        assertEquals(BackendId.HTP_QNN, selection.chosen?.id)
        assertFalse(selection.fallbackOccurred)
        assertEquals(BackendStatus.AVAILABLE, selection.status)
    }

    @Test
    fun autoPrefersAccelerator() {
        val cpu = FakeBackend(BackendId.CPU)
        val gpu = FakeBackend(BackendId.GPU)
        val htp = FakeBackend(BackendId.HTP_QNN)
        val selection = manager(cpu, gpu, htp).select(BackendPreference.AUTO)
        assertEquals(BackendId.HTP_QNN, selection.chosen?.id)
        assertFalse(selection.fallbackOccurred)
    }

    @Test
    fun autoFallsBackToCpuWhenAcceleratorsUnusable() {
        val cpu = FakeBackend(BackendId.CPU)
        val gpu = FakeBackend(BackendId.GPU, BackendStatus.UNAVAILABLE, detail = "no OpenCL libs")
        val htp = FakeBackend(BackendId.HTP_QNN, BackendStatus.UNAVAILABLE, detail = "QNN runtime missing")
        val selection = manager(cpu, gpu, htp).select(BackendPreference.AUTO)
        assertEquals(BackendId.CPU, selection.chosen?.id)
        assertFalse("AUTO ordering is not a fallback", selection.fallbackOccurred)
        assertTrue(selection.reasons.any { it.contains("QNN runtime missing") })
    }

    @Test
    fun explicitPreferenceMarksFallback() {
        val cpu = FakeBackend(BackendId.CPU)
        val htp = FakeBackend(BackendId.HTP_QNN, BackendStatus.FAILED, detail = "htp init blew up")
        val selection = manager(cpu, htp).select(BackendPreference.HTP)
        assertEquals(BackendId.CPU, selection.chosen?.id)
        assertTrue(selection.fallbackOccurred)
        assertEquals(BackendStatus.FALLBACK_OCCURRED, selection.status)
        assertTrue(selection.reasons.any { it.contains("htp init blew up") })
        assertTrue(selection.reasons.any { it.contains("fallback") })
    }

    @Test
    fun noUsableBackendYieldsNullSelection() {
        val cpu = FakeBackend(BackendId.CPU, BackendStatus.FAILED)
        val selection = manager(cpu).select(BackendPreference.CPU)
        assertNull(selection.chosen)
        assertEquals(BackendStatus.UNAVAILABLE, selection.status)
        assertTrue(selection.reasons.any { it.contains("No usable backend") })
    }

    @Test
    fun unregisteredPreferredBackendIsExplained() {
        val cpu = FakeBackend(BackendId.CPU)
        val selection = manager(cpu).select(BackendPreference.HTP)
        assertEquals(BackendId.CPU, selection.chosen?.id)
        assertTrue(selection.fallbackOccurred)
        assertTrue(selection.reasons.any { it.contains("not registered") })
    }

    @Test
    fun duplicateBackendIdsAreRejected() {
        try {
            manager(FakeBackend(BackendId.CPU), FakeBackend(BackendId.CPU))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun verifiedExecutionBackendIsUsable() {
        val htp = FakeBackend(BackendId.HTP_QNN, BackendStatus.VERIFIED_EXECUTION)
        val selection = manager(htp).select(BackendPreference.HTP)
        assertEquals(BackendId.HTP_QNN, selection.chosen?.id)
        assertFalse(selection.fallbackOccurred)
    }
}
