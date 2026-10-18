/*
 * JUnit 4 Assert API shim (top-level functions). See JunitAnnotations.kt.
 */
package org.junit.Assert

import org.junit.AssertionFailure

fun assertTrue(message: String?, condition: Boolean) {
    if (!condition) throw AssertionFailure(message ?: "expected true")
}

fun assertTrue(condition: Boolean) = assertTrue(null, condition)

fun assertFalse(message: String?, condition: Boolean) = assertTrue(message, !condition)

fun assertFalse(condition: Boolean) = assertFalse(null, condition)

fun assertEquals(message: String?, expected: Any?, actual: Any?) {
    if (expected != actual) {
        throw AssertionFailure((message?.plus(": ") ?: "") + "expected <$expected> but was <$actual>")
    }
}

fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)

fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
    if (Math.abs(expected - actual) > delta) {
        throw AssertionFailure((message?.plus(": ") ?: "") + "expected <$expected> but was <$actual> (delta $delta)")
    }
}

fun assertEquals(expected: Double, actual: Double, delta: Double) =
    assertEquals(null, expected, actual, delta)

fun assertNotEquals(message: String?, unexpected: Any?, actual: Any?) {
    if (unexpected == actual) {
        throw AssertionFailure((message?.plus(": ") ?: "") + "unexpected value <$actual>")
    }
}

fun assertNotEquals(unexpected: Any?, actual: Any?) = assertNotEquals(null, unexpected, actual)

fun assertNull(message: String?, actual: Any?) {
    if (actual != null) throw AssertionFailure((message?.plus(": ") ?: "") + "expected null but was <$actual>")
}

fun assertNull(actual: Any?) = assertNull(null, actual)

fun assertNotNull(message: String?, actual: Any?) {
    if (actual == null) throw AssertionFailure((message?.plus(": ") ?: "") + "expected non-null")
}

fun assertNotNull(actual: Any?) = assertNotNull(null, actual)

fun assertArrayEquals(message: String?, expected: ByteArray, actual: ByteArray) {
    if (!expected.contentEquals(actual)) {
        throw AssertionFailure((message?.plus(": ") ?: "") + "byte arrays differ")
    }
}

fun assertArrayEquals(expected: ByteArray, actual: ByteArray) = assertArrayEquals(null, expected, actual)

fun fail(message: String?): Nothing = throw AssertionFailure(message ?: "failed")
