package com.slop4hexagon.app.backend

import android.content.Context
import android.os.Build
import com.slop4hexagon.app.data.ModelRepository
import com.slop4hexagon.app.nativebridge.NativeCpuEngine
import com.slop4hexagon.app.nativebridge.QnnNativeProbe
import com.slop4hexagon.core.backend.BackendId
import com.slop4hexagon.core.backend.BackendManager
import com.slop4hexagon.core.backend.BackendPreference
import com.slop4hexagon.core.backend.BackendSelection
import com.slop4hexagon.core.backend.BackendStatus
import com.slop4hexagon.core.backend.BackendStatusInfo
import com.slop4hexagon.core.backend.InferenceBackend
import com.slop4hexagon.core.benchmark.BenchmarkConfig
import com.slop4hexagon.core.model.ModelDescriptor
import com.slop4hexagon.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the live backend list, the [BackendManager] built from it, and the
 * current selection. Probing (JNI load + QNN dlopen) happens off the main
 * thread and only once, on first use.
 */
class BackendManagerHolder(
    private val context: Context,
    @Suppress("unused") private val models: ModelRepository,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val probing: Boolean = false,
        val ready: Boolean = false,
        val manager: BackendManager? = null,
        val selection: BackendSelection? = null,
        val statuses: List<BackendStatusInfo> = emptyList(),
        val qnnProbe: QnnNativeProbe.QnnProbeSummary? = null,
        val abiSupport: AbiSupport = AbiSupport.UNKNOWN,
        val errors: List<String> = emptyList(),
    )

    enum class AbiSupport { KNOWN_ARM64, EMULATOR_X86_64, UNKNOWN }

    val gpuReason: String = "No Adreno GPU compute engine is implemented in this build. " +
        "An OpenCL/Vulkan path would be required; nothing here can execute on the GPU, " +
        "so this backend reports itself unsupported rather than silently falling back."

    fun refresh(preference: BackendPreference) {
        val current = _state.value
        if (current.probing) return
        _state.value = current.copy(probing = true)
        scope.launch {
            try {
                val next = buildState(preference, previous = current)
                _state.value = next
            } catch (t: Throwable) {
                _state.value = current.copy(
                    probing = false,
                    ready = true,
                    errors = current.errors + "backend setup failed: ${t::class.java.simpleName}: ${t.message}",
                )
            }
        }
    }

    private suspend fun buildState(
        preference: BackendPreference,
        previous: State,
    ): State = withContext(Dispatchers.Default) {
        val abi = when {
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> AbiSupport.KNOWN_ARM64
            Build.SUPPORTED_ABIS.any { it == "x86_64" } -> AbiSupport.EMULATOR_X86_64
            else -> AbiSupport.UNKNOWN
        }

        // Loading the JNI library from a background dispatcher only: the static
        // initializer runs System.loadLibrary.
        val engine = NativeCpuEngine.load()
        val cpu = CpuInferenceBackend(engine)

        val qnnProbe = if (abi == AbiSupport.KNOWN_ARM64) {
            QnnNativeProbe.probeHtpBackend()
        } else {
            QnnNativeProbe.QnnProbeSummary(
                QnnNativeProbe.Outcome.INVALID,
                "HTP probing skipped: this ABI (${Build.SUPPORTED_ABIS.joinToString()}) has no " +
                    "Qualcomm Hexagon runtime, so QNN presence cannot be assessed",
                emptyList(),
            )
        }

        val backends: List<InferenceBackend> = listOf(
            cpu,
            NotImplementedBackend(BackendId.GPU, gpuReason),
            QnnHtpBackend(qnnProbe),
        )
        val manager = BackendManager(backends)
        val selection = manager.select(preference)
        // Initialize the chosen backend so the UI can distinguish "available"
        // from "initialized"; failures are surfaced, never swallowed.
        val report = manager.initializeSelection(selection)
        val errors = buildList {
            if (report.outcome is com.slop4hexagon.core.backend.InitOutcome.Failure) {
                add("selected backend ${selection.chosen?.displayName ?: "none"}: ${(report.outcome as com.slop4hexagon.core.backend.InitOutcome.Failure).detail}")
            }
            if (engine == null) add("native CPU engine missing: ${NativeCpuEngine.load()?.libraryLoadError ?: "libslop_native not loadable"}")
        }
        State(
            probing = false,
            ready = true,
            manager = manager,
            selection = selection,
            statuses = manager.statusSnapshot(),
            qnnProbe = qnnProbe,
            abiSupport = abi,
            errors = errors,
        )
    }

    fun currentBackend(): InferenceBackend? = _state.value.selection?.chosen

    fun statusSnapshot(): List<BackendStatusInfo> = _state.value.statuses

    /** True only when the chosen backend has genuinely executed something. */
    fun executionVerified(): Boolean = _state.value.selection?.chosen?.status() == BackendStatus.VERIFIED_EXECUTION

    /**
     * Whether a benchmark of [config] can run on the selected backend, and why
     * not. Never guesses: capabilities are read from the live backend.
     */
    fun canRun(config: BenchmarkConfig, settings: AppSettings): CapabilityCheck {
        val state = _state.value
        val backend = state.selection?.chosen
            ?: return CapabilityCheck(false, "No usable backend is available. ${state.statuses.joinToString { "${it.id}:${it.status}" }}")
        val caps = backend.capabilities()
        return when (config.mode) {
            com.slop4hexagon.core.benchmark.BenchmarkMode.MICRO_OP -> {
                val op = config.op
                    ?: return CapabilityCheck(false, "no operation selected")
                when {
                    !caps.supportsMicroBenchmarks -> CapabilityCheck(
                        false,
                        "${backend.displayName} reports no microbenchmark support. " +
                            caps.notes.joinToString(" "),
                    )
                    op !in caps.supportedOps -> CapabilityCheck(
                        false,
                        "${backend.displayName} does not implement ${op.displayName}. " +
                            "Supported here: ${if (caps.supportedOps.isEmpty()) "none" else caps.supportedOps.joinToString { it.displayName }}.",
                    )
                    else -> CapabilityCheck(true, "${backend.displayName} can run ${op.displayName}")
                }
            }
            com.slop4hexagon.core.benchmark.BenchmarkMode.FULL_PIPELINE -> {
                if (!caps.supportsImagePipeline) {
                    CapabilityCheck(
                        false,
                        "${backend.displayName} reports no image-generation pipeline capability. " +
                            "No engine in this build executes Stable Diffusion / ONNX / QNN model " +
                            "artifacts end to end, so a full-pipeline benchmark cannot run for any " +
                            "installed model.",
                    )
                } else {
                    CapabilityCheck(true, "${backend.displayName} claims pipeline support")
                }
            }
        }
    }

    /**
     * Whether an imported model can be *executed* by the selected backend.
     * This is where the app refuses to conflate "imported" with "runnable".
     */
    fun executionVerdictFor(model: ModelDescriptor): ExecutionVerdict {
        val backend = _state.value.selection?.chosen
            ?: return ExecutionVerdict(false, "No usable backend", emptyList())
        val compat = model.compatibilityFor(backend.id)
            ?: return ExecutionVerdict(false, "no compatibility entry for ${backend.displayName}", emptyList())
        val runnable = compat.status == com.slop4hexagon.core.model.CompatibilityStatus.LIKELY_SUPPORTED &&
            backend.capabilities().supportsImagePipeline
        return ExecutionVerdict(
            runnable = runnable,
            reason = when {
                !runnable -> "${backend.displayName}: ${compat.status} — ${compat.reason}"
                else -> "${backend.displayName} reports pipeline support for ${model.inspection.format.displayName}"
            },
            notes = model.inspection.formatWarnings + model.inspection.inspectionWarnings,
        )
    }

    data class CapabilityCheck(val runnable: Boolean, val explanation: String)

    data class ExecutionVerdict(val runnable: Boolean, val reason: String, val notes: List<String>)
}
