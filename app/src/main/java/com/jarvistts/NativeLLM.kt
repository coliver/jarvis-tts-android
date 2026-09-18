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

    external fun nativeFormatPrompt(
        handle: Long,
        systemPrompt: String,
        userText: String,
    ): String

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
    ): String

    external fun nativeDestroy(handle: Long)
}
