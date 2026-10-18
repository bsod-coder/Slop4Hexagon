package com.slop4hexagon.core.benchmark

import com.slop4hexagon.core.backend.OpKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicroOpPlansTest {

    @Test
    fun matmulPlanSizesInputsCorrectly() {
        val plan = MicroOpPlans.plan(OpKind.MATMUL_F32, rows = 64, inner = 256, cols = 64)
        assertEquals(64 * 256, plan.inputAElements)
        assertEquals(256 * 64, plan.inputBElements)
        assertEquals(listOf(64, 64), plan.outputShape)
        assertTrue(plan.validate(maxTensorElements = 1L shl 24).isEmpty())
    }

    @Test
    fun int8PlanSharesMatmulShapes() {
        val f32 = MicroOpPlans.plan(OpKind.MATMUL_F32, 8, 8, 8)
        val i8 = MicroOpPlans.plan(OpKind.MATMUL_INT8, 8, 8, 8)
        assertEquals(f32.inputAElements, i8.inputAElements)
        assertEquals(f32.inputBElements, i8.inputBElements)
    }

    @Test
    fun convPlanUsesChannelAndSpatialConvention() {
        // rows = output channels, inner = input channels, cols = spatial side.
        val plan = MicroOpPlans.plan(OpKind.CONV2D_F32, rows = 8, inner = 3, cols = 16)
        assertEquals(3 * 16 * 16, plan.inputAElements)
        assertEquals(8 * 3 * 9, plan.inputBElements)
        assertEquals(listOf(8, 16, 16), plan.outputShape)
    }

    @Test
    fun eltwisePlanUsesFullVolume() {
        val plan = MicroOpPlans.plan(OpKind.ELTWISE_ADD_F32, rows = 2, inner = 3, cols = 4)
        assertEquals(24, plan.inputAElements)
        assertEquals(24, plan.inputBElements)
    }

    @Test
    fun oversizedTensorsAreRejectedAgainstBackendBudget() {
        val plan = MicroOpPlans.plan(OpKind.MATMUL_F32, rows = 4096, inner = 4096, cols = 4096)
        assertEquals(4096 * 4096, plan.inputAElements)
        assertTrue(plan.validate(maxTensorElements = 1L shl 24).isEmpty())
        val errors = plan.validate(maxTensorElements = 1L shl 20)
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("exceeds the backend limit") })
    }

    @Test
    fun shapeProductsSaturateInsteadOfOverflowing() {
        // 4096^3 does not fit in an Int: the plan must saturate (so allocation
        // guards fail closed) rather than wrap around to a small/negative size.
        val plan = MicroOpPlans.plan(OpKind.ELTWISE_MUL_F32, rows = 4096, inner = 4096, cols = 4096)
        assertEquals(Int.MAX_VALUE, plan.inputAElements)
        assertTrue(plan.validate(maxTensorElements = 1L shl 24).any { it.contains("exceeds the backend limit") })
        assertTrue(!MicroOpPlans.isAllocatable(plan, limitElements = 1 shl 24))
    }

    @Test
    fun saturatingMultiplyIsExactBelowTheLimit() {
        assertEquals(0, MicroOpPlans.mulSaturating(0, 5, 7))
        assertEquals(35, MicroOpPlans.mulSaturating(5, 7))
        assertEquals(Int.MAX_VALUE, MicroOpPlans.mulSaturating(1 shl 20, 1 shl 20))
    }

    @Test
    fun nonPositiveDimensionsAreRejected() {
        assertTrue(MicroOpPlans.plan(OpKind.MATMUL_F32, 0, 4, 4).validate(1_000_000).isNotEmpty())
        assertTrue(MicroOpPlans.plan(OpKind.MATMUL_F32, -1, 4, 4).validate(1_000_000).isNotEmpty())
    }

    @Test
    fun convSpatialSideAboveNativeLimitIsRejected() {
        val plan = MicroOpPlans.plan(OpKind.CONV2D_F32, rows = 1, inner = 1, cols = MicroOpPlan.CONV_MAX_SPATIAL_SIDE + 1)
        assertTrue(plan.validate(1L shl 40).any { it.contains("CONV2D_F32") })
    }

    @Test
    fun allocationGuardRejectsNegativeAndHuge() {
        val ok = MicroOpPlans.plan(OpKind.MATMUL_F32, 4, 4, 4)
        assertTrue(MicroOpPlans.isAllocatable(ok, limitElements = 1024))
        val huge = MicroOpPlans.plan(OpKind.MATMUL_F32, 1024, 1024, 1024)
        assertTrue(!MicroOpPlans.isAllocatable(huge, limitElements = 1024))
    }

    @Test
    fun deterministicFillIsRepeatableAndBounded() {
        val a = FloatArray(257)
        val b = FloatArray(257)
        MicroOpPlans.fillDeterministic(a, seed = 12345L)
        MicroOpPlans.fillDeterministic(b, seed = 12345L)
        assertTrue(a.contentEquals(b))
        assertTrue(a.all { it.isFinite() })
        assertTrue(a.all { it <= 1.5f && it >= -1.5f })
        assertTrue(a.distinct().size > 200)
    }

    @Test
    fun differentSeedsProduceDifferentData() {
        val a = FloatArray(64)
        val b = FloatArray(64)
        MicroOpPlans.fillDeterministic(a, seed = 1L)
        MicroOpPlans.fillDeterministic(b, seed = 2L)
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun zeroSeedIsNotDegenerate() {
        val a = FloatArray(32)
        MicroOpPlans.fillDeterministic(a, seed = 0L)
        assertTrue(a.any { it != 0f })
    }
}
