package com.jarvistts

import kotlin.math.sqrt

/** Pure, device-independent logic pulled out of MainActivity so it's unit-testable
 *  without a phone or emulator: transcript cleanup and the auto-stop-on-silence
 *  decision. Chat prompt formatting lives in native code via llama.cpp's
 *  llama_chat_apply_template(), which reads the model's own embedded template.
 */
object VoicePipeline {
    private val NOISE_TAG = Regex("\\[[A-Z _]+\\]|\\([A-Za-z ]+\\)")

    fun cleanTranscript(raw: String): String = raw.replace(NOISE_TAG, "").trim()

    fun rms(
        samples: ShortArray,
        count: Int,
    ): Double {
        if (count <= 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until count) sumSquares += (samples[i].toDouble() * samples[i])
        return sqrt(sumSquares / count)
    }
}

/** Tracks speech/silence timing across audio chunks and decides when recording
 *  should auto-stop: once real speech has been heard, then it's been quiet for
 *  [silenceHangMs]. Silence before any speech (e.g. lead-in) never triggers a stop.
 */
class SilenceDetector(
    private val rmsThreshold: Double,
    private val minSpeechMs: Int,
    private val silenceHangMs: Int,
) {
    private var speechMs = 0
    private var silentMs = 0

    /** Feed one chunk's RMS level and duration. Returns true if recording should stop now. */
    fun accept(
        rms: Double,
        chunkMs: Int,
    ): Boolean {
        if (rms > rmsThreshold) {
            speechMs += chunkMs
            silentMs = 0
            return false
        }
        if (speechMs < minSpeechMs) return false
        silentMs += chunkMs
        return silentMs >= silenceHangMs
    }
}
