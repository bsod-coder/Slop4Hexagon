/*
 * Minimal JUnit 4-compatible shim used ONLY for air-gapped verification of the
 * JVM unit tests (see tools/offline-test-runner/README.md). The Gradle build
 * uses the real junit:junit artifact; test sources compile unchanged against
 * both. This file is not part of the application.
 *
 * Annotations live in package org.junit; assertion functions live as top-level
 * functions in package org.junit.Assert so that `import
 * org.junit.Assert.assertEquals` resolves exactly like JUnit 4's static
 * methods do.
 */
package org.junit

import kotlin.reflect.KClass

/** Marker for "no expected exception" (mirrors org.junit.Test.None). */
class TestNone : Throwable()

/** Thrown when a shim assertion fails; behaves like JUnit's AssertionError. */
class AssertionFailure(message: String) : AssertionError(message)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test(
    val expected: KClass<out Throwable> = TestNone::class,
    val timeout: Long = 0L,
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Before
