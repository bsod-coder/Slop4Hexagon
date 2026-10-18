# Device Validation — Snapdragon 778G+

## Prerequisites
- Nothing Phone (1), Snapdragon 778G+ (SM7325-AF), Android 12L+ (API 31+)
- App installed via `adb install` (debug)
- No proprietary SDK needed for baseline validation (CPU/GPU/HTP unsupported paths are tested)

## Steps
1. **Diagnostics screen:**
   - Open Settings → verify:
     - `SOC_MODEL` shows `SM7325-AF` or similar (if null, check `getprop ro.soc.model` via adb)
     - `HARDWARE=qcom`
     - `SUPPORTED_ABIS=[arm64-v8a, armeabi-v7a]`
     - `API Level 31+`, `TotalMem ~8GB`
     - `Thermal status` integer (0=none, 7=critical shutdown)

2. **Backend status:**
   - CPU → `INITIALIZED` (always)
   - GPU → `FALLBACK_OCCURRED` (Adreno present but no delegate)
   - HTP → `UNSUPPORTED` (expected without QNN SDK) + error shows missing SDK doc link

3. **Model Library:**
   - Import a tiny ONNX (e.g., MobileNet 14 MB): should show `ONNX • Compatible (CPU)`.
   - Import a `.safetensors`: shows `Safetensors (SD) • Needs conversion (DLC)` + warning.
   - Rename a TFLite to `.bin` → header magic still detects `TFL3` as TFLite (format registry test).

4. **Benchmark — Micro:**
   - Select `CPU`, `Micro`, `Warmup 1, Measured 5`, Run.
   - Verify: cold-start ~ few ms, init ~1 ms, each inference 50–400 ms (real matmul on 778G+), median/min/max consistent, `backendVerified=false` for CPU (only HTP would be verified with DLC). Export JSON and confirm monotonic nanos (not `currentTimeMillis`).

5. **Benchmark — Full Pipeline:**
   - Without model: shows `Full pipeline requires compatible model…`
   - With ONNX selected: runs validation timing, returns error `Full pipeline not yet implemented…` — honest, not fake.

6. **Generate:**
   - Without model: `Select a model first` error (no placeholder image).
   - With incompatible model (e.g., GGUF): failure `Backend does not support…`
   - With ONNX: currently returns `No executable diffusion pipeline…` — success image not fabricated.

7. **Thermal & memory:**
   - Run 3 consecutive micro benchmarks; check `Peak memory` and thermal status change; export logs.

## HTP Validation (when SDK available)
- Rebuild with `QNN_AVAILABLE=ON`, install.
- Benchmark → HTP → Micro should transition `UNSUPPORTED` → `INITIALIZED` → `VERIFIED_EXECUTION` after measured run.
- Capture `adb logcat -s QnnHtpAdapter` + exported JSON; include `SOC_MODEL`, `Build.HARDWARE`, SDK version in PR.

## What NOT to claim
- Do not claim Hexagon version confirmed without `QnnHtpDevice_getInfo()` or driver log.
- Do not claim inference ran on HTP unless `VERIFIED_EXECUTION` and timing export exists.

