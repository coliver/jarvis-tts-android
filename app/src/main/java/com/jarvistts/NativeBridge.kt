package com.jarvistts

object NativeBridge {
    init {
        System.loadLibrary("jarvis_tts")
    }

    external fun create(
        modelsDir: String,
        voicesDir: String,
        tokenizerPath: String,
        precision: String,
        temperature: Float,
        lsdSteps: Int,
        numThreads: Int,
    ): Long

    external fun warmup(handle: Long): Double

    external fun destroy(handle: Long)

    external fun streamStart(
        handle: Long,
        text: String,
        voice: String,
    ): Long

    external fun streamRead(streamCtx: Long): FloatArray?

    external fun streamEnd(streamCtx: Long)
}
