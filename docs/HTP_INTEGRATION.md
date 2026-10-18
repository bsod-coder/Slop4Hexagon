# HTP / QNN Integration

## Summary
v0.1 ships with **isolated adapter boundary** — no proprietary QNN binaries, no fake HTP success. `QnnHtpBackend` reports `UNSUPPORTED` until you provide SDK artifacts and rebuild with `QNN_AVAILABLE=ON`. The JNI surface is small: `nativeGetHtpStatus()`, `nativeInitQnn()`, `nativeShutdown()`.

## Target Device
- **Nothing Phone (1)**
- SoC: **Snapdragon 778G+ (SM7325-AF)** — Kryo 670, Adreno 642L
- Hexagon: **DSP 682 / HTP v68** (do not assume version alone guarantees runtime; verify via `Build.SOC_MODEL` + QNN driver)
- RAM: 8 GB (large model guard at 2 GB imported)

## Required SDK Artifacts
Obtain **Qualcomm AI Engine Direct SDK (QNN, formerly QAIRT/SNPE)** from developer.qualcomm.com (requires account + license). Version tested for 778G+ is **QNN 2.20+** (HTP backend for SM7325).

Required files (example layout under `QNN_SDK_ROOT`):
```
QNN_SDK_ROOT/
  include/QNN/
    QnnInterface.h
    QnnTypes.h
    Htp/QnnHtpDevice.h
    Htp/QnnHtpPerfInfrastructure.h
  lib/
    aarch64-android/
      libQnnHtp.so
      libQnnSystem.so
      libQnnHtpV68Stub.so
      libQnnHtpV68Skel.so  # pushed to device /vendor
  bin/
    qnn-onnx-converter
    qnn-model-lib-generator
    qnn-quantizer
```

**Licensing:** QNN SDK is proprietary; redistribution not allowed under Qualcomm terms. Do not commit `.so`/headers to this repo. Documented here for setup, not bundled.

## ABI & Build
- Target ABI: `arm64-v8a` (device), `x86_64` (emulator, will report unsupported)
- NDK 26, CMake 3.22.1, `-std=c++17`, `c++_shared`
- Build with flag:
  ```bash
  ./gradlew assembleDebug -DQNN_SDK_ROOT=/path/to/qnn-sdk -PqnnAvailable=true
  ```
  `app/src/main/cpp/CMakeLists.txt` respects `option(QNN_AVAILABLE OFF)`; when ON, adds include path and defines `QNN_AVAILABLE=1`.

## Model Conversion
1. Prepare ONNX (opset 11+; only ops supported by HTP — Conv, Gemm, Softmax, etc. Unsupported ops cause conversion failure).
2. Convert:
   ```bash
   qnn-onnx-converter --inputNetwork model.onnx --output model.cpp --out_node out
   qnn-model-lib-generator -c model.cpp -t aarch64-android -o build --hexagon_version 68
   # Produces libModel.so + model_dlc.bin
   ```
3. Quantize for HTP INT8 (optional but recommended for 778G+ memory):
   ```bash
   qnn-quantizer --input_list data.txt --output quantized.bin --act_quant int8 --weights_quant int8
   ```

4. For Stable Diffusion checkpoints (`.safetensors/.ckpt`): you must extract UNet/VAE/text-encoder ONNX graphs first — a plain checkpoint cannot run on HTP without offline conversion and operator audit. Planned pipeline: `st → ONNX (optimum) → quantized DLC per sub-graph`.

## Runtime Verification
- `QnnHtpBackend.initialize()` → `QnnHtpAdapter.initQnn()` → `dlopen("libQnnHtp.so")` → `QnnInterface_getProviders()` → create HTP context.
- Status mapping:
  - `INIT_OK` → `INITIALIZED`
  - `INIT_MISSING_SDK` → `UNSUPPORTED` (shows docs link)
  - `INIT_UNSUPPORTED_DEVICE` → `UNSUPPORTED` (e.g., emulator or wrong driver)
  - Others → `FAILED`
- True `VERIFIED_EXECUTION` only after a measured inference returns without fallback (microbenchmark for now; diffusion later).

## What v0.1 Provides
- `CpuBackend` (matmul), `GpuBackend` (fallback), `QnnHtpBackend` stub + native `QnnHtpAdapter.cpp` that returns `UNAVAILABLE` without SDK — buildable.
- `BackendManager` fallback chain, `BackendStatus` 6-state, diagnostics exposure.
- No download of proprietary SDK from unofficial sources, no committed binaries.

## Next Steps to Finish Integration
- [ ] Integrate `QnnInterface.h` and `QnnHtpDevice.h` behind `QNN_AVAILABLE`.
- [ ] Load `libQnnHtp.so` and verify `Build.SOC_MODEL == SM7325-AF` + Hexagon 682.
- [ ] Implement DLC model loading in `nativeInitQnn()` and tensor execution path for microbenchmark → pipeline.
- [ ] Validate on physical Nothing Phone (1) per `docs/DEVICE_VALIDATION.md`.

