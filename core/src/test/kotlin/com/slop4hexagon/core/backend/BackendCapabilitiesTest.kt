package com.slop4hexagon.core.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendCapabilitiesTest {

    @Test
    fun snapshotReportsLiveStatusAndCapabilities() {
        val cpu = FakeBackend(
            BackendId.CPU,
            BackendStatus.INITIALIZED,
            BackendCapabilities(
                supportedOps = setOf(OpKind.MATMUL_F32, OpKind.CONV2D_F32),
                supportsMicroBenchmarks = true,
                supportsImagePipeline = false,
                maxTensorElements = 42,
                notes = listOf("real ops"),
            ),
            detail = "cpu ready",
        )
        val snapshot = BackendManager(listOf(cpu)).statusSnapshot()
        assertEquals(1, snapshot.size)
        val row = snapshot[0]
        assertEquals(BackendId.CPU, row.id)
        assertEquals(BackendStatus.INITIALIZED, row.status)
        assertEquals("cpu ready", row.detail)
        assertEquals(setOf(OpKind.MATMUL_F32, OpKind.CONV2D_F32), row.capabilities!!.supportedOps)
        assertEquals(42L, row.capabilities!!.maxTensorElements)
        assertFalse(row.capabilities!!.supportsImagePipeline)
        assertFalse(row.fallback)
    }

    @Test
    fun imagePipelineCapabilityIsNeverImpliedByMicroOps() {
        val caps = BackendCapabilities.NONE
        assertFalse(caps.supportsMicroBenchmarks)
        assertFalse(caps.supportsImagePipeline)
        assertTrue(caps.supportedOps.isEmpty())
    }

    @Test
    fun statusUsabilityRules() {
        assertTrue(BackendStatus.AVAILABLE.isUsable())
        assertTrue(BackendStatus.INITIALIZED.isUsable())
        assertTrue(BackendStatus.VERIFIED_EXECUTION.isUsable())
        assertFalse(BackendStatus.UNAVAILABLE.isUsable())
        assertFalse(BackendStatus.UNSUPPORTED.isUsable())
        assertFalse(BackendStatus.FAILED.isUsable())
        assertFalse(BackendStatus.FALLBACK_OCCURRED.isUsable())
    }

    @Test
    fun initializationFailureIsReportedNotHidden() = kotlinx.coroutines.runBlocking {
        val broken = FakeBackend(BackendId.HTP_QNN, BackendStatus.AVAILABLE, failInitialize = true, detail = "missing libQnnHtp.so")
        val cpu = FakeBackend(BackendId.CPU)
        val manager = BackendManager(listOf(cpu, broken))
        val selection = manager.select(BackendPreference.HTP)
        val report = manager.initializeSelection(selection)
        assertTrue(report.outcome is InitOutcome.Failure)
        val failure = report.outcome as InitOutcome.Failure
        assertTrue(failure.detail.contains("missing libQnnHtp.so"))
        assertEquals(BackendStatus.FAILED, broken.status())
        // The manager must not silently swap to CPU a second time behind the user's back.
        assertEquals(BackendId.HTP_QNN, report.selection.chosen?.id)
    }

    @Test
    fun successfulInitializeMovesStatusToInitialized() = kotlinx.coroutines.runBlocking {
        val cpu = FakeBackend(BackendId.CPU)
        val manager = BackendManager(listOf(cpu))
        val report = manager.initializeSelection(manager.select(BackendPreference.CPU))
        assertTrue(report.outcome is InitOutcome.Success)
        assertEquals(BackendStatus.INITIALIZED, cpu.status())
    }

    @Test
    fun executeFailureReturnsStructuredError() = kotlinx.coroutines.runBlocking {
        val cpu = FakeBackend(BackendId.CPU, failExecute = true)
        val result = cpu.execute(
            InferenceRequest.MicroOpRequest(OpKind.MATMUL_F32, 2, 2, 2, FloatArray(4), FloatArray(4)),
        )
        assertTrue(result is InferenceResult.Failure)
        val error = (result as InferenceResult.Failure).error
        assertTrue(error is InferenceError.ExecutionFailed)
        assertTrue(error.describe().contains("Execution failed"))
    }

    @Test
    fun verifiedExecutionIsEarnedByRealExecution() = kotlinx.coroutines.runBlocking {
        val cpu = FakeBackend(BackendId.CPU)
        assertEquals(BackendStatus.AVAILABLE, cpu.status())
        cpu.execute(InferenceRequest.MicroOpRequest(OpKind.MATMUL_F32, 1, 1, 1, FloatArray(1), FloatArray(1)))
        assertEquals(BackendStatus.VERIFIED_EXECUTION, cpu.status())
    }
}
