#!/usr/bin/env bash
# Type-checks the app module's NON-UI Kotlin sources against minimal Android
# stubs, so the repository/adapter/backend layer can be verified in sandboxes
# that cannot download the Android SDK or AndroidX artifacts.
#
# This is a compile check, not a build: the UI (Compose) sources and the real
# build are covered only by `./gradlew :app:assembleDebug` in a normal Android
# environment. See tools/offline-app-logic-check/README.md.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KOTLINC_HOME="${KOTLINC_HOME:?set KOTLINC_HOME to a JetBrains Kotlin compiler distribution}"
JAVA_HOME="${JAVA_HOME:-}"
KOTLINC="$KOTLINC_HOME/bin/kotlinc-jvm"
OUT="$SCRIPT_DIR/out"

CORE_SRC="$REPO_ROOT/core/src/main/kotlin"
APP_SRC="$REPO_ROOT/app/src/main/kotlin"

rm -rf "$OUT"
mkdir -p "$OUT/classes"

CP="$KOTLINC_HOME/lib/kotlin-stdlib.jar"
if [ -f "$KOTLINC_HOME/lib/kotlinx-coroutines-core-jvm.jar" ]; then
  CP="$CP:$KOTLINC_HOME/lib/kotlinx-coroutines-core-jvm.jar"
fi

# Everything except the Compose screens and the activity host, which need the
# real AndroidX/Compose artifacts (no stub can represent a @Composable body).
# The composition root (SlopApplication/AppContainer) *is* included.
FILES=$(find "$SCRIPT_DIR/stubs" "$CORE_SRC" -name '*.kt')
APP_FILES=$(find "$APP_SRC/com/slop4hexagon/app" -name '*.kt' \
  ! -path "*/ui/*" ! -name 'MainActivity.kt')

echo "== type-checking app non-UI sources against Android stubs =="
# shellcheck disable=SC2086
"$KOTLINC" -nowarn -cp "$CP" -d "$OUT/classes" $FILES $APP_FILES
echo "PASS  app non-UI + composition-root sources type-check (with stubs) against :core"
