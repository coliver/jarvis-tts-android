package com.jarvistts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelManagerTest {
    @Test
    fun `bundled personas asset parses and has a jarvis fallback entry`() {
        val json = File("src/main/assets/personas.json").readText()

        val personas = ModelManager.parsePersonas(json)

        assertTrue(personas.isNotEmpty())
        assertTrue(personas.containsKey(ModelManager.DEFAULT_VOICE))
        personas.forEach { (voice, persona) -> assertTrue("$voice has a blank persona", persona.isNotBlank()) }
    }

    @Test
    fun `parsePersonas reads voice name to persona text`() {
        val json =
            """
            {
              "jarvis": "You are JARVIS.",
              "picard": "You are Captain Picard."
            }
            """.trimIndent()

        val personas = ModelManager.parsePersonas(json)

        assertEquals("You are JARVIS.", personas["jarvis"])
        assertEquals("You are Captain Picard.", personas["picard"])
    }

    @Test
    fun `parsePersonas handles an empty object`() {
        assertEquals(emptyMap<String, String>(), ModelManager.parsePersonas("{}"))
    }
}
