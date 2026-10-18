#!/usr/bin/env bash
# Compiles and RUNS the app-layer JVM unit tests in an air-gapped sandbox, by
# reusing the stub type-check harness and the offline JUnit shim/runner from
# tools/offline-test-runner.
#
# The canonical command remains:  ./gradlew :app:testDebugUnitTest
#
# Only the test classes that need no Android framework are compiled here; the
# Compose UI and the activity host are excluded (they cannot be stubbed).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KOTLINC_HOME="${KOTLINC_HOME:?set KOTLINC_HOME to a JetBrains Kotlin compiler distribution}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
KOTLINC="$KOTLINC_HOME/bin/kotlinc-jvm"
OUT="$SCRIPT_DIR/out-tests"

CP="$KOTLINC_HOME/lib/kotlin-stdlib.jar:$KOTLINC_HOME/lib/kotlinx-coroutines-core-jvm.jar"

rm -rf "$OUT"
mkdir -p "$OUT/classes"

STUBS="$SCRIPT_DIR/stubs"
CORE="$REPO_ROOT/core/src/main/kotlin"
CORE_TESTS="$REPO_ROOT/core/src/test/kotlin"
APP="$REPO_ROOT/app/src/main/kotlin"
APP_TESTS="$REPO_ROOT/app/src/test/kotlin"
SHIM="$REPO_ROOT/tools/offline-test-runner/src"

APP_SRC=$(find "$APP/com/slop4hexagon/app" -name '*.kt' ! -path "*/ui/*" ! -name 'MainActivity.kt')

echo "== compiling stubs + core + app(non-UI) + app tests =="
# shellcheck disable=SC2086
"$KOTLINC" -nowarn -cp "$CP" -d "$OUT/classes" \
  "$SHIM/JunitAnnotations.kt" "$SHIM/JunitAssert.kt" "$SHIM/OfflineTestRunner.kt" \
  $(find "$STUBS" -name '*.kt') \
  $(find "$CORE" -name '*.kt') \
  $APP_SRC \
  $(find "$APP_TESTS" -name '*.kt')

CLASSES=$(cd "$OUT/classes" && find . -name '*Test.class' ! -name '*\$*' \
  | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)

if [ -z "$CLASSES" ]; then
  echo "error: no app test classes found" >&2
  exit 2
fi

echo "== running app-layer logic tests =="
# shellcheck disable=SC2086
"$JAVA_BIN" -cp "$OUT/classes:$CP" OfflineTestRunnerKt $CLASSES

echo
echo "note: core's own suite (102 tests) is run by tools/offline-test-runner/run-jvm-tests-offline.sh"
# Core sources are compiled above only so the app tests can link against them;
# the core test sources are deliberately not included in this runner.
: "$CORE_TESTS"
