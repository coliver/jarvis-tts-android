package com.jarvistts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        store = SessionStore(tmp.newFolder("sessions"))
    }

    private fun turns(vararg texts: String) = texts.map { Turn(Speaker.USER, it, 0L) }

    @Test
    fun `create persists a session and returns it with a generated id`() {
        val created = store.create(turns("What time is it?"))

        assertTrue(created.id.isNotBlank())
        assertEquals(created.createdAt, created.updatedAt)
        assertEquals("What time is it?", created.title)
        assertEquals(store.load(created.id), created)
    }

    @Test
    fun `create rejects an empty turn list`() {
        assertThrows(IllegalArgumentException::class.java) { store.create(emptyList()) }
    }

    @Test
    fun `load returns null for an unknown id`() {
        assertNull(store.load("does-not-exist"))
    }

    @Test
    fun `update overwrites turns and title but keeps the original id and createdAt`() {
        val created = store.create(turns("first question"))
        // Guarantee a distinct updatedAt millisecond so ordering assertions elsewhere are meaningful.
        Thread.sleep(5)

        val updated = store.update(created.id, turns("first question", "second question"))

        assertEquals(created.id, updated.id)
        assertEquals(created.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt > created.updatedAt)
        assertEquals("first question", updated.title)
        assertEquals(2, updated.turns.size)
        assertEquals(updated, store.load(created.id))
    }

    @Test
    fun `update on an id with no existing file recreates it`() {
        val updated = store.update("fresh-id", turns("hello"))

        assertEquals("fresh-id", updated.id)
        assertEquals(updated, store.load("fresh-id"))
    }

    @Test
    fun `list returns summaries most-recently-updated first`() {
        val a = store.create(turns("alpha"))
        Thread.sleep(5)
        val b = store.create(turns("beta"))
        Thread.sleep(5)
        store.update(a.id, turns("alpha", "alpha again"))

        val ids = store.list().map { it.id }

        assertEquals(listOf(a.id, b.id), ids)
    }

    @Test
    fun `list skips files that fail to parse`() {
        store.create(turns("valid session"))
        val corrupt = java.io.File(tmp.root, "sessions/corrupt.json")
        corrupt.writeText("not valid json")

        val summaries = store.list()

        assertEquals(1, summaries.size)
        assertEquals("valid session", summaries[0].title)
    }

    @Test
    fun `list is empty when the directory does not exist yet`() {
        val emptyStore = SessionStore(java.io.File(tmp.root, "never-created"))

        assertTrue(emptyStore.list().isEmpty())
    }

    @Test
    fun `delete removes the session and returns true, false when already gone`() {
        val created = store.create(turns("to be deleted"))

        assertTrue(store.delete(created.id))
        assertNull(store.load(created.id))
        assertFalse(store.delete(created.id))
    }

    @Test
    fun `title falls back to the first user turn even if jarvis spoke first in the list`() {
        val created =
            store.create(
                listOf(
                    Turn(Speaker.JARVIS, "Good evening.", 0L),
                    Turn(Speaker.USER, "What's on my calendar?", 0L),
                ),
            )

        assertEquals("What's on my calendar?", created.title)
    }

    @Test
    fun `title truncates long text with an ellipsis`() {
        val longText = "a".repeat(80)

        val created = store.create(turns(longText))

        assertEquals("a".repeat(48) + "...", created.title)
    }
}
