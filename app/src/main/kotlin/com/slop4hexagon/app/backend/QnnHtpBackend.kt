package com.slop4hexagon.app.backend

import com.slop4hexagon.app.nativebridge.QnnNativeProbe
import com.slop4hexagon.core.backend.BackendCapabilities
import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.backend.InferenceBackend
import com.slop4hexagon.core.backend.InferenceError
import com.slop4hexagon.core.backend.InferenceRequest
import com.slop4hexagon.core.backend.InferenceResult
import com.slop4hexagon.core.backend.InitOutcome
import com.slop4hexagon.core.backend.OpKind

/**
 * The isolated Qualcomm Hexagon (QNN / QAIRT) integration boundary.
 *
 * Deliberate limits of this class, which are the point of the whole design:
 *  * it only *probes* for the QNN HTP backend library and its documented
 *    `QnnInterface_getProviders` entry point (see `app/src/main/cpp/qnn_probe.cpp`);
 *  * it never creates a QNN context, never builds a graph, never quantizes a
 *    model, and therefore can never earn
 *    [BackendStatus.VERIFIED_EXECUTION];
 *  * executing any request fails with [InferenceError.NotSupportedYet] plus the
 *    precise list of artifacts/steps still missing, so nothing upstream can
 *    mistake a probe success for an inference run.
 *
 * Status mapping (all honest):
 *  * `ENTRY_POINT_FOUND`    -> [BackendStatus.AVAILABLE]  (runtime present only)
 *  * `ENTRY_POINT_MISSING`  -> [BackendStatus.UNAVAILABLE] (wrong/unknown QNN build)
 *  * `LIBRARY_NOT_FOUND`    -> [BackendStatus.UNAVAILABLE] (SDK artifacts not shipped)
 *  * `INVALID`              -> [BackendStatus.FAILED]      (probe itself broken)
 *  * probing skipped because the build has no HTP support compiled in
 *                           -> [BackendStatus.UNSUPPORTED]
 */
class QnnHtpBackend(private val probe: QnnNativeProbe.QnnProbeSummary?) : InferenceBackend {

    override val id: BackendId = BackendId.HTP_QNN
    override val displayName: String = BackendId.HTP_QNN.displayName

    private val probed: Boolean = probe != null

    override fun capabilities(): BackendCapabilities {
        val runtimePresent = probe?.outcome == QnnNativeProbe.Outcome.ENTRY_POINT_FOUND
        return BackendCapabilities(
            // No operator has been wired through QNN, so the supported op set
            // is empty no matter what the runtime can do.
            supportedOps = emptySet(),
            supportsMicroBenchmarks = false,
            supportsImagePipeline = false,
            maxTensorElements = 0,
            notes = buildList {
                add(
                    if (runtimePresent) {
                        "QNN HTP backend library and its public entry point were resolved in this " +
                            "process. That proves runtime presence only: no graph was created and " +
                            "no operator was executed."
                    } else {
                        "QNN HTP runtime not usable here: ${probe?.headline ?: "probe not run (build without HTP support)"}"
                    },
                )
                add(
                    "To execute anything on the HTP, models must be converted and compiled with the " +
                        "Qualcomm AI Engine Direct (QNN) SDK into a context binary for this device's " +
                        "DSP architecture, and the corresponding skel/stub libraries must be present.",
                )
                add(
                    "Stable Diffusion checkpoint files (.safetensors/.ckpt) cannot be executed on " +
                        "the HTP directly; a diffusion graph would also have to be split and compiled.",
                )
            },
        )
    }

    override fun status(): BackendStatus = when {
        !probed -> BackendStatus.UNSUPPORTED
        probe!!.outcome == QnnNativeProbe.Outcome.ENTRY_POINT_FOUND -> BackendStatus.AVAILABLE
        probe.outcome == QnnNativeProbe.Outcome.INVALID -> BackendStatus.FAILED
        else -> BackendStatus.UNAVAILABLE
    }

    override fun statusDetail(): String = when {
        !probed -> "this build was assembled without the QNN probe path"
        probe!!.outcome == QnnNativeProbe.Outcome.ENTRY_POINT_FOUND ->
            "QNN backend library loadable and ${QnnNativeProbe.QNN_ENTRY_POINT} resolved; " +
                "no HTP execution has been attempted, so this is not verified inference"
        else -> probe.headline
    }

    override suspend fun initialize(): InitOutcome {
        val outcome = probe?.outcome
        val message = when {
            outcome == null -> "QNN support is not compiled into this build"
            outcome == QnnNativeProbe.Outcome.ENTRY_POINT_FOUND ->
                "QNN runtime present, but this build implements no QNN graph execution; " +
                    "refusing to report initialization of an inference path that does not exist"
            else -> "QNN runtime not usable: ${probe.headline}"
        }
        return InitOutcome.Failure(InferenceError.NotSupportedYet(message), message)
    }

    override suspend fun execute(request: InferenceRequest): InferenceResult {
        val opNote = if (request is InferenceRequest.MicroOpRequest) {
            "op ${request.op.displayName} is not wired to QNN; this adapter declares no supported ops"
        } else {
            "image pipeline execution on HTP requires a compiled context binary for this device"
        }
        return InferenceResult.Failure(
            InferenceError.NotSupportedYet(
                "HTP inference is not implemented in this build. $opNote. " +
                    "Prerequisites: QNN/QAIRT SDK artifacts (libQnnHtp.so, matching " +
                    "libQnnHtpV<arch>Stub.so on the app side and libQnnHtpV<arch>Skel.so " +
                    "on the DSP search path), plus a model converted with the QNN " +
                    "context-binary toolchain. See docs/QNN_HTP_SETUP.md.",
            ),
        )
    }

    override fun close() = Unit

    companion object {
        /** Ops this adapter could theoretically expose once wired. */
        val FUTURE_OPS: Set<OpKind> = setOf(OpKind.MATMUL_F32, OpKind.CONV2D_F32)
    }
}
