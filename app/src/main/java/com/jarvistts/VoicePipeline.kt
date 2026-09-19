package com.jarvistts

import kotlin.math.sqrt

/** Pure, device-independent logic pulled out of MainActivity so it's unit-testable
 *  without a phone or emulator: transcript cleanup, the auto-stop-on-silence
 *  decision, and which past turns to feed back as conversation history. Chat
 *  *template* formatting (role tags, BOS/EOS) lives in native code via
 *  llama.cpp's llama_chat_apply_template(), which reads the model's own
 *  embedded template; this only decides which (role, text) pairs get sent.
 */
object VoicePipeline {
    private val NOISE_TAG = Regex("\\[[A-Z _]+\\]|\\([A-Za-z ]+\\)")

    // llama.cpp tokenizers for the small instruct models this app targets run
    // close to 4 characters/token for English text. There's no cheap way to
    // get an exact count without a native round-trip per candidate turn, so
    // this is a deliberate approximation, not a hard guarantee against
    // overflowing the context window.
    private const val CHARS_PER_TOKEN_ESTIMATE = 4

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

    /** Picks the most recent [turns] that fit within [tokenBudget] (estimated),
     *  as chat-role/text pairs in chronological order, for feeding back into
     *  the LLM prompt as conversation history. Always includes at least the
     *  single most recent turn, even if it alone exceeds the budget, so one
     *  long turn doesn't wipe out history entirely.
     */
    fun buildHistory(
        turns: List<Turn>,
        tokenBudget: Int,
    ): List<Pair<String, String>> {
        val charBudget = tokenBudget * CHARS_PER_TOKEN_ESTIMATE
        var usedChars = 0
        val picked = mutableListOf<Pair<String, String>>()
        for (turn in turns.asReversed()) {
            val cost = turn.text.length
            if (picked.isNotEmpty() && usedChars + cost > charBudget) break
            picked.add(roleFor(turn.speaker) to turn.text)
            usedChars += cost
        }
        return picked.asReversed()
    }

    private fun roleFor(speaker: Speaker) = if (speaker == Speaker.USER) "user" else "assistant"
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
