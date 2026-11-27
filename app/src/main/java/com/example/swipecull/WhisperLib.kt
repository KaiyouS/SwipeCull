package com.example.swipecull

/** Thin bridge to whisper.cpp (see app/src/main/cpp). */
object WhisperLib {
    init {
        System.loadLibrary("swipecull_whisper")
    }

    @JvmStatic external fun nativeInit(path: String): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeTranscribe(handle: Long, pcm: FloatArray, lang: String, threads: Int): ByteArray
}
