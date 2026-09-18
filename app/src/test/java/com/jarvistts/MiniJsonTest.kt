package com.jarvistts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniJsonTest {
    @Test
    fun `stringify then parse round-trips a nested structure`() {
        val original =
            mapOf(
                "id" to "abc-123",
                "createdAt" to 42L,
                "title" to "Hello \"world\"\nnew line",
                "turns" to
                    listOf(
                        mapOf("speaker" to "USER", "text" to "hi", "durationMs" to 100L),
                        mapOf("speaker" to "JARVIS", "text" to "hello there", "durationMs" to 200L),
                    ),
            )

        val json = MiniJson.stringify(original)

        @Suppress("UNCHECKED_CAST")
        val parsed = MiniJson.parse(json) as Map<String, Any?>

        assertEquals("abc-123", parsed["id"])
        assertEquals(42.0, parsed["createdAt"])
        assertEquals("Hello \"world\"\nnew line", parsed["title"])
        @Suppress("UNCHECKED_CAST")
        val turns = parsed["turns"] as List<Map<String, Any?>>
        assertEquals(2, turns.size)
        assertEquals("USER", turns[0]["speaker"])
        assertEquals("hi", turns[0]["text"])
    }

    @Test
    fun `parse handles empty object and array`() {
        assertEquals(emptyMap<String, Any?>(), MiniJson.parse("{}"))
        assertEquals(emptyList<Any?>(), MiniJson.parse("[]"))
    }

    @Test
    fun `parse handles booleans and null`() {
        @Suppress("UNCHECKED_CAST")
        val parsed = MiniJson.parse("""{"a":true,"b":false,"c":null}""") as Map<String, Any?>
        assertEquals(true, parsed["a"])
        assertEquals(false, parsed["b"])
        assertNull(parsed["c"])
    }

    @Test
    fun `parse handles escaped special characters`() {
        val json = MiniJson.stringify("tab\there\\backslash\"quote")
        assertEquals("tab\there\\backslash\"quote", MiniJson.parse(json))
    }

    @Test
    fun `parse handles unicode escape`() {
        assertEquals("A", MiniJson.parse("\"\\u0041\""))
    }

    @Test
    fun `parse tolerates surrounding whitespace`() {
        @Suppress("UNCHECKED_CAST")
        val parsed = MiniJson.parse("  { \"a\" : 1 }  ") as Map<String, Any?>
        assertEquals(1.0, parsed["a"])
    }
}
