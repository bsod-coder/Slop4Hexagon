package com.slop4hexagon

import com.slop4hexagon.domain.engine.BackendManager
import com.slop4hexagon.domain.engine.CpuBackend
import com.slop4hexagon.domain.engine.GpuBackend
import com.slop4hexagon.domain.engine.QnnHtpBackend
import com.slop4hexagon.domain.model.BackendId
import org.junit.Assert.*
import org.junit.Test

class BackendSelectionTest {
    @Test
    fun `AUTO prefers CPU when HTP and GPU unsupported`() {
        val mgr = BackendManager(listOf(CpuBackend(), GpuBackend(), QnnHtpBackend()))
        // Without context, GPU/QNN remain unsupported, CPU available
        val resolved = mgr.resolvePreferred(BackendId.AUTO)
        assertEquals(BackendId.CPU, resolved?.id)
    }

    @Test
    fun `explicit CPU resolved even when AUTO would pick same`() {
        val mgr = BackendManager(listOf(CpuBackend()))
        assertEquals(BackendId.CPU, mgr.resolvePreferred(BackendId.CPU)?.id)
    }

    @Test
    fun `fallback to CPU when preferred HTP unavailable`() {
        val mgr = BackendManager(listOf(CpuBackend(), QnnHtpBackend()))
        val resolved = mgr.resolvePreferred(BackendId.HTP)
        // HTP status unsupported -> fallback CPU
        assertNotNull(resolved)
        assertEquals(BackendId.CPU, resolved?.id)
    }

    @Test
    fun `no backend available returns null`() {
        val mgr = BackendManager(emptyList())
        assertNull(mgr.resolvePreferred(BackendId.AUTO))
    }

    @Test
    fun `capability reporting honest`() {
        val cpu = CpuBackend()
        assertTrue(cpu.capabilities.supportedFormats.isNotEmpty())
        assertFalse(cpu.capabilities.requiresConversion)
        val htp = QnnHtpBackend()
        assertTrue(htp.capabilities.requiresConversion)
        assertEquals(BackendId.HTP, htp.capabilities.backendId)
    }
}
