package com.example.test_app.ai.whisper

/**
 * JNI bindings to `libwhisper_jni.so` (app/src/main/cpp/whisper_jni.cpp): whisper.cpp / ggml on the CPU.
 * CPU STOPGAP ASR, not the NPU Whisper. Not thread-safe; [WhisperCppSpeechToText] serialises calls on one thread.
 * See doc/MODEL_INTEGRATION.md.
 */
internal class WhisperNative {
    /** Loads a ggml model file; throws [RuntimeException] when it cannot be read. */
    external fun nativeCreate(modelPath: String): Long

    /** Greedy transcription of 16 kHz mono float PCM in [-1, 1] (never translates). [initialPrompt] may be null. */
    external fun nativeTranscribe(handle: Long, pcm: FloatArray, threads: Int, language: String, initialPrompt: String?): String

    external fun nativeFree(handle: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
