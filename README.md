# Slop4Hexagon — Android Local AI Image Lab for Qualcomm Hexagon HTP

Offline-first Android lab for experimenting with local image generation and image-to-image editing, optimized for **Nothing Phone (1) — Snapdragon 778G+ (SM7325-AF) / Adreno 642L / Hexagon 682 / 8 GB RAM**.

Status: **v0.1.0 — functional vertical slice**. UI, model management, backend abstraction, benchmarking, diagnostics, and QNN/HTP integration boundary are implemented and buildable. Full diffusion pipeline is *honestly incomplete* until ONNX Runtime / QNN DLC conversion is validated on device (see limitations).

No account, cloud, API key, or network required for local inference.

## Architecture Overview

```
UI (Compose + Material3 + Navigation)
  ├── Generate (prompt, SAF image pick, backend choice, progress)
  ├── Model Library (SAF import, streamed copy, hash dup, compatibility)
  ├── Benchmark Lab (micro + full-pipeline, monotonic clock, JSON export)
  └── Settings/Diagnostics (backend status, device info, thermal, export)

Domain
  ├── model/ ModelFormat, ModelMetadata, InferenceRequest/Result, DeviceInfo
  ├── engine/ InferenceBackend, BackendCapabilities, BackendStatus, BackendManager, Cpu/Gpu/Htp
  ├── benchmark/ BenchmarkResult, BenchmarkEngine, MicroBenchmark (real matmul)
  └── diagnostics/ DeviceDiagnostics

Data
  ├── db/ Room (ModelEntity, BenchmarkEntity, ModelDao)
  ├── repo/ ModelRepository (SAF streamed import, header magic, SHA-256 64k)
  └── prefs/ DataStore (preferred backend, storage location)

Native
  └── cpp/ QnnHtpAdapter.cpp via JNI — isolated, buildable without QNN SDK
```

**Key principles:**
- Distinguishes *available → initialized → verified execution → unsupported → failed → fallback*.
- QNN/HTP never pretends to run; adapter reports `UNSUPPORTED` with exact SDK prerequisites when artifacts missing.
- No placeholder images; failures return guidance.
- Model format registry is strict: `.safetensors/.ckpt` imported for inspection but not executable without pipeline.

## Build Instructions

### Prerequisites
- **JDK 17** (Temurin 17.0.11+ recommended)
- **Android SDK** 34, build-tools 34.0.0, platform-tools
- **Android NDK** 26 (via SDK Manager)
- **CMake** 3.22.1 + Ninja
- Gradle 8.7 via wrapper (`./gradlew`)

### Setup

```bash
# Clone
git clone https://github.com/bsod-coder/Slop4Hexagon.git
cd Slop4Hexagon

# Point to SDK (if not auto-detected)
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Build debug APK (requires SDK)
./gradlew assembleDebug

# Run unit tests & lint
./gradlew testDebugUnitTest lintDebug

# Install on Nothing Phone (1) via adb
adb install app/build/outputs/apk/debug/app-debug.apk
```

**This environment (CI container without Android SDK) cannot run `./gradlew`:** outbound is whitelisted to `github.com`, `api.github.com`, `codeload.github.com`, `registry.npmjs.org`, `pypi.org`, `files.pythonhosted.org`; `services.gradle.org`/`dl.google.com` and `deb.debian.org` are blocked, so SDK/Gradle distributions cannot be fetched. Source is complete and would build with a standard Android dev setup — reported honestly per acceptance criteria.

### Android Versions & ABIs
- `compileSdk 34`, `minSdk 26`, `targetSdk 34` (Nothing Phone 1 ships Android 12 / API 31; min 26 keeps SAF reliability)
- ABIs: `arm64-v8a` (778G+ device), `x86_64` (emulator). `armeabi-v7a` excluded for HTP focus.

### Dependencies
- Kotlin 1.9.22, Compose BOM 2024.02.00, Navigation 2.7.7, Lifecycle 2.7.0, Coroutines 1.7.3, Room 2.6.1, DataStore 1.0.0, Kotlinx Serialization 1.6.3, Coil 2.5.0, AGP 8.3.2.

## Supported Model Formats & Engines

| Format | Extension | Library import | CPU (microbenchmark) | GPU | HTP (QNN DLC) | Notes |
|--------|-----------|----------------|----------------------|-----|---------------|-------|
| ONNX | `.onnx` | ✅ | ✅ (matmul) | ⚠️ delegate missing | ❌ needs conversion | CPU can run generic ONNX; diffusion pipeline needs ONNX Runtime integration (planned) |
| TFLite | `.tflite` | ✅ | ✅ | ⚠️ | ❌ |  |
| QNN DLC | `.dlc/.bin` | ✅ | ❌ | ❌ | ✅ (if SDK) | Compiled via `qnn-model-lib-generator` for SM7325-AF |
| Safetensors | `.safetensors` | ✅ (inspect) | ❌ | ❌ | ❌ (conversion) | SD checkpoint container (UNet+VAE+text encoder) — import ≠ executable |
| CKPT | `.ckpt/.pt/.pth` | ✅ (inspect) | ❌ | ❌ | ❌ | Same as safetensors |
| GGUF | `.gguf` | ✅ (inspect) | ❌ | ❌ | ❌ | For LLMs, not image pipelines |

**Engine:** v0.1 provides `CpuBackend` (always available), `GpuBackend` (detects Adreno, reports `FALLBACK_OCCURRED` without delegate), `QnnHtpBackend` (probes native, honest status). No bundled ONNX Runtime yet; full pipeline returns `Failure` with guidance rather than fake image.

## Qualcomm QNN/HTP Prerequisites

See [docs/HTP_INTEGRATION.md](docs/HTP_INTEGRATION.md) for exact steps.

QNN is *isolated* — app builds without SDK. HTP shows `UNSUPPORTED` until you:

1. Obtain **Qualcomm AI Engine Direct SDK (QNN / QAIRT)** — requires Qualcomm developer account + license acceptance. Not redistributable; do not commit binaries.
2. Set env `QNN_SDK_ROOT=/path/to/qnn-sdk` (contains `include/QNN`, `lib/aarch64-android/libQnnHtp.so`, `libQnnSystem.so`).
3. Build with `-DQNN_AVAILABLE=ON`: `./gradlew assembleDebug -DQNN_SDK_ROOT=/path/to/qnn-sdk -PqnnAvailable=true`
4. Convert model: `qnn-onnx-converter` → `qnn-model-lib-generator -t sm7325` → `.dlc` + `.so` (HTPv68 / Hexagon 682 for 778G+). Quantize to INT8 via `qnn-quantizer` for HTP efficiency.
5. Validate on device: Benchmark Lab → HTP → Micro → should reach `VERIFIED_EXECUTION`; if `UNSUPPORTED`/`FAILED`, export diagnostics and check `docs/DEVICE_VALIDATION.md`.

Do **not** download SDK from unofficial mirrors or commit `.so`s.

## Device Compatibility & Verification

Primary target: Nothing Phone (1). App runs on any `arm64-v8a` device with Android 12+ but HTP only on Qualcomm SoCs with HTP driver.

**Verification on 778G+:**
1. Install APK, open Settings → diagnostics confirms `SOC_MODEL` (API31+) and `HARDWARE=qcom`.
2. Check backend status chips: CPU `INITIALIZED`, GPU `FALLBACK_OCCURRED`, HTP `UNSUPPORTED` (before SDK).
3. Import a small ONNX (e.g., MobileNet) → Benchmark → Micro → 5 runs → median displayed (real matmul ms).
4. After QNN DLC prepared, re-test HTP → should show `VERIFIED_EXECUTION` and real latency. Do not conflate matmul ms with diffusion speed.

See [docs/DEVICE_VALIDATION.md](docs/DEVICE_VALIDATION.md).

## How to Import a Model

1. Open **Models** → **Import Model** (Storage Access Framework).
2. Pick any file (even large >500 MB — streamed in 8 KB chunks, hashed first 64 KB for duplicate detection).
3. Card shows format (via extension + 4-byte magic), size, est. memory, compatibility per-backend. Unknown formats flagged red.
4. Tap card → details (stored path, SHA256, warnings). Tap **Remove** to delete app's copy (original file never touched).
5. No silent acceptance: unknown extensions stay `UNKNOWN`/`Incompatible`; SD checkpoints warn “import ≠ executable”.

## How to Run & Export Benchmarks

1. **Benchmark Lab** → choose model (optional for micro), backend, mode:
   - **Micro**: fixed-shape 512×512 float matmul (real CPU work), 1 warmup + 5 measured default. Reports cold-start, init, warmup, inference list, median/min/max, peak memory/thermal when available.
   - **Full Pipeline**: attempts pipeline; currently returns honest `error: not yet implemented` after timing validation — not fabricated.
2. **Run Benchmark** → progress + cancellation. Timing via `SystemClock.elapsedRealtimeNanos()` (monotonic).
3. Saved runs appear below with median/min/max; **Share JSON** → exports via `ACTION_SEND` with device info, backend status, thermal, full timing arrays.
4. **Comparison**: select multiple saved runs (export JSONs) and diff median values externally. In-app comparison table planned.

Benchmark never reports a single tensor op as diffusion speed.

## Known Limitations

- Diffusion generation (text-to-image / image-to-image) returns `Failure` with guidance — ONNX Runtime/TFLite pipeline not yet bundled and not validated for 512×512 on 8 GB. Microbenchmark is functional.
- GPU delegate not bundled; Adreno detected but falls back to CPU.
- HTP requires proprietary QNN SDK + device-specific DLC — absent in v0.1, reported as `UNSUPPORTED`.
- No quantized INT8 extension yet (scaffolding exists: `supportsQuantizedInt8` flag).
- Large model warning at >2 GB; OOM handling is graceful but not exhaustive.
- No foreground-service long-run persistence beyond placeholder notification.

All limitations are visible in UI; no fake success.

## Distinction: Implemented vs Planned

| Area | Implemented | Planned |
|------|-------------|---------|
| UI Generate | Prompt/negative, size/steps/seed, SAF image pick, backend picker, progress/cancel, real backend used, error display | Real pipeline execution after ONNX Runtime + QNN validated |
| Model Library | SAF streamed import, hash dup, size guard, Room, details, format registry, compatibility table | Direct download via SAF `CreateDocument` + MediaStore save |
| Benchmark | Monotonic clock, warmup/measured split, median/min/max, thermal, JSON export, comparison via exports | In-app comparison chart, peak memory via `Debug.getMemoryInfo()` |
| HTP | Isolated adapter, status 6-state, diagnostics, docs, CMake | Full QNN integration after SDK on 778G+ |
| Diagnostics | SOC_MODEL, HARDWARE, ABIs, API, memory, thermal, backend versions | HTP architecture version via private props (if exposed) |
| Tests | Unit: backend selection, capability, format, aggregation, error/JSON | UI tests (Compose) + on-device HTP validation |

## Development Note: Validating HTP on 778G+

1. Obtain QNN SDK (see above) + NDK 26.
2. Convert a tiny ONNX (e.g., 128×128 UNet fragment) to DLC: `qnn-onnx-converter --inputNetwork tiny.onnx --output tiny_dlc --hexagonVersion 68`.
3. Push DLC to device: `adb push tiny_dlc /data/local/tmp`.
4. Build app with QNN flag, install, run Benchmark → HTP → Micro (should now probe native).
5. If `VERIFIED_EXECUTION`, capture elapsed ms, thermal, and `dumpsys thermalservice`. If `UNSUPPORTED`/`FAILED`, collect `adb logcat | grep QnnHtp`, `getprop | grep htp`, and `Build.SOC_MODEL`.
6. Document driver version: `adb shell cat /vendor/etc/qnn/...` if accessible.

Never claim HTP ran without `VERIFIED_EXECUTION` + timing export.

## License

MIT © 2026 Вацлав Карпека — see [LICENSE](LICENSE).

