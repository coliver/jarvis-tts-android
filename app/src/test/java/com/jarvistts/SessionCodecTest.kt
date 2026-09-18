package com.jarvistts

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCodecTest {
    @Test
    fun `encode then decode round-trips a session`() {
        val session =
            ChatSession(
                id = "session-1",
                createdAt = 1000L,
                updatedAt = 2000L,
                title = "What's the weather",
                turns =
                    listOf(
                        Turn(Speaker.USER, "What's the weather like?", 500L),
                        Turn(Speaker.JARVIS, "Overcast, twelve degrees.", 800L),
                    ),
            )

        val decoded = SessionCodec.decode(SessionCodec.encode(session))

        assertEquals(session, decoded)
    }

    @Test
    fun `round-trips special characters in turn text`() {
        val session =
            ChatSession(
                id = "session-2",
                createdAt = 1L,
                updatedAt = 1L,
                title = "quotes and newlines",
                turns =
                    listOf(
                        Turn(Speaker.USER, "She said \"hello\"\nand left.", 0L),
                    ),
            )

        val decoded = SessionCodec.decode(SessionCodec.encode(session))

        assertEquals(session, decoded)
    }

    @Test
    fun `round-trips a session with no turns`() {
        val session = ChatSession(id = "empty", createdAt = 1L, updatedAt = 1L, title = "empty", turns = emptyList())

        val decoded = SessionCodec.decode(SessionCodec.encode(session))

        assertEquals(session, decoded)
    }
}
