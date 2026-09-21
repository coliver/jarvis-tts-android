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
    private fun detector(speechMultiplier: Double = 1.8) =
        SilenceDetector(minSpeechMs = 300, silenceHangMs = 1000, speechMultiplier = speechMultiplier)

    /** Feeds the 3 calibration chunks every test needs before the detector
     *  starts classifying speech vs. silence, so each test can control what
     *  noise floor it's testing against.
     */
    private fun calibrate(
        d: SilenceDetector,
        ambientRms: Double,
    ) {
        repeat(3) { assertFalse(d.accept(rms = ambientRms, chunkMs = 100)) }
    }

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
        calibrate(d, ambientRms = 0.0)
        // 400ms of speech clears minSpeechMs (300ms)
        repeat(4) { assertFalse(d.accept(rms = 1000.0, chunkMs = 100)) }
        // now silence: needs 1000ms (10 x 100ms chunks) to trigger stop
        repeat(9) { assertFalse(d.accept(rms = 0.0, chunkMs = 100)) }
        assertTrue(d.accept(rms = 0.0, chunkMs = 100))
    }

    @Test
    fun `brief silence gap does not stop if speech resumes`() {
        val d = detector()
        calibrate(d, ambientRms = 0.0)
        assertFalse(d.accept(rms = 1000.0, chunkMs = 400)) // clears minSpeechMs
        assertFalse(d.accept(rms = 0.0, chunkMs = 500)) // pause, but under hang threshold
        assertFalse(d.accept(rms = 1000.0, chunkMs = 100)) // resumes speech, resets silence clock
        repeat(9) { assertFalse(d.accept(rms = 0.0, chunkMs = 100)) }
        assertTrue(d.accept(rms = 0.0, chunkMs = 100))
    }

    @Test
    fun `audio only a little louder than the noise floor never counts as speech`() {
        val d = detector()
        calibrate(d, ambientRms = 80.0)
        // floor calibrates to 80, multiplier 1.8 -> speech threshold 144; stay just under it.
        repeat(50) {
            assertFalse(d.accept(rms = 143.0, chunkMs = 100))
        }
    }

    @Test
    fun `loud background noise alone is never mistaken for endless speech`() {
        // Reproduces the reported bug: office background chatter measured on a
        // real device at RMS 1600-5100, well above what a fixed low threshold
        // assumed. The floor calibrates to that level, so ambient-only chunks
        // correctly read as non-speech instead of holding the mic open.
        val d = detector()
        calibrate(d, ambientRms = 3000.0)
        repeat(50) {
            assertFalse(d.accept(rms = 3000.0, chunkMs = 100))
        }
    }

    @Test
    fun `speech well above a loud noise floor is still detected and stops once the user goes quiet again`() {
        val d = detector()
        calibrate(d, ambientRms = 3000.0)
        // Well above the 3000 * 1.8 = 5400 speech threshold.
        repeat(4) { assertFalse(d.accept(rms = 9000.0, chunkMs = 100)) }
        // Drops back to the same ambient level once the user stops talking.
        repeat(9) { assertFalse(d.accept(rms = 3000.0, chunkMs = 100)) }
        assertTrue(d.accept(rms = 3000.0, chunkMs = 100))
    }
}
