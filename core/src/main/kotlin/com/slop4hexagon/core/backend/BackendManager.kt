package com.slop4hexagon.core.backend

/**
 * Enumerates registered backends and implements preference-based selection with
 * explicit, reportable fallback behaviour.
 *
 * The class is deliberately pure (no Android types) so that selection rules are
 * unit-testable on the JVM. Backends are queried live for their status.
 */
class BackendManager(private val backends: List<InferenceBackend>) {

    init {
        require(backends.isNotEmpty()) { "At least one backend must be registered" }
        require(backends.map { it.id }.distinct().size == backends.size) { "Duplicate backend ids" }
    }

    fun all(): List<InferenceBackend> = backends.toList()

    fun find(id: BackendId): InferenceBackend? = backends.firstOrNull { it.id == id }

    fun statusSnapshot(): List<BackendStatusInfo> = backends.map { backend ->
        BackendStatusInfo(
            id = backend.id,
            displayName = backend.displayName,
            status = backend.status(),
            detail = backend.statusDetail(),
            capabilities = backend.capabilities(),
        )
    }

    /**
     * Choose a backend honouring [preference]. When the preferred backend is
     * unusable the manager walks a deterministic fallback chain and reports
     * every decision in [BackendSelection.reasons].
     */
    fun select(preference: BackendPreference): BackendSelection {
        val reasons = mutableListOf<String>()
        val direct = preference.directTarget()
        if (direct != null) {
            val backend = find(direct)
            if (backend == null) {
                reasons += "${direct.displayName}: not registered in this build"
            } else {
                val status = backend.status()
                if (status.isUsable()) {
                    reasons += "${direct.displayName}: selected (status=$status)"
                    return BackendSelection(backend, status, fallbackOccurred = false, reasons = reasons)
                }
                reasons += "${direct.displayName}: unusable (status=${status}, ${backend.statusDetail()})"
            }
        } else {
            reasons += "AUTO preference: probing backends in capability order"
        }

        // Fallback chain: accelerator-first for AUTO, portable CPU last.
        val chain = listOf(BackendId.HTP_QNN, BackendId.GPU, BackendId.CPU)
        for (candidateId in chain) {
            if (candidateId == direct) continue
            val backend = find(candidateId) ?: continue
            val status = backend.status()
            if (status.isUsable()) {
                val viaFallback = direct != null
                reasons += "${candidateId.displayName}: " +
                    if (viaFallback) "used as fallback (status=$status)" else "selected (status=$status)"
                return BackendSelection(
                    chosen = backend,
                    status = if (viaFallback) BackendStatus.FALLBACK_OCCURRED else status,
                    fallbackOccurred = viaFallback,
                    reasons = reasons,
                )
            }
            reasons += "${candidateId.displayName}: unusable (status=${status}, ${backend.statusDetail()})"
        }

        reasons += "No usable backend is available"
        return BackendSelection(chosen = null, status = BackendStatus.UNAVAILABLE, fallbackOccurred = false, reasons = reasons)
    }

    /**
     * Prepare the selected backend and describe the outcome without hiding
     * fallback. Initialization failures flip the backend to FAILED and the
     * selection to UNAVAILABLE instead of silently swapping in another backend
     * a second time (which would mask the failure).
     */
    suspend fun initializeSelection(selection: BackendSelection): InitializationReport {
        val backend = selection.chosen
            ?: return InitializationReport(
                selection = selection,
                outcome = InitOutcome.Failure(
                    InferenceError.BackendUnavailable("No usable backend"),
                    selection.reasons.joinToString("; "),
                ),
            )
        return when (val outcome = backend.initialize()) {
            is InitOutcome.Success -> InitializationReport(selection, outcome)
            is InitOutcome.Failure -> InitializationReport(selection, outcome)
        }
    }
}

data class BackendSelection(
    val chosen: InferenceBackend?,
    val status: BackendStatus,
    val fallbackOccurred: Boolean,
    val reasons: List<String>,
) {
    fun describe(): String = reasons.joinToString("\n")
}

data class InitializationReport(
    val selection: BackendSelection,
    val outcome: InitOutcome,
)
