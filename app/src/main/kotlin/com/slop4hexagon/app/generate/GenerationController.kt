package com.slop4hexagon.app.generate

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import com.slop4hexagon.app.backend.BackendManagerHolder
import com.slop4hexagon.app.data.ModelRepository
import com.slop4hexagon.app.platform.ImageStore
import com.slop4hexagon.core.backend.InferenceRequest
import com.slop4hexagon.core.backend.InferenceResult
import com.slop4hexagon.core.model.ModelDescriptor
import com.slop4hexagon.core.pipeline.PipelineRequestValidator
import com.slop4hexagon.core.pipeline.PipelineSettings
import com.slop4hexagon.core.pipeline.ValidatedRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Orchestrates a generation attempt: resolve model → validate against the real
 * capabilities of the selected backend → only then dispatch.
 *
 * Because no image pipeline engine ships in this build, the honest outcome for
 * any model the library can currently accept is [Blocked], carrying the exact
 * prerequisite text. The dispatch path below is nonetheless complete: when a
 * backend does report `supportsImagePipeline`, the request is built and executed
 * and the result is decoded and saved through the same code path — no separate
 * "demo" branch that could be mistaken for inference.
 */
class GenerationController(
    private val context: Context,
    private val holder: BackendManagerHolder,
    private val models: ModelRepository,
) {

    data class Progress(val fraction: Float, val label: String)

    sealed class Outcome {
        /** Settings invalid, or nothing in this build can execute the request. */
        data class Blocked(val validation: ValidatedRequest) : Outcome()

        /** The backend was asked to run and reported a failure. */
        data class Failed(
            val message: String,
            val latencyMillis: Double,
            val backendLabel: String,
        ) : Outcome()

        /** A real pipeline result. */
        data class Executed(
            val bitmap: Bitmap,
            val pngBytes: ByteArray,
            val latencyMillis: Double,
            val backendLabel: String,
            val modelLabel: String,
            val width: Int,
            val height: Int,
        ) : Outcome() {
            override fun equals(other: Any?): Boolean = this === other
            override fun hashCode(): Int = javaClass.hashCode()
        }
    }

    @Volatile
    var cancelRequested: Boolean = false
        private set

    private val _progress = kotlinx.coroutines.flow.MutableStateFlow<Progress?>(null)
    val progress: kotlinx.coroutines.flow.StateFlow<Progress?> = _progress

    fun requestCancel() {
        cancelRequested = true
    }

    fun validate(settings: PipelineSettings, model: ModelDescriptor?): ValidatedRequest =
        PipelineRequestValidator.validate(
            settings = settings,
            capabilities = holder.currentBackend()?.capabilities(),
            inspection = model?.inspection,
        )

    suspend fun generate(settings: PipelineSettings, sourceUri: Uri?): Outcome =
        withContext(Dispatchers.IO) {
            cancelRequested = false
            val model = settings.modelId?.let { id -> models.models.value.firstOrNull { it.id == id } }
            val merged = settings.copy(modelId = model?.id ?: settings.modelId)
            val validation = validate(merged, model)
            if (!validation.executable) return@withContext Outcome.Blocked(validation)

            val backend = holder.currentBackend()
                ?: return@withContext Outcome.Blocked(
                    validation.copy(blockers = validation.blockers + "backend vanished during setup"),
                )

            val source: ImageStore.PreparedImage? =
                if (merged.mode == InferenceRequest.ImageGenMode.IMAGE_TO_IMAGE && sourceUri != null) {
                    _progress.value = Progress(0.02f, "decoding source image")
                    runCatching { ImageStore.prepareSourceImage(context, sourceUri, merged.width, merged.height) }
                        .getOrElse { t ->
                            return@withContext Outcome.Failed(
                                message = "source image could not be prepared: ${t::class.java.simpleName}: ${t.message}",
                                latencyMillis = 0.0,
                                backendLabel = backend.displayName,
                            )
                        }
                } else {
                    null
                }

            _progress.value = Progress(0.05f, "dispatching to ${backend.displayName}")
            val request = InferenceRequest.ImagePipelineRequest(
                modelId = merged.modelId ?: "",
                mode = merged.mode,
                prompt = merged.prompt,
                negativePrompt = merged.negativePrompt.takeIf { it.isNotBlank() },
                width = merged.width,
                height = merged.height,
                steps = merged.steps,
                seed = merged.seed,
                denoisingStrength = merged.denoisingStrength.takeIf { merged.mode == InferenceRequest.ImageGenMode.IMAGE_TO_IMAGE },
                sourceImage = source?.let {
                    com.slop4hexagon.core.backend.SourceImage(it.rgba, it.width, it.height)
                },
            )

            val startedAt = SystemClock.elapsedRealtimeNanos()
            val result = try {
                coroutineContext.ensureActive()
                backend.execute(request)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                InferenceResult.Failure(
                    com.slop4hexagon.core.backend.InferenceError.ExecutionFailed(
                        "${t::class.java.simpleName}: ${t.message ?: "no detail"}",
                    ),
                )
            }
            val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1e6
            _progress.value = null

            when (result) {
                is InferenceResult.Failure -> Outcome.Failed(
                    message = result.error.describe(),
                    latencyMillis = elapsedMillis,
                    backendLabel = backend.displayName,
                )
                is InferenceResult.MicroOpSuccess -> Outcome.Failed(
                    message = "backend answered with a micro-op result for a pipeline request; the " +
                        "engine is misconfigured and no image can be shown",
                    latencyMillis = elapsedMillis,
                    backendLabel = backend.displayName,
                )
                is InferenceResult.ImageSuccess -> {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(result.pngBytes, 0, result.pngBytes.size)
                    if (bitmap == null) {
                        Outcome.Failed(
                            message = "backend returned ${result.pngBytes.size} bytes that are not a decodable image",
                            latencyMillis = elapsedMillis,
                            backendLabel = backend.displayName,
                        )
                    } else {
                        Outcome.Executed(
                            bitmap = bitmap,
                            pngBytes = result.pngBytes,
                            latencyMillis = elapsedMillis,
                            backendLabel = backend.displayName,
                            modelLabel = model?.displayName ?: "unknown model",
                            width = result.width,
                            height = result.height,
                        )
                    }
                }
            }
        }
}
