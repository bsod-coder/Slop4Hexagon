# Slop4Hexagon — Android Local AI Image Lab (Qualcomm Hexagon HTP)

Offline-first Android app for experimenting with on-device image generation and
image-to-image editing, built around an **honest** backend model: CPU, Adreno
GPU and the Qualcomm Hexagon NPU (HTP, via the QNN / QAIRT runtime) are distinct
execution targets with distinct *capability* and *execution-verification*
states, and this repository never reports more than it actually proved.

Primary target device: **Nothing Phone (1)** — Snapdragon 778G+ (`SM7325-AF`),
Adreno 642L, Hexagon NPU, 8 GB RAM.

---

## Current status (read this first)

| Area | State |
|---|---|
| `:core` domain layer (backends, capability model, format registry, benchmark math, JSON, diagnostics) | **Implemented and unit-tested** — 102 JVM tests |
| Native CPU micro-op engine (`app/src/main/cpp`) | **Implemented and host-tested** — 109 checks, clean under ASan+UBSan |
| Material 3 UI: Generate / Model Library / Benchmark Lab / Settings+Diagnostics | **Implemented** (not compiled in this environment — see *Build*) |
| Model import via SAF, streaming copy + SHA-256, duplicate + unknown-format refusal, managed storage | **Implemented** |
| Benchmark Lab: micro-op mode (real measured timings), JSON export, saved-run comparison | **Implemented** for the CPU engine |
| Benchmark Lab: full-pipeline mode | **Implemented as a code path**, but no engine can satisfy it (see below) |
| QNN / HTP integration | **Isolated probe boundary only.** dlopen+dlsym presence probe, no graph, no execution. |
| Stable Diffusion / any text-to-image or image-to-image execution | **Not implemented.** No pipeline engine is bundled. |
| Adreno GPU backend | **Not implemented** (reported `UNSUPPORTED` with a reason) |
| On-device verification on a physical Snapdragon 778G+ | **Not performed** (no device in this environment) |

The Generate screen therefore never shows a fake picture: if nothing can
execute, the app explains exactly which prerequisite is missing. No placeholder
image is substituted for generated output anywhere in this codebase.

---

## Architecture

```
:app (Android, Kotlin, Compose M3)
├── ui/            Generate · ModelLibrary(+Details) · Benchmark · Settings — ViewModel + StateFlow
├── generate/      GenerationController: validate → dispatch → decode result
├── bench/         BenchmarkRunner: monotonic timing, phase separation, memory/thermal samples
├── data/          ModelRepository (SAF streaming import), JsonIndexStore (index + history)
├── backend/       CpuInferenceBackend · NotImplementedBackend(GPU) · QnnHtpBackend · BackendManagerHolder
├── nativebridge/  SlopNativeBridge (raw JNI), NativeCpuEngine (validated wrapper), QnnNativeProbe
├── platform/      DeviceInfo (public APIs only), ImageStore (decode/preprocess/save)
└── src/main/cpp/  slop_engine.{h,cpp} · native_jni.cpp · qnn_probe.cpp · native_tests.cpp

:core (pure Kotlin JVM, no Android types — compiled and tested here)
├── backend/       BackendId · BackendStatus · BackendCapabilities · InferenceRequest/Result · BackendManager
├── model/         ModelFormat(+Registry: magic detection) · ModelInspector (SafeTensors header) ·
│                  CompatibilityMatrix · ImportPlanner · ModelDescriptor(+JSON)
├── benchmark/     BenchmarkConfig · MicroOpPlans · BenchmarkAggregator · BenchmarkReport(+JSON) · BenchmarkComparator
├── pipeline/      PipelineRequestValidator (per-field honoured / not-supported / missing-model-part)
├── diagnostics/   DeviceSnapshot (export)
└── json/          dependency-free JSON parser/writer used by all of the above
```

Design rules the code enforces:

* **Status ladder, not a boolean.** `UNSUPPORTED → UNAVAILABLE → AVAILABLE →
  INITIALIZED → VERIFIED_EXECUTION`, plus `FAILED` and `FALLBACK_OCCURRED`.
  `CpuInferenceBackend` reaches `VERIFIED_EXECUTION` only after the native
  self-test passes **and** a real op executed twice with an identical output
  digest. A QNN probe that merely finds `libQnnHtp.so` yields `AVAILABLE`, never
  more.
* **Capability gating before execution.** `BackendManagerHolder.canRun()` refuses
  a benchmark whose mode the selected backend does not implement, with the
  reason, instead of running something meaningless.
* **Imported ≠ executable.** `CompatibilityMatrix` never marks a weights bundle
  (`.safetensors`, `.ckpt`) as supported anywhere, and marks HTP as
  `REQUIRES_CONVERSION` for it.
* **No main-thread work, no full-file buffering.** Imports stream in 256 KiB
  chunks; benchmarks run on `Dispatchers.Default`; the micro-op path materializes
  no output tensor (row scratch only) and returns a reduced digest.

---

## Build instructions

Requirements: Android SDK platform 35 + build-tools, **NDK r27** (`27.0.12077973`)
and **CMake 3.22.1**, JDK 17. Point `local.properties`/`ANDROID_HOME` at the SDK.

```sh
./gradlew :core:test                       # 102 pure-JVM unit tests
./gradlew :app:assembleDebug               # APK (native lib built by CMake/NDK)
./gradlew :app:testDebugUnitTest             # app unit tests
./gradlew :app:lintDebug                   # lint
```

`gradle/libs.versions.toml` pins AGP 8.7.3, Kotlin 2.1.10, Compose BOM
2024.12.01, compileSdk/targetSdk 35, minSdk 26, `arm64-v8a` + `x86_64` ABIs.

### Verified in this session, and what was not

The sandbox this work was produced in can reach `github.com`, `registry.npmjs.org`
and `pypi.org` only. `dl.google.com`, `maven.google.com`, `repo.maven.apache.org`
and `services.gradle.org` all fail (TLS handshake terminated), so **Gradle could
not be run at all** — the wrapper itself works, the distribution download does
not:

```
$ ./gradlew --version
Downloading https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
Attempt 1/1 failed. Reason: Remote host terminated the handshake
```

Therefore `:app` (AGP/Compose) was **not** compiled here, and no APK was built.
What *was* executed here, with a downloaded Kotlin 2.4.21 compiler, a JDK 25
runtime and g++ 12:

| Command | Result |
|---|---|
| `tools/offline-test-runner/run-jvm-tests-offline.sh` | `Result: 102 passed, 0 failed` |
| `tools/offline-native-check/run-native-checks.sh` | `Result: 109 checks, 0 failed`; ASan+UBSan clean; 7 JNI entry points exported |
| `tools/offline-app-logic-check/check-app-logic.sh` | app non-UI + composition-root sources type-check against Android stubs |
| `tools/offline-app-logic-check/run-app-logic-tests.sh` | `Result: 6 passed, 0 failed` (app-layer logic tests, executed here) |

The two `tools/offline-*` harnesses exist precisely because the above is the
most that can be honestly verified without the Android SDK; each README states
what it does and does not prove. The Compose screens were reviewed and
parse-checked but not type-checked — expect the first real `assembleDebug` to
need small fixes there.

---

## Supported Android versions and ABIs

* **minSdk 26** (Android 8.0), target/compile **SDK 35**.
* ABIs: `arm64-v8a` (device) and `x86_64` (emulator). No 32-bit build: a
  32-bit process cannot address the memory a diffusion model needs, and QNN
  ships `aarch64-android`.
* Dynamic colour requires API 31+ (silently ignored below that); thermal status
  requires API 29+; `Build.SOC_MODEL` requires API 31+ — all reported as
  `null`/absent where unavailable rather than guessed.
* `x86_64` emulator: the HTP probe is skipped with the reason "no Qualcomm
  Hexagon runtime for this ABI". Nothing on an emulator can verify HTP.

## Supported model formats and engines

Recognised by `ModelFormatRegistry` (magic number first, extension as a hint,
conflicts resolved in favour of magic with a warning):

| Format | Detect | Executable in this build? |
|---|---|---|
| SafeTensors (`.safetensors`) | header length + `{` | no — weights bundle, needs a pipeline engine |
| Pickle checkpoint (`.ckpt/.pt/.pth`) | `0x80` protocol byte | no — and unsafe to load; convert to SafeTensors |
| ONNX (`.onnx`) | protobuf heuristic | no — needs an ONNX runtime, none bundled |
| GGUF (`.gguf`) | `GGUF` magic | no — no GGUF engine bundled |
| TFLite (`.tflite`) | `TFL3` at offset 4 | no — needs a TFLite interpreter, none bundled |
| QNN context binary (`.serialized`, `.qnnbin`) | no public magic; load-only verification | HTP *only*, and HTP execution is not wired up here |
| QNN/QAIRT `.dlc` | no public magic | same as above |
| anything else, incl. bare `.bin` | — | **refused** as unknown, never stored as executable |

A checkpoint may hold UNet + VAE + text encoder; the details screen shows which
components were found from tensor names and says explicitly when a full pipeline
would need more files. Supported engines in this build: **the portable C++ CPU
micro-op engine** (fixed-shape matmul fp32/int8, 3×3 conv, element-wise
add/mul) — real, measured, verified by self-test. No diffusion runtime.

## Importing a model

1. Model Library → *choose model file* (SAF; nothing to grant on API 29+).
2. The file is streamed into `filesDir/models/<id>` while SHA-256 is computed;
   progress and errors are shown; a `*.part` file is renamed only after a
   complete read, and cancellation deletes the partial copy.
3. Identical content (same hash **and** size) is refused as a duplicate; an
   unrecognised format is refused outright.
4. Your original file is never modified or deleted — *Remove* only deletes the
   app's own copy, and the dialog says so.
5. Open the entry for format, tensor inventory, estimated resident memory and
   the per-backend verdict.

Large models are supported by design (streaming, hash-as-you-go, header-only
inspection); nothing reads a whole file into the Java heap.

## Benchmarking

* **Backend micro-op** mode times one fixed-shape op and is labelled as such —
  it is explicitly not presented as image-generation speed.
* **Full pipeline** mode drives an end-to-end `ImagePipelineRequest`; with no
  pipeline engine installed, the run records a failure plus the exact
  prerequisite and *no* latency statistics.
* Phases are recorded separately: cold start (first touch of the runtime,
  including init when needed), init, warm-up, measured runs. Defaults: 1
  warm-up + 5 measured, editable in Settings. Only measured runs enter
  median/min/max/mean/p95.
* Timing uses `SystemClock.elapsedRealtimeNanos()`. Peak RSS comes from
  `getrusage(RUSAGE_SELF)` in the native engine; heap figures are labelled as
  point-in-time. Thermal status comes from `PowerManager`.
* Nothing starts automatically; a run needs the *Start benchmark* button, and
  Cancel works between iterations.
* Every run (including failed ones) is kept in a local history, viewable,
  deletable, **exportable as JSON** (`BenchmarkReportJson`, schemaVersion 1) and
  comparable across two saved runs, with a caveat line whenever the backends
  differ.

## Diagnostics and device identification

`Settings → Device & runtime diagnostics` collects `Build.SOC_MODEL`,
`Build.HARDWARE`, `Build.SUPPORTED_ABIS`, Android version/API,
`ActivityManager` memory, the live backend table (statuses, details,
capabilities, notes) and peak RSS when the native engine is available — then
exports it as JSON. Missing/restricted fields are reported as `null` and are
treated as normal; no undocumented system property is used as the sole source of
device identity, and `hexagonVerified` is only ever set from a real executed HTP
inference.

## Qualcomm QNN / HTP: prerequisites and current boundary

See **[docs/QNN_HTP_SETUP.md](docs/QNN_HTP_SETUP.md)** for the full list of
artifacts and steps, and **[docs/HTP_VALIDATION.md](docs/HTP_VALIDATION.md)** for
the on-device verification procedure (including for this exact SoC:
per Qualcomm's supported-device table, `SM7325` maps to Hexagon **V68**, not
V66 — verify before assuming).

Summary of where this repository stands:

* No Qualcomm binaries, headers or licensed SDK content are committed or
  downloaded by this project. `:app` links nothing against QNN: `qnn_probe.cpp`
  only `dlopen`s a candidate library name and checks for the documented
  `QnnInterface_getProviders` entry point, then `dlclose`s it — the loading
  strategy Qualcomm's own HTP documentation prescribes.
* `QnnHtpBackend.initialize()` **refuses** to report success, and
  `execute()` returns `NotSupportedYet`, because implementing a real HTP path
  means SDK artifacts, op coverage, a compiled context binary and a device to
  test on. Faking it is what this boundary exists to prevent.

## Known limitations / not implemented

1. **No image generation.** No diffusion (or other generative) pipeline engine is
   bundled: text-to-image and image-to-image produce no output. The request path,
   validation, preprocessing and result handling are implemented so an engine can
   be attached without touching the UI.
2. **No model conversion.** SafeTensors/ONNX → QNN context-binary conversion is
   not implemented here; the app only documents it as a prerequisite.
3. **No GPU backend**; the entry exists to report `UNSUPPORTED` truthfully.
4. **HTP probe ≠ HTP execution.** Only presence is detected.
5. **INT8/quantized artifacts** are recognised as a format, and `MATMUL_INT8` is
   a real native op, but there is no quantized-model loader/extension wired to a
   generative pipeline yet (extension point: `BackendCapabilities.supportedOps` +
   `ModelFormat.QNN_CONTEXT_BINARY`).
6. No Room database (deliberate: a versioned JSON index is sufficient and keeps
   the build free of codegen — see `JsonIndexStore` KDoc). No foreground service
   or notification: long runs are cancellable in-app and survive rotation via
   `ViewModel`; a service is on the list for multi-minute pipeline runs.
7. UI code unverified by a compiler in this environment (see *Build*).

## Planned (explicitly not done)

pipeline engine with a real sampler; ONNX Runtime or QNN-graph execution;
GPU (Adreno/OpenCL) backend; `.safetensors` → HTP conversion recipe; export of
generated images with prompt metadata; instrumented UI tests; per-device
capability cache.

## Repository layout

```
core/            pure Kotlin domain (testable without Android)
app/             Android app: UI, repositories, JNI bridge, cpp/
docs/            QNN_HTP_SETUP.md, HTP_VALIDATION.md
tools/           offline-test-runner, offline-native-check, offline-app-logic-check
```

Licence: MIT (see `LICENSE`).
