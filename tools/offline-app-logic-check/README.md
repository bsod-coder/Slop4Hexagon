# Offline app-logic type check

`check-app-logic.sh` **type-checks** the `:app` module's non-UI Kotlin sources
(repositories, SAF import, backend adapters, benchmark runner, diagnostics,
composition root) against the minimal `android.*` / `androidx.datastore.*`
declarations in `stubs/`.

## Why this exists

The real build (`./gradlew :app:assembleDebug`) needs the Android SDK, the
Android Gradle Plugin, the Compose artifacts and `junit`, all of which come
from Google's Maven and Maven Central. In a network-restricted sandbox
(only `github.com`, `registry.npmjs.org`, `pypi.org` reachable) none of that can
be downloaded, so the app module would otherwise be completely unverified.

Type-checking against stubs catches the mistakes that matter for this layer —
wrong API names (e.g. `OpenableColumns.DISPLAY_NAME` vs the real
`OpenableColumns.Display.DISPLAY_NAME`), wrong argument types, non-existent
members — while needing nothing but a Kotlin compiler.

## What it does NOT prove

* It is **not** a build and **not** a test run. No bytecode here ever runs on a
  device; nothing validates runtime behaviour.
* The Compose screens, `MainActivity` and `SlopTheme` are **excluded**: a
  `@Composable` body needs the Compose compiler plugin and the Compose
  runtime, which cannot be stubbed meaningfully. In this session those files
  were reviewed by hand and parse-checked, but not type-checked.
* The stubs intentionally model only the members this repo uses, with real
  signatures where they were known; if a stub disagrees with the platform, the
  platform wins and the Gradle build is the authority.

## Usage

```sh
export KOTLINC_HOME=/path/to/kotlinc   # JetBrains Kotlin compiler distribution
export JAVA_HOME=/path/to/jdk          # JDK 17+
tools/offline-app-logic-check/check-app-logic.sh
```
