package com.slop4hexagon.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {

    @Test
    fun parsesAndWritesScalars() {
        assertEquals(JsonValue.JsonBool(true), JsonValue.parse("true"))
        assertEquals(JsonValue.JsonNull, JsonValue.parse(" null "))
        assertEquals(JsonValue.JsonNumber(42.0), JsonValue.parse("42"))
        assertEquals(JsonValue.JsonNumber(-3.25), JsonValue.parse("-3.25"))
        assertEquals(JsonValue.JsonNumber(1.5e3), JsonValue.parse("1.5e3"))
        assertEquals(JsonValue.JsonString("hi"), JsonValue.parse("\"hi\""))
    }

    @Test
    fun roundTripsNestedDocument() {
        val text = """
            {"name":"model","size":123,"tags":["a","b"],"nested":{"ok":true,"missing":null},"neg":-7}
        """.trimIndent()
        val value = JsonValue.parseObject(text)
        assertEquals("model", value.getString("name"))
        assertEquals(123, value.getInt("size"))
        assertEquals(listOf("a", "b"), value.getStringList("tags"))
        assertEquals(true, value.getObject("nested")?.getBool("ok"))
        assertEquals(-7, value.getInt("neg"))
        val round = JsonValue.parse(value.toCompactString())
        assertEquals(value, round)
    }

    @Test
    fun writesEscapesCorrectly() {
        val obj = JsonObjectBuilder().put("s", "line\nbreak\t\"quote\"\\slash\u0001").build()
        val text = obj.toCompactString()
        val parsed = JsonValue.parse(text) as JsonValue.JsonObject
        assertEquals("line\nbreak\t\"quote\"\\slash\u0001", parsed.getString("s"))
        assertTrue(text.contains("\\n"))
        assertTrue(text.contains("\\u0001"))
    }

    @Test
    fun integralNumbersRoundTripExactly() {
        val big = 1_234_567_890_123L
        val obj = JsonObjectBuilder().put("n", big).build()
        val parsed = JsonValue.parseObject(obj.toCompactString())
        assertEquals(big, parsed.getLong("n"))
    }

    @Test
    fun prettyPrintIsStable() {
        val obj = JsonObjectBuilder().put("a", 1).putStrings("b", listOf("x")).build()
        val pretty = obj.toPrettyString()
        assertTrue(pretty.contains("\n"))
        assertEquals(obj, JsonValue.parse(pretty))
    }

    @Test
    fun rejectsMalformedDocuments() {
        for (bad in listOf("{", "[1,]", "{\"a\":}", "\"unterminated", "1 2", "tru", "")) {
            try {
                JsonValue.parse(bad)
                fail("Should have failed to parse: $bad")
            } catch (expected: JsonParseException) {
                // expected
            }
        }
    }

    @Test
    fun parseErrorsCarryPosition() {
        try {
            JsonValue.parse("{\"a\" 1}")
            fail("expected parse error")
        } catch (e: JsonParseException) {
            assertTrue(e.position >= 0)
        }
    }
}
