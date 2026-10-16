package com.example.test_app.ai.llama

/**
 * JNI bindings to `libllama_jni.so` (app/src/main/cpp/llama_jni.cpp), which wraps the prebuilt llama.cpp b11552 Snapdragon libraries
 * (libllama, libmtmd, libggml*, in `jniLibs`, pulled by `tools/pull_llama_libs.sh`). Not thread-safe except [nativeAbort];
 * [LlamaGemmaEngine] serialises calls on one thread. See doc/MODEL_INTEGRATION.md.
 */
internal class LlamaNative {
    /**
     * Initialises the ggml backends (sets `ADSP_LIBRARY_PATH` to [libDir]) and lists the devices, one per line:
     * `name|description|type|freeBytes|totalBytes` (expect `HTP0` and `GPUOpenCL` on the QIDK).
     */
    external fun nativeListDevices(libDir: String): String

    /**
     * Loads the LLM on [llmDevice] (`HTP0`; `none` = CPU only) and the mmproj on [mmprojDevice] (`GPUOpenCL`; `none` = CPU).
     * Throws [RuntimeException] with the reason (unknown device lists the available ones).
     */
    external fun nativeCreate(
        modelPath: String, mmprojPath: String, libDir: String, llmDevice: String, mmprojDevice: String,
        nCtx: Int, nThreads: Int, nGpuLayers: Int,
    ): Long

    /**
     * Greedy generation (temperature 0, KV cache cleared first) for a prompt in the model's chat format with one `<__media__>` marker
     * per entry of [kinds] (0 = next image of [images] with its size at [imageDims] `[w0, h0, w1, h1, ...]`, 1 = next audio of [audio]).
     * Returns the raw text; statistics via [nativeLastStats]. Throws [RuntimeException] on failure (including a prompt that does not fit the context).
     */
    external fun nativeGenerate(
        handle: Long, prompt: String, kinds: IntArray, audio: Array<FloatArray>, images: Array<ByteArray>, imageDims: IntArray, maxTokens: Int,
    ): String

    /** `[promptTokens, promptPositions, generatedTokens, prefillMs, decodeMs, stop (0 end of turn, 1 max tokens, 2 aborted), mediaChunks]`. */
    external fun nativeLastStats(handle: Long): LongArray

    /** Thread-safe: makes the running [nativeGenerate] return between chunks / tokens. */
    external fun nativeAbort(handle: Long)

    external fun nativeFree(handle: Long)

    companion object {
        init {
            System.loadLibrary("llama_jni")
        }
    }
}
