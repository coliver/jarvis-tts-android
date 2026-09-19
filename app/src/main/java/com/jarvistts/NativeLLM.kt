package com.jarvistts

object NativeLLM {
    init {
        System.loadLibrary("jarvis_llm")
    }

    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    external fun nativeInit(
        modelPath: String,
        nCtx: Int,
        listener: ProgressListener?,
    ): Long

    /** [historyRoles]/[historyTexts] are parallel arrays (role is "user" or
     *  "assistant") of prior turns to include before [userText], oldest first.
     */
    external fun nativeFormatPrompt(
        handle: Long,
        systemPrompt: String,
        historyRoles: Array<String>,
        historyTexts: Array<String>,
        userText: String,
    ): String

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
    ): String

    /** Flags an in-flight nativeGenerate() call (running on another thread)
     *  to stop at the next token boundary. Safe to call from any thread;
     *  nativeGenerate returns whatever it had accumulated so far.
     */
    external fun nativeCancelGenerate(handle: Long)

    external fun nativeDestroy(handle: Long)
}
