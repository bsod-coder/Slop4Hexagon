package com.slop4hexagon.domain.engine

import android.content.Context
import com.slop4hexagon.domain.model.BackendCapabilities
import com.slop4hexagon.domain.model.BackendId
import com.slop4hexagon.domain.model.BackendStatus

/**
 * Core abstraction for inference backends.
 * Keep JNI boundary small and inputs validated.
 */
interface InferenceBackend {
    val id: BackendId
    val capabilities: BackendCapabilities
    fun getStatus(): BackendStatus
    fun isAvailable(context: Context? = null): Boolean
    fun initialize(context: Context? = null): BackendStatus
    fun shutdown()

    /**
     * Optional micro-operation for honest timing. Real work, not fabricated pipeline.
     */
    fun supportsMicroBenchmark(): Boolean = false
}

/**
 * Optional extended interface for backends that can attempt generation.
 * Current CPU/GPU are not expected to run SD; they will return explicit unsupported.
 */
interface GenerativeBackend : InferenceBackend {
    suspend fun generate(request: com.slop4hexagon.domain.model.InferenceRequest): com.slop4hexagon.domain.model.InferenceResult
}
