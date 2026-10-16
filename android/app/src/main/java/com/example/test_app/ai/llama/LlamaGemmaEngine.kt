package com.example.test_app.ai.llama

import android.content.Context
import android.util.Log
import com.example.test_app.ai.ChatFormat
import com.example.test_app.ai.ChatPrompt
import com.example.test_app.ai.LlmEngine
import com.example.test_app.ai.LlmResult
import com.example.test_app.ai.MediaPart
import com.example.test_app.ai.StopReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

/** How the engine finds and loads Gemma. Defaults = the verified `llama-server` command of `idk-test/MODELS_USAGE.md` section 5b. */
data class LlamaGemmaConfig(
    val modelDirName: String = "gemma",
    val modelFile: String = "gemma-4-E2B_q4_0-it.gguf",
    val mmprojFile: String = "gemma-4-E2B-it-mmproj.gguf",
    /** `--device HTP0`: the LLM on the Hexagon NPU. Never `GPUOpenCL` (aborts with `clEnqueueNDRangeKernel error -54`). `none` = CPU. */
    val llmDevice: String = "HTP0",
    /** `-mmdev GPUOpenCL`: the audio/image encoder on the Adreno GPU. `none` = CPU. */
    val mmprojDevice: String = "GPUOpenCL",
    /** `-c 4096`. */
    val contextSize: Int = 4096,
    /** `-t 6`. */
    val threads: Int = 6,
    /** `-ngl 99`. */
    val gpuLayers: Int = 99,
)

/**
 * Gemma-4-E2B-it (q4_0 GGUF + mmproj) through the Snapdragon build of llama.cpp: text, audio and image input.
 * The LLM runs on the Hexagon NPU, the mmproj on the Adreno GPU ([LlamaGemmaConfig]); greedy sampling, thinking off
 * (the Gemma 4 template with `enable_thinking = false`, [ChatFormat.GEMMA4]).
 *
 * - Model folder `files/gemma/` (external files dir first, then internal; copy with `run-as`, doc/DEVICE_SETUP.md section 8).
 * - One dedicated thread ("llama-gemma"): the llama context is not thread-safe. The KV cache is cleared before EVERY call.
 * - Cancelling the calling coroutine (timeout, user cancel) aborts the generation between tokens and waits until the native call
 *   has returned, so the next call never queues behind a zombie query (unlike Genie, ADR-036). A prefill (media encode) in
 *   progress is only interrupted between chunks.
 *
 * Docs: doc/MODEL_INTEGRATION.md.
 */
class LlamaGemmaEngine(
    private val context: Context,
    private val config: LlamaGemmaConfig = LlamaGemmaConfig(),
) : LlmEngine {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "llama-gemma") }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val native by lazy { LlamaNative() }

    @Volatile private var handle: Long = 0

    val isLoaded: Boolean get() = handle != 0L

    /** Candidate model folders, in order: app external files dir, then internal files dir (debug-friendly via `run-as`). */
    val candidateDirs: List<File> get() = candidateDirs(context, config)

    /** The first folder holding both files; the first candidate when none does (the error then names it). */
    val modelDir: File get() = modelDir(context, config)

    /** Loads the model and the mmproj once (the first call can take seconds: files are read, the NPU session and the GPU kernels are set up). */
    override suspend fun load() = withContext(dispatcher) {
        if (handle != 0L) return@withContext
        val dir = modelDir
        val model = File(dir, config.modelFile)
        val mmproj = File(dir, config.mmprojFile)
        check(model.canRead() && mmproj.canRead()) {
            "Missing or unreadable Gemma files (${config.modelFile}, ${config.mmprojFile}) in ${candidateDirs.joinToString()}. See doc/DEVICE_SETUP.md."
        }
        val started = System.currentTimeMillis()
        handle = native.nativeCreate(
            model.absolutePath, mmproj.absolutePath, context.applicationInfo.nativeLibraryDir,
            config.llmDevice, config.mmprojDevice, config.contextSize, config.threads, config.gpuLayers,
        )
        Log.i(TAG, "Gemma loaded in ${System.currentTimeMillis() - started} ms (llm=${config.llmDevice} mmproj=${config.mmprojDevice} ctx=${config.contextSize})")
    }

    /** Devices ggml sees, one `name|description|type|free|total` per line (diagnostics; initialises the backends). */
    suspend fun listDevices(): String = withContext(dispatcher) { native.nativeListDevices(context.applicationInfo.nativeLibraryDir) }

    override suspend fun generate(prompt: ChatPrompt, maxTokens: Int): LlmResult {
        check(handle != 0L) { "LlamaGemmaEngine.load() has not completed" }
        val work = scope.async { generateBlocking(prompt, maxTokens) }
        try {
            return work.await()
        } catch (e: CancellationException) {
            // The caller gave up (timeout / cancel): stop the native generation and wait for it, so the single thread is free again.
            withContext(NonCancellable) {
                while (!work.isCompleted) {
                    native.nativeAbort(handle)
                    delay(5)
                }
            }
            throw e
        }
    }

    private fun generateBlocking(prompt: ChatPrompt, maxTokens: Int): LlmResult {
        val h = handle
        check(h != 0L) { "LlamaGemmaEngine is closed" }
        val rendered = prompt.render(ChatFormat.GEMMA4)
        val media = PackedMedia.pack(prompt.media)
        val started = System.nanoTime()
        val text = native.nativeGenerate(h, rendered, media.kinds, media.audio, media.images, media.imageDims, maxTokens)
        val wallMs = (System.nanoTime() - started) / 1_000_000
        val s = native.nativeLastStats(h)
        val stop = when (s[5].toInt()) {
            0 -> StopReason.END_OF_TURN
            1 -> StopReason.MAX_TOKENS
            else -> StopReason.ABORTED
        }
        Log.i(
            TAG,
            "generate promptChars=${rendered.length} promptTokens=${s[0]} media=${media.kinds.size} prefillMs=${s[3]} genTokens=${s[2]} decodeMs=${s[4]} " +
                "wallMs=$wallMs stop=$stop outChars=${text.length}",
        )
        return LlmResult(
            text = text, promptChars = rendered.length, prefillMs = s[3], decodeMs = s[4],
            promptTokens = s[0].toInt(), generatedTokens = s[2].toInt(), stopReason = stop,
        )
    }

    override fun close() {
        executor.execute {
            if (handle != 0L) native.nativeFree(handle)
            handle = 0
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "LlamaGemmaEngine"

        fun candidateDirs(context: Context, config: LlamaGemmaConfig = LlamaGemmaConfig()): List<File> =
            listOfNotNull(context.getExternalFilesDir(null), context.filesDir).map { File(it, config.modelDirName) }

        fun modelDir(context: Context, config: LlamaGemmaConfig = LlamaGemmaConfig()): File {
            val dirs = candidateDirs(context, config)
            return dirs.firstOrNull { File(it, config.modelFile).canRead() && File(it, config.mmprojFile).canRead() } ?: dirs.first()
        }

        /** True when the GGUF and the mmproj are on the device (no engine, thread or native library needed). */
        fun modelPresent(context: Context, config: LlamaGemmaConfig = LlamaGemmaConfig()): Boolean {
            val dir = modelDir(context, config)
            return File(dir, config.modelFile).canRead() && File(dir, config.mmprojFile).canRead()
        }
    }
}

/** A prompt's media in the arrays the JNI layer takes (pure; unit-tested). */
internal class PackedMedia(val kinds: IntArray, val audio: Array<FloatArray>, val images: Array<ByteArray>, val imageDims: IntArray) {
    companion object {
        fun pack(media: List<MediaPart>): PackedMedia {
            val kinds = IntArray(media.size) { if (media[it] is MediaPart.Audio) 1 else 0 }
            val audio = media.filterIsInstance<MediaPart.Audio>().map { it.pcm16k }.toTypedArray()
            val images = media.filterIsInstance<MediaPart.Image>()
            val dims = IntArray(images.size * 2) { if (it % 2 == 0) images[it / 2].width else images[it / 2].height }
            return PackedMedia(kinds, audio, images.map { it.rgb }.toTypedArray(), dims)
        }
    }
}
