package com.example.test_app.ai.genie

/**
 * JNI bindings to `libgenie_jni.so` (app/src/main/cpp/genie_jni.cpp), which wraps the QAIRT Genie C API.
 * Not thread-safe; [GenieLlmEngine] serialises calls on one thread. See doc/MODEL_INTEGRATION.md.
 */
internal class GenieNative {
    external fun nativeCreate(configJson: String, libDir: String): Long
    external fun nativeQuery(handle: Long, prompt: String): String
    external fun nativeLastTimings(handle: Long): LongArray
    external fun nativeReset(handle: Long)
    external fun nativeFree(handle: Long)

    companion object {
        init {
            System.loadLibrary("genie_jni")
        }
    }
}
