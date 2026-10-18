#!/usr/bin/env bash
# Compiles and runs the :core JVM unit tests without Gradle/Maven access.
# Intended for air-gapped CI sandboxes; the canonical command is:
#
#     ./gradlew :core:test
#
# Prerequisites (any JDK 17+ and a Kotlin compiler distribution):
#   export KOTLINC_HOME=/path/to/kotlinc      (must contain bin/kotlinc-jvm)
#   export JAVA_HOME=/path/to/jdk             (optional if java is on PATH)
#
# The script compiles:
#   1) tools/offline-test-runner/src (JUnit 4 API shim + reflective runner)
#   2) core/src/main/kotlin         (production domain code)
#   3) core/src/test/kotlin         (the real JUnit test sources)
# and then executes every *Test class through the reflective runner.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KOTLINC_HOME="${KOTLINC_HOME:-}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if [ -z "$KOTLINC_HOME" ] || [ ! -x "$KOTLINC_HOME/bin/kotlinc-jvm" ]; then
  echo "error: set KOTLINC_HOME to a JetBrains Kotlin compiler distribution (bin/kotlinc-jvm missing)" >&2
  exit 2
fi
KOTLINC="$KOTLINC_HOME/bin/kotlinc-jvm"
if ! command -v "$JAVA_BIN" >/dev/null 2>&1 && [ ! -x "$JAVA_BIN" ]; then
  echo "error: java not found (set JAVA_HOME)" >&2
  exit 2
fi

OUT="$SCRIPT_DIR/out"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

# The Kotlin compiler distribution ships kotlinx-coroutines-core; include it
# when present so tests may use runBlocking (Gradle resolves the same artifact
# from Maven Central). kotlin-stdlib is always required at runtime.
CP="$KOTLINC_HOME/lib/kotlin-stdlib.jar"
if [ -f "$KOTLINC_HOME/lib/kotlinx-coroutines-core-jvm.jar" ]; then
  CP="$CP:$KOTLINC_HOME/lib/kotlinx-coroutines-core-jvm.jar"
fi

echo "== compiling test shim + core + tests =="
# shellcheck disable=SC2086
"$KOTLINC" -nowarn \
  ${CP:+-cp "$CP"} \
  -d "$OUT/classes" \
  "$SCRIPT_DIR/src" \
  "$REPO_ROOT/core/src/main/kotlin" \
  "$REPO_ROOT/core/src/test/kotlin"

echo "== discovering test classes =="
TEST_CLASSES=$(cd "$OUT/classes" && find . -name '*Test.class' ! -name '*\$*' \
  | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)

if [ -z "$TEST_CLASSES" ]; then
  echo "error: no test classes found" >&2
  exit 2
fi

echo "== running tests =="
# shellcheck disable=SC2086
"$JAVA_BIN" -cp "$OUT/classes${CP:+:$CP}" OfflineTestRunnerKt $TEST_CLASSES
