package com.slop4hexagon.app.backend

import com.slop4hexagon.core.backend.BackendCapabilities
import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.backend.InferenceBackend
import com.slop4hexagon.core.backend.InferenceError
import com.slop4hexagon.core.backend.InferenceRequest
import com.slop4hexagon.core.backend.InferenceResult
import com.slop4hexagon.core.backend.InitOutcome

/**
 * A backend placeholder that reports a capability set of zero and never claims
 * more than the truth. Used for the Adreno GPU path: no GPU compute engine
 * exists in this build, so the status is UNSUPPORTED with the exact reason, and
 * any attempt to initialize returns a failure instead of pretending.
 */
class NotImplementedBackend(
    override val id: BackendId,
    private val reason: String,
) : InferenceBackend {

    override val displayName: String = id.displayName

    override fun capabilities(): BackendCapabilities = BackendCapabilities.NONE

    override fun status(): BackendStatus = BackendStatus.UNSUPPORTED

    override fun statusDetail(): String = reason

    override suspend fun initialize(): InitOutcome =
        InitOutcome.Failure(InferenceError.NotSupportedYet(reason), reason)

    override suspend fun execute(request: InferenceRequest): InferenceResult =
        InferenceResult.Failure(InferenceError.NotSupportedYet(reason))

    override fun close() = Unit
}
