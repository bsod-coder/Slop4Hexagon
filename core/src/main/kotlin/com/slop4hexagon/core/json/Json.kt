package com.slop4hexagon.core.json

/**
 * Minimal, dependency-free JSON model + parser + writer.
 *
 * Used for model-library metadata, benchmark reports and diagnostic exports so
 * that the domain layer can be compiled and unit-tested on a plain JVM without
 * any third-party serialization stack. The wire format is standard JSON.
 */
sealed class JsonValue {

    object JsonNull : JsonValue() {
        override fun toString(): String = "null"
    }

    data class JsonBool(val value: Boolean) : JsonValue()

    data class JsonNumber(val value: Double) : JsonValue()

    data class JsonString(val value: String) : JsonValue()

    data class JsonArray(val items: List<JsonValue>) : JsonValue()

    data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue() {

        operator fun get(name: String): JsonValue? = fields[name]

        fun getString(name: String): String? = (fields[name] as? JsonString)?.value

        fun getBool(name: String): Boolean? = (fields[name] as? JsonBool)?.value

        fun getDouble(name: String): Double? = (fields[name] as? JsonNumber)?.value

        fun getLong(name: String): Long? = (fields[name] as? JsonNumber)?.value?.let {
            if (it == Math.floor(it) && !it.isInfinite() && Math.abs(it) <= MAX_SAFE_INTEGER) it.toLong() else null
        }

        fun getInt(name: String): Int? = getLong(name)?.let {
            if (it >= Int.MIN_VALUE && it <= Int.MAX_VALUE) it.toInt() else null
        }

        fun getObject(name: String): JsonObject? = fields[name] as? JsonObject

        fun getArray(name: String): JsonArray? = fields[name] as? JsonArray

        fun getStringList(name: String): List<String>? =
            (fields[name] as? JsonArray)?.items?.map { (it as? JsonString)?.value ?: return null }

        companion object {
            private const val MAX_SAFE_INTEGER = 9007199254740992.0
        }
    }

    /** Compact single-line serialization. */
    fun toCompactString(): String = StringBuilder().also { writeTo(it, pretty = false) }.toString()

    /** Pretty-printed serialization with two-space indentation. */
    fun toPrettyString(): String = StringBuilder().also { writeTo(it, pretty = true) }.toString()

    private fun writeTo(sb: StringBuilder, pretty: Boolean, indent: Int = 0) {
        when (this) {
            is JsonNull -> sb.append("null")
            is JsonBool -> sb.append(if (value) "true" else "false")
            is JsonNumber -> sb.append(formatNumber(value))
            is JsonString -> writeQuoted(sb, value)
            is JsonArray -> {
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    if (pretty) newlineAndIndent(sb, indent + 1)
                    item.writeTo(sb, pretty, indent + 1)
                }
                if (pretty) newlineAndIndent(sb, indent)
                sb.append(']')
            }
            is JsonObject -> {
                if (fields.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((key, value) in fields) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newlineAndIndent(sb, indent + 1)
                    writeQuoted(sb, key)
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    value.writeTo(sb, pretty, indent + 1)
                }
                if (pretty) newlineAndIndent(sb, indent)
                sb.append('}')
            }
        }
    }

    companion object {

        fun parse(text: String): JsonValue = Parser(text).parseDocument()

        fun parseObject(text: String): JsonObject =
            parse(text) as? JsonObject ?: throw JsonParseException("Expected a JSON object")

        private fun newlineAndIndent(sb: StringBuilder, indent: Int) {
            sb.append('\n')
            repeat(indent) { sb.append("  ") }
        }

        private fun writeQuoted(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c == '\b' -> sb.append("\\b")
                    c == '\u000C' -> sb.append("\\f")
                    c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }

        /** Prints integral values without a decimal point so Long round-trips stay exact. */
        private fun formatNumber(value: Double): String {
            if (value.isNaN() || value.isInfinite()) return "null"
            return if (value == Math.floor(value) && Math.abs(value) < 9.007199254740992E15) {
                value.toLong().toString()
            } else {
                value.toString()
            }
        }
    }
}

class JsonParseException(message: String, val position: Int = -1) :
    RuntimeException(if (position >= 0) "$message (at offset $position)" else message)

/** Mutable convenience builder for composing JSON objects. */
class JsonObjectBuilder {
    private val fields = LinkedHashMap<String, JsonValue>()

    fun put(name: String, value: JsonValue?): JsonObjectBuilder {
        fields[name] = value ?: JsonValue.JsonNull
        return this
    }

    fun put(name: String, value: String?): JsonObjectBuilder =
        put(name, value?.let { JsonValue.JsonString(it) })

    fun put(name: String, value: Boolean?): JsonObjectBuilder =
        put(name, value?.let { JsonValue.JsonBool(it) })

    fun put(name: String, value: Long?): JsonObjectBuilder =
        put(name, value?.let { JsonValue.JsonNumber(it.toDouble()) })

    fun put(name: String, value: Int?): JsonObjectBuilder =
        put(name, value?.let { JsonValue.JsonNumber(it.toDouble()) })

    fun put(name: String, value: Double?): JsonObjectBuilder =
        put(name, value?.let { JsonValue.JsonNumber(it) })

    fun putStrings(name: String, values: List<String>): JsonObjectBuilder {
        fields[name] = JsonValue.JsonArray(values.map { JsonValue.JsonString(it) })
        return this
    }

    fun build(): JsonValue.JsonObject = JsonValue.JsonObject(fields)
}

internal class Parser(private val text: String) {
    private var pos = 0

    fun parseDocument(): JsonValue {
        val value = parseValue()
        skipWhitespace()
        if (pos != text.length) fail("Trailing characters after JSON value")
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (pos >= text.length) fail("Unexpected end of input")
        return when (text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.JsonString(parseString())
            't' -> parseKeyword("true", JsonValue.JsonBool(true))
            'f' -> parseKeyword("false", JsonValue.JsonBool(false))
            'n' -> parseKeyword("null", JsonValue.JsonNull)
            else -> parseNumber()
        }
    }

    private fun parseObject(): JsonValue.JsonObject {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return JsonValue.JsonObject(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            fields[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonValue.JsonObject(fields)
                }
                else -> fail("Expected ',' or '}' in object")
            }
        }
    }

    private fun parseArray(): JsonValue.JsonArray {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return JsonValue.JsonArray(items)
        }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonValue.JsonArray(items)
                }
                else -> fail("Expected ',' or ']' in array")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("Unterminated string")
            when (val c = text[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= text.length) fail("Unterminated escape")
                    when (val e = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 > text.length) fail("Invalid unicode escape")
                            val hex = text.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: fail("Invalid unicode escape \\u$hex")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("Invalid escape '\\$e'")
                    }
                }
                else -> {
                    if (c.code < 0x20) fail("Unescaped control character in string")
                    sb.append(c)
                }
            }
        }
    }

    private fun parseNumber(): JsonValue.JsonNumber {
        val start = pos
        if (peek() == '-') pos++
        if (pos >= text.length || !text[pos].isDigit()) fail("Invalid number")
        while (pos < text.length && text[pos].isDigit()) pos++
        if (pos < text.length && text[pos] == '.') {
            pos++
            if (pos >= text.length || !text[pos].isDigit()) fail("Invalid number fraction")
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            if (pos >= text.length || !text[pos].isDigit()) fail("Invalid number exponent")
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        val literal = text.substring(start, pos)
        val value = literal.toDoubleOrNull() ?: fail("Invalid number literal '$literal'")
        return JsonValue.JsonNumber(value)
    }

    private fun parseKeyword(keyword: String, value: JsonValue): JsonValue {
        if (!text.startsWith(keyword, pos)) fail("Invalid literal")
        pos += keyword.length
        return value
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
    }

    private fun peek(): Char {
        if (pos >= text.length) fail("Unexpected end of input")
        return text[pos]
    }

    private fun expect(c: Char) {
        if (pos >= text.length || text[pos] != c) fail("Expected '$c'")
        pos++
    }

    private fun fail(message: String): Nothing = throw JsonParseException(message, pos)
}
