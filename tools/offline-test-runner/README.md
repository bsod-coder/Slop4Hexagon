# Offline test runner

Air-gapped fallback for executing the `:core` JVM unit tests when Maven
Central and the Gradle distribution server are unreachable (the canonical
command `./gradlew :core:test` needs network access to resolve the Android and
Kotlin Gradle plugins plus `junit:junit`).

This is **not** a second test suite: the script compiles the *same* JUnit test
sources from `core/src/test/kotlin` and runs them reflectively. A tiny
JUnit-4-compatible shim (`src/JunitShim.kt`) provides `org.junit.Test`,
`org.junit.Before` and `org.junit.Assert` so the sources compile without the
`junit:junit` artifact. Assertions in the shim implement the same semantics as
JUnit 4 for the API surface the tests use.

## Usage

```sh
export KOTLINC_HOME=/path/to/kotlinc   # JetBrains Kotlin compiler distribution
export JAVA_HOME=/path/to/jdk          # any JDK 17+
tools/offline-test-runner/run-jvm-tests-offline.sh
```

The script prints one `PASS`/`FAIL` line per test and exits non-zero on
failure.

## Scope

Only pure-JVM tests are covered. Android instrumented/UI tests and the Gradle
build itself still require a normal development environment — see the README
"Build instructions" section.
