# Validating HTP inference on a physical Snapdragon 778G+ device

Short development note. Nothing in this list can be done on an emulator or in CI
without a device: **all items below require the physical phone**, and until they
pass, no statement about HTP performance or compatibility is true for this repo.

Current state of this repository: steps 1–3 have **not** been executed. The app
builds a CPU micro-op engine plus a QNN *presence probe*, and reports HTP as
unavailable/unverified — which is the correct output for an unvalidated device.

## 0. Prerequisites

* Nothing Phone (1) (`SM7325-AF`), developer options + USB debugging, `adb`
  visible (`adb devices`), ≥ 3 GB free storage, and the phone cooled to idle.
* QAIRT/QNN SDK installed (see `QNN_HTP_SETUP.md` §3–4) with the `aarch64-android`
  libs **and** `lib/hexagon-v68/unsigned/libQnnHtpV68Skel.so` — verify V68 against
  your SDK's supported-device table, do not carry it over from a blog post.
* `./gradlew :app:assembleDebug` in a normal Android environment (not possible in
  the sandbox this code was written in — see the README's build section).

## 1. Confirm the SoC identity from public APIs

```sh
adb shell getprop ro.soc.model        # expect SM7325 (or SM7325-AF variant)
adb shell getprop ro.soc.manufacturer # expect QTI
adb shell getprop ro.hardware         # expect qcom
adb shell getprop ro.board.platform   # e.g. kona-family/sm7325 board id
adb shell getprop ro.build.version.sdk
```

These are *identity* facts only. None of them proves a Hexagon architecture
version, an HTP driver revision, or that any inference ran on the NPU. The app's
diagnostics screen exports exactly these fields plus the disclaimer.

## 2. Prove the QNN stack works *without the app* first

This isolates driver/library problems from app problems:

```sh
# on the host, from the SDK: build a small context binary for this device
qnn-net-run --backend <SDK>/lib/aarch64-android/libQnnHtp.so \
            --model   <SDK>/lib/aarch64-android/libQnnModelDlc.so \
            --dlc_path model.dlc --input_list inputs.txt \
            --output_dir out --log_level verbose
```

Push the Android libs + the V68 skel to the device (per SDK docs, the skel must be
reachable through `ADSP_LIBRARY_PATH`), run `qnn-net-run` from a shell, and then
inspect the profile with `qnn-profile-viewer`. Acceptance for this step: the
profile attributes execution to the HTP backend and the outputs match the CPU
backend within quantization tolerance. If *this* fails, stop: the problem is the
SDK/driver pairing, not this app.

## 3. App-level checks (what the UI must show)

| Where | Expected before any HTP work is wired | Expected after a real HTP adapter exists |
|---|---|---|
| Settings → Backend states, Qualcomm Hexagon HTP row | `Unavailable` (no SDK libs in APK), with the `dlopen` reason | `Available` → `Initialized` after context creation; `Verified execution` only after step 4 |
| Generate, "Execution target" | CPU (or HTP) + explicit blockers; **no image** | real image + the backend actually used |
| Benchmark Lab, HTP preference | run refused with prerequisite text, no numbers | measured micro-op/pipeline numbers |
| Diagnostics JSON | `hexagonVerified: false` | `true` only with the evidence below |

## 4. The verification that actually matters: execution, not presence

Merely loading `libQnnHtp.so` (or detecting `qcom`) proves nothing, so the app's
promotion rule is deliberately strict. A backend may be labelled
`VERIFIED_EXECUTION` only when all of the following hold in one session:

1. The same fixed-shape request was executed on HTP **and** on the CPU engine.
2. The HTP output digest agrees with the CPU reference within the documented
   relative tolerance (the digest is linear by design — see
   `slop_engine.h` — so fp16/int8 rounding shows up as a small difference rather
   than a hash mismatch).
3. Repeating the HTP run gives the same digest (determinism).
4. The QNN profile for the run attributes it to the HTP backend (cross-checked
   out-of-band with `qnn-profile-viewer`, not just the app's own claim).

Only then does `executionVerified` become true in an exported benchmark report,
and only then may a number be quoted as "HTP latency".

## 5. Thermal and memory honesty

The 778G+ throttles under sustained NPU load, so record rather than assume:

* Run 1 warm-up + ≥ 5 measured iterations; keep cold start separate.
* Capture `PowerManager.currentThermalStatus()` per phase (the benchmark report
  stores one thermal snapshot plus a point-in-time note) and note the ambient.
* Keep peak RSS (`getrusage` via the native engine) next to the timings — a
  "fast" run that is about to be LMK-killed is not a usable result on 8 GB.
* Export the JSON per run and compare saved runs in the Benchmark Lab; the
  comparison view already refuses to declare a winner across different backends
  without labelling it as such.

## 6. Recording the result

When the above passes, update the README status table (HTP row), and attach to
the change: `ro.soc.model`, Android build, SDK version string, model file name +
SHA-256 (from the library details screen), context-binary config used, the
benchmark JSON, and the `qnn-profile-viewer` excerpt. Any claim in the docs that
is not backed by that bundle should keep saying "not verified".
