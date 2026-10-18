# Qualcomm QNN / QAIRT integration: what this repo does, and what is still required

Target: Snapdragon 778G+ / `SM7325-AF` (Nothing Phone (1)), Hexagon NPU reached
through the Qualcomm AI Engine Direct SDK (QNN, now distributed inside the
**Qualcomm AI Runtime / QAIRT** SDK).

## 1. What is implemented here (and what is not)

Implemented in this repository:

* `app/src/main/cpp/qnn_probe.cpp` — resolves a QNN backend library with
  `dlopen()` and checks for the documented public entry point
  `QnnInterface_getProviders`, then `dlclose()`. This mirrors Qualcomm's own
  guidance that the backend library be loaded dynamically rather than linked as
  a dependency.
* `app/src/main/kotlin/.../nativebridge/QnnNativeProbe.kt` — probes
  `libQnnHtp.so` and the QAIRT-era rename `libQairtHtp.so`; refuses any name that
  is not a plain `.so` soname (no path injection).
* `app/src/main/kotlin/.../backend/QnnHtpBackend.kt` — maps the probe result to
  the honest status ladder and **refuses** to claim initialization or execution.
* The capability/status model (`:core`) is built so that a HTP result can only be
  reported as `VERIFIED_EXECUTION` after a real inference ran and its output
  digest matched a reference within tolerance.

Not implemented, on purpose:

* No QNN context creation, graph construction, quantization or execution.
* No linked or committed Qualcomm binaries or headers (they are proprietary).
* No model converter. Nothing in this repo converts a `.safetensors` / `.onnx`
  model into an HTP context binary, and no code path pretends otherwise.

Consequence: an ordinary Stable Diffusion checkpoint **cannot** run on the HTP
here. The library marks such files `REQUIRES_CONVERSION` for HTP, which is the
truth: HTP executes compiled QNN artifacts, not generic weight files.

## 2. License / distribution constraints

* The QNN/QAIRT SDK is obtained from Qualcomm (`https://qpm.qualcomm.com/`,
  install via QPM CLI after accepting the Qualcomm license). It is **not**
  redistributable the way an Apache-licensed dependency is; Qualcomm's own helper
  projects state that QNN/QAIRT runtime artifacts must not be redistributed
  unless the license permits it.
* Therefore: do **not** commit `libQnn*.so`, `*.pdb`, headers or `.dlc`/context
  binaries into this repository. The build must stay green without them, which it
  is (the probe uses `dlopen`, so nothing is linked at build time).
* Shipping the runtime inside an APK requires checking the SDK's
  `LICENSE.pdf` / redistribution terms for your SDK version and use case. A
  common alternative is to keep the QNN libraries outside the APK (e.g. push to
  app-private storage during a device-verification workflow) and have the app
  load them from there — the probe already only needs them to be resolvable by
  the linker for the process.

## 3. Artifacts required for a real integration

From `<QAIRT_SDK_ROOT>` (installed layout varies by version; look for
`qnn/` or the QAIRT root), for `aarch64-android`:

| Artifact | Path (typical) | Purpose |
|---|---|---|
| `libQnnHtp.so` (or `libQairtHtp.so`) | `lib/aarch64-android/` | HTP backend, `dlopen`ed by the app |
| `libQnnSystem.so` | `lib/aarch64-android/` | system/context-binary APIs |
| `libQnnHtpV<arch>Stub.so` | `lib/aarch64-android/` | Android-side FastRPC stub |
| `libQnnHtpV<arch>Skel.so` | `lib/hexagon-v<arch>/unsigned/` | runs **on the DSP**; must be on the DSP search path |
| `libQnnHtpNetRunExtensions.so` | `lib/aarch64-android/` | optional profiling/config extensions |
| Headers `Qnn*.h` | `include/QNN/` | `QnnInterface.h` etc. for a compiled-in adapter |
| Tools | `bin/<host>/` | `qnn-net-run`, `qnn-onnx-converter`/`qnn-pytorch-converter`, `qnn-context-binary-generator`, `qnn-profile-viewer` |

### Device target for the Nothing Phone (1)

Qualcomm's QAIRT "Supported Snapdragon devices" table lists
**778G (SM7325) → `aarch64-android`, SoC model id 35, Hexagon arch V68**. Note
that the marketing name for the NPU block on this SoC is *Hexagon 770*; the
**DSP architecture the SDK targets is V68**, and V66 (a value often quoted for
other 2021 mid-range parts) would be wrong here.

Treat both the arch and the SoC id as *to-be-confirmed on the actual device*:
read your SDK version's supported-device table, and verify with
`qnn-net-run` output / `ro.soc.model` before compiling anything against them. A
mismatched stub/skel pair fails at load time with a FastRPC error, not a clean
"unsupported" — which is exactly the class of failure this app's status model is
designed to surface instead of hide.

## 4. Steps to finish the integration

1. **Install the SDK** and export `QNN_SDK_ROOT`. Confirm
   `$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtp.so` exists and note the SDK
   version (`qnn-net-run --version`).
2. **Decide the licensing posture** for shipping the runtime (see §2).
3. **Compile a context binary** for a *small, supported* model first (e.g. a
   single conv+relu+gemm graph), targeting the device:
   `qnn-onnx-converter` → `qnn-net-run --backend libQnnHtp.so --input_list …`
   on-device sanity check → `qnn-context-binary-generator` with a config that
   pins the backend/SoC (for this device: `dsp_arch: "v68"`, `soc_model: 35` —
   use the exact schema of your SDK version).
4. **Run it on the device with the SDK tool before touching the app**: push the
   libs + skel, set `ADSP_LIBRARY_PATH` to the skel directory, execute
   `qnn-net-run`, and read `qnn-profile-viewer` output to confirm the HTP backend
   actually executed the graph. Only then is it meaningful to wire the app up.
5. **Add the real adapter**: implement a `QnnHtpBackend` that, instead of the
   probe, loads `QnnInterface_getProviders`, selects the HTP provider by API
   version, creates backend → device → context → graph → tensor, executes, and
   *validates output digests* against the CPU reference with
   `slop::digestsAgree`-style tolerance comparison. `InferenceRequest` already
   carries `MicroOpRequest`/`ImagePipelineRequest`; extend `BackendCapabilities`
   with the ops you have wired and let the UI capability gating do the rest.
6. **Package deliberately**: if the license permits shipping, put the `.so` files
   in a *separate* source set/flavor (never in the public repo), keep
   `abiFilters arm64-v8a`, and add a build-time guard that fails when a required
   library is absent — rather than falling back silently.
7. **Verification gate**: only flip the HTP backend to
   `VERIFIED_EXECUTION` after step 5's digest match passes on the device; keep
   `AVAILABLE` for probe-only builds.

## 5. What must stay true meanwhile

With no SDK present (as in this repository), the app must and does report:

```
Qualcomm Hexagon HTP (QNN)  →  Unavailable
  "not loadable in this process (checked the app's native lib dir + system
   namespaces): dlopen failed: library libQnnHtp.so not found"
```

and any attempt to run a benchmark or generation on it returns
`NotSupportedYet` with the prerequisite text above. There is no code path in
which a probe, a `Build.HARDWARE == "qcom"` check, or the presence of a
`.serialized` file turns into a claim of HTP inference.
