package com.jarvistts

/** Minimal recursive-descent JSON reader/writer. Hand-rolled so session
 *  persistence carries no external dependency and stays testable under
 *  plain JUnit: org.json is Android-stub-only outside Robolectric, and this
 *  repo deliberately sticks to fast local-only JUnit (see AGENTS.md).
 *  Handles exactly the subset this app needs: objects, arrays, strings,
 *  numbers, true/false/null. Not a general-purpose parser.
 */
object MiniJson {
    fun stringify(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> quote(value)
            is Boolean -> value.toString()
            is Number -> value.toString()
            is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k.toString())}:${stringify(v)}" }
            is List<*> -> value.joinToString(",", "[", "]") { stringify(it) }
            else -> error("Unsupported JSON value type: ${value::class}")
        }

    fun parse(json: String): Any? = Parser(json).parseValue()

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            val result =
                when (s[pos]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't' -> literal("true", true)
                    'f' -> literal("false", false)
                    'n' -> literal("null", null)
                    else -> parseNumber()
                }
            skipWhitespace()
            return result
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        break
                    }
                    else -> error("Expected ',' or '}' at $pos in $s")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        break
                    }
                    else -> error("Expected ',' or ']' at $pos in $s")
                }
            }
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (s[pos] != '"') {
                val c = s[pos]
                if (c == '\\') {
                    pos++
                    when (s[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'u' -> {
                            val hex = s.substring(pos + 1, pos + 5)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> error("Unknown escape \\${s[pos]} at $pos")
                    }
                    pos++
                } else {
                    sb.append(c)
                    pos++
                }
            }
            pos++
            return sb.toString()
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "-+.eE")) pos++
            return s.substring(start, pos).toDouble()
        }

        private fun literal(
            text: String,
            value: Any?,
        ): Any? {
            require(s.startsWith(text, pos)) { "Expected '$text' at $pos in $s" }
            pos += text.length
            return value
        }

        private fun expect(c: Char) {
            check(s[pos] == c) { "Expected '$c' at $pos in $s" }
            pos++
        }

        private fun peek(): Char = s[pos]

        private fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}
