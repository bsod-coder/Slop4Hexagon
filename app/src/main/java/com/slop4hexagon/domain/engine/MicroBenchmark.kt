package com.slop4hexagon.domain.engine

import com.slop4hexagon.domain.benchmark.BenchmarkEngine
import kotlin.random.Random

/**
 * Real CPU work for microbenchmark: fixed-shape matmul 512x512 float.
 * Avoids heap thrash by reusing buffers where possible.
 */
class MicroBenchmark {
    // 512*512 floats ~1MB
    private val size = 512

    fun runFixedShapeMatMul(clock: BenchmarkEngine.MonotonicClock): Long {
        val a = FloatArray(size * size) { Random.nextFloat() }
        val b = FloatArray(size * size) { Random.nextFloat() }
        val c = FloatArray(size * size)

        val start = clock.elapsedRealtimeNanos()
        // Blocked matmul for better cache (block 64)
        val block = 64
        for (i in 0 until size step block) {
            for (j in 0 until size step block) {
                for (k in 0 until size step block) {
                    for (ii in i until minOf(i + block, size)) {
                        for (kk in k until minOf(k + block, size)) {
                            val aik = a[ii * size + kk]
                            for (jj in j until minOf(j + block, size)) {
                                c[ii * size + jj] += aik * b[kk * size + jj]
                            }
                        }
                    }
                }
            }
        }
        // Prevent DCE
        if (c[0].isNaN()) throw IllegalStateException("nan")
        return clock.elapsedRealtimeNanos() - start
    }
}
