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
