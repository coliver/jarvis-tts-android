package com.jarvistts

object NativeSTT {
    init {
        System.loadLibrary("jarvis_stt")
    }

    external fun nativeInit(modelPath: String): Long

    external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
    ): String

    external fun nativeDestroy(handle: Long)
}
