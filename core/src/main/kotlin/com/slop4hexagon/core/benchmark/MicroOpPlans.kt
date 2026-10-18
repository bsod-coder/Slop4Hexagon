package com.slop4hexagon.core.benchmark

import com.slop4hexagon.core.backend.OpKind

/**
 * Fixed-shape execution plan for a backend microbenchmark op.
 *
 * Keeping the shape arithmetic in one place means the UI, the benchmark runner
 * and every backend agree on what a (rows, inner, cols) triple means for each
 * op — and on how big the input buffers must be. The conventions are:
 *
 *  * MATMUL_F32 / MATMUL_INT8: `A[rows x inner] * B[inner x cols]`
 *  * CONV2D_F32: input channels = `inner`, spatial `cols x cols`, output
 *    channels (filters) = `rows`, 3x3 kernel, stride 1, zero padding
 *  * ELTWISE_*: `A` and `B` both hold `rows * inner * cols` elements
 */
data class MicroOpPlan(
    val op: OpKind,
    val rows: Int,
    val inner: Int,
    val cols: Int,
    val inputAElements: Int,
    val inputBElements: Int,
    val outputShape: List<Int>,
) {
    /** Largest single buffer this plan touches, in elements. */
    fun maxInputElements(): Int = maxOf(inputAElements, inputBElements)

    fun totalInputElements(): Int = inputAElements + inputBElements

    fun validate(maxTensorElements: Long): List<String> {
        val errors = mutableListOf<String>()
        if (rows <= 0 || inner <= 0 || cols <= 0) errors += "all three shape dimensions must be positive"
        val dims = listOf(rows, inner, cols)
        if (dims.any { it > BenchmarkConfig.MAX_DIM }) {
            errors += "each dimension must be <= ${BenchmarkConfig.MAX_DIM}"
        }
        if (op == OpKind.CONV2D_F32 && cols > CONV_MAX_SPATIAL_SIDE) {
            errors += "CONV2D_F32 supports spatial sides up to $CONV_MAX_SPATIAL_SIDE (got $cols)"
        }
        if (errors.isEmpty()) {
            val maxElement = maxOf(inputAElements, inputBElements).toLong()
            if (maxTensorElements > 0 && maxElement > maxTensorElements) {
                errors += "tensor of $maxElement elements exceeds the backend limit of $maxTensorElements"
            }
        }
        return errors
    }

    companion object {
        /**
         * Mirrors the native kernel limit in app/src/main/cpp/slop_engine.h.
         * The native int8 inner-dimension bound (8192) is looser than
         * [BenchmarkConfig.MAX_DIM] (4096), so [BenchmarkConfig] already covers
         * it; no separate check is duplicated here.
         */
        const val CONV_MAX_SPATIAL_SIDE = 2048
    }
}

object MicroOpPlans {

    /** Saturating multiply: shape products must never silently overflow Int. */
    internal fun mulSaturating(vararg factors: Int): Int {
        var acc = 1L
        for (f in factors) {
            acc *= f.toLong()
            if (acc > Int.MAX_VALUE) return Int.MAX_VALUE
        }
        return acc.toInt()
    }

    fun plan(op: OpKind, rows: Int, inner: Int, cols: Int): MicroOpPlan = when (op) {
        OpKind.MATMUL_F32, OpKind.MATMUL_INT8 -> MicroOpPlan(
            op = op,
            rows = rows,
            inner = inner,
            cols = cols,
            inputAElements = mulSaturating(rows, inner),
            inputBElements = mulSaturating(inner, cols),
            outputShape = listOf(rows, cols),
        )
        OpKind.CONV2D_F32 -> MicroOpPlan(
            op = op,
            rows = rows,
            inner = inner,
            cols = cols,
            inputAElements = mulSaturating(inner, cols, cols),
            inputBElements = mulSaturating(rows, inner, 9),
            outputShape = listOf(rows, cols, cols),
        )
        OpKind.ELTWISE_ADD_F32, OpKind.ELTWISE_MUL_F32 -> MicroOpPlan(
            op = op,
            rows = rows,
            inner = inner,
            cols = cols,
            inputAElements = mulSaturating(rows, inner, cols),
            inputBElements = mulSaturating(rows, inner, cols),
            outputShape = listOf(rows, inner, cols),
        )
    }

    /** Overflow-safe sanity bound used before allocating any buffer. */
    fun isAllocatable(plan: MicroOpPlan, limitElements: Int): Boolean =
        plan.inputAElements > 0 && plan.inputBElements > 0 &&
            plan.inputAElements <= limitElements && plan.inputBElements <= limitElements

    /**
     * Deterministic pseudo-random tensor content. Same seed => same data on
     * every backend, so digests are comparable across runs.
     */
    fun fillDeterministic(target: FloatArray, seed: Long, amplitude: Float = 1.5f) {
        // Knuth's 64-bit LCG. Long overflow wraps deterministically, so the
        // stream is identical on every JVM/ART device for a given seed.
        var state = if (seed == 0L) LCG_DEFAULT_STATE else seed xor LCG_DEFAULT_STATE
        for (i in target.indices) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            // Top 53 bits -> [0,1), mapped to [-amplitude, amplitude].
            val unit = (state ushr 11).toDouble() / 9007199254740992.0
            target[i] = (unit * 2.0 - 1.0).toFloat() * amplitude
        }
    }

    private const val LCG_MULTIPLIER = 6364136223846793005L
    private const val LCG_INCREMENT = 1442695040888963407L
    private const val LCG_DEFAULT_STATE = -7046029254386353131L
}
