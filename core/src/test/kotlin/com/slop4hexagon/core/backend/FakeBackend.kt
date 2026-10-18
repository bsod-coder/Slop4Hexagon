package com.slop4hexagon.core.backend

/** Test double with a controllable status and capability set. */
class FakeBackend(
    override val id: BackendId,
    private var status: BackendStatus = BackendStatus.AVAILABLE,
    private val capabilities: BackendCapabilities = BackendCapabilities(
        supportedOps = setOf(OpKind.MATMUL_F32),
        supportsMicroBenchmarks = true,
        supportsImagePipeline = false,
        maxTensorElements = 1_000_000,
    ),
    private val detail: String = "fake backend",
    private val failInitialize: Boolean = false,
    private val failExecute: Boolean = false,
) : InferenceBackend {

    var initializeCalls = 0
    var executeCalls = 0
    var closeCalls = 0

    override val displayName: String = id.displayName

    override fun capabilities(): BackendCapabilities = capabilities

    override fun status(): BackendStatus = status

    override fun statusDetail(): String = detail

    fun forceStatus(newStatus: BackendStatus) {
        status = newStatus
    }

    override suspend fun initialize(): InitOutcome {
        initializeCalls++
        return if (failInitialize) {
            status = BackendStatus.FAILED
            InitOutcome.Failure(InferenceError.BackendUnavailable(detail), "init failed: $detail")
        } else {
            status = BackendStatus.INITIALIZED
            InitOutcome.Success("initialized")
        }
    }

    override suspend fun execute(request: InferenceRequest): InferenceResult {
        executeCalls++
        if (failExecute) {
            return InferenceResult.Failure(InferenceError.ExecutionFailed("boom"))
        }
        status = BackendStatus.VERIFIED_EXECUTION
        return when (request) {
            is InferenceRequest.MicroOpRequest ->
                InferenceResult.MicroOpSuccess(request.op, listOf(request.rows, request.cols), 1.0, 100L)
            is InferenceRequest.ImagePipelineRequest ->
                InferenceResult.Failure(InferenceError.NotSupportedYet("image pipeline"))
        }
    }

    override fun close() {
        closeCalls++
    }
}
