package com.jarvistts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePipelineTest {
    @Test
    fun `cleanTranscript strips bracketed whisper tags`() {
        assertEquals(
            "Hey, what's going on there? I'm saying words.",
            VoicePipeline.cleanTranscript("Hey, what's going on there? I'm saying words. [BLANK_AUDIO]"),
        )
    }

    @Test
    fun `cleanTranscript strips parenthesized noise tags`() {
        assertEquals("Testing.", VoicePipeline.cleanTranscript("Testing. (silence)"))
    }

    @Test
    fun `cleanTranscript trims surrounding whitespace`() {
        assertEquals("Hello there", VoicePipeline.cleanTranscript("  Hello there  "))
    }

    @Test
    fun `cleanTranscript leaves plain speech untouched`() {
        assertEquals("What time is it?", VoicePipeline.cleanTranscript("What time is it?"))
    }

    @Test
    fun `rms of silence is zero`() {
        val silence = ShortArray(100)
        assertEquals(0.0, VoicePipeline.rms(silence, silence.size), 0.0001)
    }

    @Test
    fun `rms of constant amplitude equals that amplitude`() {
        val samples = ShortArray(100) { 1000 }
        assertEquals(1000.0, VoicePipeline.rms(samples, samples.size), 0.0001)
    }

    @Test
    fun `rms only considers the requested count`() {
        val samples = shortArrayOf(1000, 1000, 0, 0)
        assertEquals(1000.0, VoicePipeline.rms(samples, 2), 0.0001)
    }

    @Test
    fun `buildHistory maps speakers to chat roles in chronological order`() {
        val turns =
            listOf(
                Turn(Speaker.USER, "hello", 0),
                Turn(Speaker.JARVIS, "hi there", 0),
            )
        assertEquals(
            listOf("user" to "hello", "assistant" to "hi there"),
            VoicePipeline.buildHistory(turns, tokenBudget = 1000),
        )
    }

    @Test
    fun `buildHistory returns nothing for an empty transcript`() {
        assertTrue(VoicePipeline.buildHistory(emptyList(), tokenBudget = 1000).isEmpty())
    }

    @Test
    fun `buildHistory drops oldest turns once the budget is exceeded`() {
        // Each turn is 10 chars, ~2-3 tokens at the 4-chars-per-token estimate.
        val turns = (1..20).map { Turn(Speaker.USER, "x".repeat(10), 0) }
        val history = VoicePipeline.buildHistory(turns, tokenBudget = 10)
        assertTrue(history.size < turns.size)
        assertTrue(history.isNotEmpty())
    }

    @Test
    fun `buildHistory always keeps the single most recent turn even over budget`() {
        val turns = listOf(Turn(Speaker.USER, "x".repeat(1000), 0))
        assertEquals(1, VoicePipeline.buildHistory(turns, tokenBudget = 1).size)
    }

    @Test
    fun `buildHistory keeps only the most recent turns, not the oldest`() {
        val turns = listOf(Turn(Speaker.USER, "first", 0), Turn(Speaker.JARVIS, "second", 0))
        // Budget only large enough for one short turn.
        val history = VoicePipeline.buildHistory(turns, tokenBudget = 2)
        assertEquals(listOf("assistant" to "second"), history)
    }
}

class SilenceDetectorTest {
    private fun detector() = SilenceDetector(rmsThreshold = 400.0, minSpeechMs = 300, silenceHangMs = 1000)

    @Test
    fun `never stops on leading silence before any speech`() {
        val d = detector()
        repeat(50) {
            assertFalse(d.accept(rms = 0.0, chunkMs = 100))
        }
    }

    @Test
    fun `stops after silenceHangMs of quiet following enough speech`() {
        val d = detector()
        // 400ms of speech clears minSpeechMs (300ms)
        assertFalse(d.accept(rms = 1000.0, chunkMs = 100))
        assertFalse(d.accept(rms = 1000.0, chunkMs = 100))
        assertFalse(d.accept(rms = 1000.0, chunkMs = 100))
        assertFalse(d.accept(rms = 1000.0, chunkMs = 100))
        // now silence: needs 1000ms (10 x 100ms chunks) to trigger stop
        repeat(9) { assertFalse(d.accept(rms = 0.0, chunkMs = 100)) }
        assertTrue(d.accept(rms = 0.0, chunkMs = 100))
    }

    @Test
    fun `brief silence gap does not stop if speech resumes`() {
        val d = detector()
        assertFalse(d.accept(rms = 1000.0, chunkMs = 400)) // clears minSpeechMs
        assertFalse(d.accept(rms = 0.0, chunkMs = 500)) // pause, but under hang threshold
        assertFalse(d.accept(rms = 1000.0, chunkMs = 100)) // resumes speech, resets silence clock
        repeat(9) { assertFalse(d.accept(rms = 0.0, chunkMs = 100)) }
        assertTrue(d.accept(rms = 0.0, chunkMs = 100))
    }

    @Test
    fun `speech below threshold never counts as speech`() {
        val d = detector()
        repeat(50) {
            assertFalse(d.accept(rms = 399.0, chunkMs = 100))
        }
    }
}
