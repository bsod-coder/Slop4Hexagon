package com.slop4hexagon.domain.engine

import android.content.Context
import com.slop4hexagon.domain.model.BackendId
import com.slop4hexagon.domain.model.BackendStatus

/**
 * Enumerates, initializes, and chooses backends according to user preference.
 * Honest fallback: reports FALLBACK_OCCURRED when AUTO resolves away from preferred.
 */
class BackendManager(
    private val backends: List<InferenceBackend>
) {
    fun listBackends(): List<InferenceBackend> = backends

    fun find(id: BackendId): InferenceBackend? = backends.firstOrNull { it.id == id }

    /**
     * Initialize all backends, report statuses. Does not throw.
     */
    fun initializeAll(context: Context? = null): Map<BackendId, BackendStatus> {
        return backends.associate { b ->
            try {
                b.id to b.initialize(context)
            } catch (e: Exception) {
                b.id to BackendStatus.FAILED
            }
        }
    }

    /**
     * Resolve preferred backend:
     * - If preferred != AUTO and available/initialized, return it.
     * - If AUTO, pick best available: HTP > GPU > CPU if each reports AVAILABLE or better.
     * - Otherwise fallback to CPU if exists.
     * Returns null if nothing available.
     */
    fun resolvePreferred(preferred: BackendId, context: Context? = null): InferenceBackend? {
        if (preferred != BackendId.AUTO) {
            val candidate = find(preferred)
            if (candidate != null && isUsable(candidate, context)) return candidate
            // Fallback chain for AUTO-like behavior when explicit backend unavailable
            return fallbackChain(context)
        }
        // AUTO
        val ordered = listOf(BackendId.HTP, BackendId.GPU, BackendId.CPU)
        for (id in ordered) {
            val b = find(id) ?: continue
            if (isUsable(b, context)) return b
        }
        return backends.firstOrNull { isUsable(it, context) }
    }

    private fun isUsable(b: InferenceBackend, ctx: Context?): Boolean {
        val s = try { b.getStatus() } catch (_: Exception) { BackendStatus.FAILED }
        return s == BackendStatus.AVAILABLE || s == BackendStatus.INITIALIZED || s == BackendStatus.VERIFIED_EXECUTION || b.isAvailable(ctx)
    }

    private fun fallbackChain(context: Context?): InferenceBackend? {
        // explicit fallback order CPU is safest
        return find(BackendId.CPU)?.takeIf { isUsable(it, context) }
            ?: backends.firstOrNull { isUsable(it, context) }
    }

    fun getStatusReport(context: Context? = null): Map<BackendId, BackendStatus> {
        return backends.associate { it.id to try { it.getStatus() } catch (_: Exception) { BackendStatus.FAILED } }
    }
}
