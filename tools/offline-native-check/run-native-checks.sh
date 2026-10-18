#!/usr/bin/env bash
# Host-side verification of the native CPU engine (app/src/main/cpp).
#
# The production build for this code is `./gradlew :app:externalNativeBuildDebug`
# (needs the Android NDK). This script exists so the same kernels can be
# compiled, tested and sanitizer-checked on a plain Linux/macOS box or in a CI
# sandbox without the NDK — which is what was used while developing this repo.
#
# Usage: tools/offline-native-check/run-native-checks.sh [--skip-sanitizers]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CPP_DIR="$REPO_ROOT/app/src/main/cpp"
OUT="$SCRIPT_DIR/out"
SKIP_SAN="${1:-}"

CXX="${CXX:-g++}"
if ! command -v "$CXX" >/dev/null 2>&1; then
  echo "error: no C++ compiler found (set CXX)" >&2
  exit 2
fi

mkdir -p "$OUT"
FLAGS=(-std=c++17 -Wall -Wextra -Werror -ffp-contract=off -O2)

echo "== building and running native kernel tests =="
"$CXX" "${FLAGS[@]}" -o "$OUT/slop_native_tests" \
  "$CPP_DIR/native_tests.cpp" "$CPP_DIR/slop_engine.cpp"
"$OUT/slop_native_tests"

if [ "$SKIP_SAN" != "--skip-sanitizers" ]; then
  echo "== rebuilding with AddressSanitizer + UndefinedBehaviorSanitizer =="
  "$CXX" -std=c++17 -fsanitize=address,undefined -fno-omit-frame-pointer -g -O1 \
    -ffp-contract=off -o "$OUT/slop_native_tests_asan" \
    "$CPP_DIR/native_tests.cpp" "$CPP_DIR/slop_engine.cpp"
  ASAN_OPTIONS=detect_leaks=1 "$OUT/slop_native_tests_asan" >/dev/null
  echo "PASS  sanitizer run reported no errors"
fi

# Optional extra check when JDK JNI headers are available: proves the JNI glue
# and the QNN probe compile warning-free against a real jni.h. The Android build
# uses the NDK's jni.h, which provides the same API.
JNI_INC="${JNI_INC:-}"
if [ -z "$JNI_INC" ] && [ -n "${JAVA_HOME:-}" ] && [ -f "$JAVA_HOME/include/jni.h" ]; then
  JNI_INC="$JAVA_HOME/include"
fi
if [ -n "$JNI_INC" ] && [ -f "$JNI_INC/jni.h" ]; then
  echo "== compile-checking JNI glue against $JNI_INC =="
  "$CXX" -std=c++17 -Wall -Wextra -Werror -ffp-contract=off -O2 \
    -I"$JNI_INC" -I"$JNI_INC/linux" -c "$CPP_DIR/native_jni.cpp" -o "$OUT/native_jni.o"
  "$CXX" -std=c++17 -Wall -Wextra -Werror -O2 \
    -I"$JNI_INC" -I"$JNI_INC/linux" -c "$CPP_DIR/qnn_probe.cpp" -o "$OUT/qnn_probe.o"
  SYMS=$(nm "$OUT/native_jni.o" | grep -c "T Java_" || true)
  echo "PASS  JNI symbols exported: $SYMS"
  if [ "$SYMS" -lt 7 ]; then
    echo "error: expected at least 7 Java_* JNI entry points" >&2
    exit 1
  fi
else
  echo "note: set JNI_INC or JAVA_HOME (with include/jni.h) to compile-check the JNI glue"
fi

echo
echo "All native checks passed."
