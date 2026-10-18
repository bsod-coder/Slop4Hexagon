# Implementation Plan - Slop4Hexagon Android Local AI Image Lab

## Repository Status
- Initial commit only contained LICENSE (MIT).
- No existing Android project, no native code, no CI.
- Current branch: `arena/7fcbeeca-slop4hexagon` from `c9b7557`
- Environment: Debian 12, no preinstalled JDK/Android SDK, outbound limited to github.com, codeload.github.com, api.github.com, registry.npmjs.org, pypi.org, files.pythonhosted.org. `deb.debian.org` blocked. JDK available only as JRE via jdk4py wheel (no javac), Gradle/SDK/QNN binaries blocked by proxy. Build verification will be limited to source inspection + JVM logic tests emulated via Python; honest limitation will be documented.

## Product Goal
Offline-first Android lab for local image generation / image-to-image editing, optimized for Qualcomm Snapdragon 778G+ (Nothing Phone 1, SM7325-AF, Adreno 642L, Hexagon DSP, 8GB RAM). No account/cloud/API key/network required. Distinguish UI / model metadata / inference engine / backends / benchmarking.

## Architecture Decisions

### Module Layout
- Single `:app` Android module (keeps review simple). Core domain logic kept in `domain` package with zero Android deps where possible to allow JVM unit tests.
- Package `com.slop4hexagon` (short) - app id `com.slop4hexagon.lab`
- `data` for Room + DataStore + SAF import.
- `engine` for backend abstraction.
- `ui` for Compose destinations.
- `native` via JNI (`app/src/main/cpp`) - CMake.

### Backend Abstraction
```
enum BackendId { CPU, GPU, HTP, AUTO }
enum BackendStatus { AVAILABLE, INITIALIZED, VERIFIED_EXECUTION, UNSUPPORTED, FAILED, FALLBACK_OCCURRED }
data class BackendCapabilities(supportedFormats, supportsImg2Img, supportsDenoisingStrength, supportsQuantized, maxMemoryBytes, ... )
interface InferenceBackend { id, capabilities, status, initialize(), isAvailable(), execute() }
class BackendManager { enumerate(), initializeAll(), resolvePreferred(), fallbackChain }
```
- CPU: always AVAILABLE, pure Kotlin/Java, real microbenchmark (matmul) + no-op pipeline that honestly reports unsupported for diffusion.
- GPU: checks GPU info, delegates to CPU fallback, marked UNSUPPORTED until Vulkan/OpenCL delegate added.
- HTP/QNN: isolated adapter `QnnHtpBackend`. Tries System.loadLibrary("qnnHtpAdapter") and QnnLoader. Without proprietary .so/headers, reports UNSUPPORTED with explicit prerequisites doc. Never fakes success.

### Model Format Registry
- `ModelFormat` enum: ONNX, TFLITE, DLC, SAFETENSORS, CKPT, GGUF, UNKNOWN
- `ModelFormatRegistry.detect(uri, extension, headerBytes, size)` - extension primary, magic secondary: ONNX protobuf (0x08), TFLite `TFL3`, safetensors JSON header, GGUF `GGUF`.
- `ModelCompatibilityChecker.check(format, backend, size)` - strict table: CPU supports ONNX/TFLITE (limited), HTP supports DLC only (requires QNN conversion), GGUF/CKPT/SAFETENSORS marked unsupported for execution but importable for inspection.
- Store `ModelEntity` in Room with hash (SHA-256 first 64k + size for duplicate detection), streamed copy (no heap load).

### Inference
- `InferenceRequest` (prompt, negativePrompt, mode TXT2IMG/IMG2IMG, width, height, steps, seed, denoisingStrength, sourceImageUri)
- `InferenceResult` sealed: Success(bitmapUri, backendUsed, elapsedMs) / Failure(reason, backendStatus)
- Pipeline validates `steps`, `width/height` (64..1024, multiple 8), `denoisingStrength` only if backend.capabilities.supportsDenoisingStrength else error.
- No placeholder image: on unsupported, return Failure with guidance. UI shows error, not fake.

### Benchmark
- Use `SystemClock.elapsedRealtimeNanos()` via injected `MonotonicClock` for testability.
- Two modes: MICRO (fixed-shape matmul 512x512 float, 1 warmup + 5 measured) and FULL_PIPELINE (attempt inference pipeline with same warmup/measured split, records cold-start + init + warmup + inference).
- `BenchmarkResult` median/min/max/peakMemory/thermal, JSON export via kotlinx.serialization.
- Aggregation: median via sorted list (honest, handles even/odd).

### Diagnostics
- Reads `Build.SOC_MODEL` (API 31+), `Build.HARDWARE`, `Build.SUPPORTED_ABIS`, `Build.VERSION.SDK_INT`, `ActivityManager.MemoryInfo`, `PowerManager` thermal (API 29+), `BackendStatus` init results.
- Treat missing props as normal.

### UI
- Kotlin 1.9.22, Compose BOM 2024.02.00, Material3 dynamic colors (`dynamicLightColorScheme`), Navigation Compose 2.7.7.
- Single `MainActivity` with `Scaffold` + `NavigationBar` + `NavHost` for 4 destinations: Generate, Library, Benchmark, Settings.
- ViewModels expose `StateFlow<UiState>` with sealed UI states.
- SAF: `ActivityResultContracts.OpenDocument` + `CreateDocument`, streamed copy with progress.

### Native
- `CMakeLists.txt` builds `libslop4hexagon_jni.so`. JNI: `QnnHtpAdapter.cpp` exposes `nativeGetHtpStatus()`, `nativeInitQnn()`. Without SDK, returns `HTP_UNAVAILABLE_MISSING_SDK`.
- Keep JNI small, validate jstring, release refs, catch exceptions.

### Testing
- JVM unit tests (JUnit4) for backend selection, capability, format detection, duplicate hash, benchmark median, fallback, JSON serialization. Run via Gradle `testDebugUnitTest` when SDK available; emulated via Python in this env.
- Lint via `gradlew lint`.

### Build
- compileSdk 34, minSdk 26 (Nothing Phone 1 ships Android 12, but 26 keeps SAF reliability while covering device), targetSdk 34, AGP 8.3.2, Gradle 8.7, NDK 26, Kotlin 1.9.22.
- Version catalog `gradle/libs.versions.toml`.

### Documentation
- README: purpose, status, arch, build, models, QNN prereqs, import/benchmark, limitations.
- docs/HTP_INTEGRATION.md: exact SDK artifacts, ABI, conversion steps.
- docs/DEVICE_VALIDATION.md: steps to validate HTP on 778G+.

## Execution Steps
1. Create Gradle project structure + wrapper + version catalog.
2. Implement domain/backend/model format/diagnostics/engine stubs.
3. Implement data layer (Room, DataStore, SAF repo).
4. Implement UI/navigation/theme/ViewModels.
5. Add native CMake/JNI.
6. Add unit tests + CI workflow.
7. Update README + docs.
8. Run available checks, push branch, open PR.

## Risks & Limitations
- No Android SDK/JDK toolchain in env -> build cannot be executed; will be reported honestly.
- No QNN SDK binaries/headers -> HTP will report UNSUPPORTED with doc, not fabricated.
- No real diffusion runtime (ONNX Runtime/TFLite still need large deps) -> full pipeline honestly incomplete, microbenchmark is real.

