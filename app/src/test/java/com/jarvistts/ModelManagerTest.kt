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

    @Test
    fun `personaFor loads the persona matching the selected voice`() {
        val personas = mapOf("jarvis" to "You are JARVIS.", "picard" to "You are Captain Picard.")

        assertTrue(ModelManager.personaFor(personas, "picard").startsWith("You are Captain Picard."))
    }

    @Test
    fun `personaFor falls back to the default voice for an unknown voice`() {
        val personas = mapOf(ModelManager.DEFAULT_VOICE to "You are JARVIS.", "picard" to "You are Captain Picard.")

        val persona = ModelManager.personaFor(personas, "some-new-voice-with-no-entry")

        assertTrue(persona.startsWith("You are JARVIS."))
    }

    @Test
    fun `personaFor appends the spoken reply length hint`() {
        val personas = mapOf(ModelManager.DEFAULT_VOICE to "You are JARVIS.")

        val persona = ModelManager.personaFor(personas, ModelManager.DEFAULT_VOICE)

        assertTrue(persona.contains("Keep replies short"))
    }

    @Test
    fun `resolveSelectedVoice keeps the persisted voice when its clip is still available`() {
        val voice = ModelManager.resolveSelectedVoice("picard", listOf("jarvis", "picard"))

        assertEquals("picard", voice)
    }

    @Test
    fun `resolveSelectedVoice falls back to the first available voice when the persisted one is gone`() {
        val voice = ModelManager.resolveSelectedVoice("removed-voice", listOf("jarvis", "picard"))

        assertEquals("jarvis", voice)
    }

    @Test
    fun `resolveSelectedVoice falls back to the default voice name when no voice clips are bundled`() {
        val voice = ModelManager.resolveSelectedVoice("picard", emptyList())

        assertEquals(ModelManager.DEFAULT_VOICE, voice)
    }

    @Test
    fun `on app load the persona matches the persisted voice selection`() {
        val personas = mapOf("jarvis" to "You are JARVIS.", "picard" to "You are Captain Picard.")

        val selectedVoice = ModelManager.resolveSelectedVoice("picard", listOf("jarvis", "picard"))
        val persona = ModelManager.personaFor(personas, selectedVoice)

        assertTrue(persona.startsWith("You are Captain Picard."))
    }

    @Test
    fun `on app load a persisted voice with no clip anymore loads the fallback voice's persona`() {
        val personas = mapOf("jarvis" to "You are JARVIS.", "picard" to "You are Captain Picard.")

        val selectedVoice = ModelManager.resolveSelectedVoice("removed-voice", listOf("jarvis", "picard"))
        val persona = ModelManager.personaFor(personas, selectedVoice)

        assertTrue(persona.startsWith("You are JARVIS."))
    }

    @Test
    fun `resolveSelectedModelName keeps the persisted model when its file is still available`() {
        val name = ModelManager.resolveSelectedModelName("/models/custom.gguf", listOf("custom.gguf", "other.gguf"))

        assertEquals("custom.gguf", name)
    }

    @Test
    fun `resolveSelectedModelName falls back to the first available model when the persisted file is gone`() {
        val name = ModelManager.resolveSelectedModelName("/models/deleted.gguf", listOf("custom.gguf", "other.gguf"))

        assertEquals("custom.gguf", name)
    }

    @Test
    fun `resolveSelectedModelName falls back to the default filename when nothing is available`() {
        val name = ModelManager.resolveSelectedModelName("/models/deleted.gguf", emptyList())

        assertEquals(ModelManager.DEFAULT_LLM_FILENAME, name)
    }

    @Test
    fun `resolveSelectedModelName handles no persisted selection yet`() {
        val name = ModelManager.resolveSelectedModelName(null, listOf("custom.gguf"))

        assertEquals("custom.gguf", name)
    }
}
