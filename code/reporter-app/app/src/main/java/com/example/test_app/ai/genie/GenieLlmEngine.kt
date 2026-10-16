package com.example.test_app.ai.genie

import android.content.Context
import android.util.Log
import com.example.test_app.ai.ChatFormat
import com.example.test_app.ai.ChatPrompt
import com.example.test_app.ai.LlmEngine
import com.example.test_app.ai.LlmResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.Executors

/**
 * Qwen3-4B on the Hexagon NPU through Genie.
 *
 * - Model folder: `<externalFilesDir>/qwen4b` (see doc/DEVICE_SETUP.md); contains `genie_config.json`, tokenizer, 4 ctx bins.
 * - Relative paths inside `genie_config.json` are made absolute in memory (the file on disk is not edited).
 * - Sampler is forced to deterministic (temp 0.1, top-k 1) in memory, as the integration guide requires.
 * - One dedicated thread: Genie is not thread-safe. The dialog is reset before EVERY call so no history leaks.
 * - QAIRT runtime libs ship in the APK (jniLibs) and are found in `nativeLibraryDir`.
 *
 * Docs: doc/MODEL_INTEGRATION.md.
 */
class GenieLlmEngine(
    private val context: Context,
    private val modelDirName: String = DEFAULT_MODEL_DIR,
) : LlmEngine {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "genie-llm") }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val native = GenieNative()
    private var handle: Long = 0

    /** Candidate model folders, in order: app external files dir, then internal files dir (debug-friendly via `run-as`). */
    val candidateDirs: List<File>
        get() = listOfNotNull(context.getExternalFilesDir(null), context.filesDir).map { File(it, modelDirName) }

    val modelDir: File get() = candidateDirs.firstOrNull { File(it, "genie_config.json").canRead() } ?: candidateDirs.first()

    override suspend fun load() = withContext(dispatcher) {
        if (handle != 0L) return@withContext
        val dir = modelDir
        val configFile = File(dir, "genie_config.json")
        check(configFile.canRead()) { "Missing or unreadable genie_config.json in ${candidateDirs.joinToString()}. See doc/DEVICE_SETUP.md." }
        val started = System.currentTimeMillis()
        handle = native.nativeCreate(patchConfig(configFile.readText(), dir), context.applicationInfo.nativeLibraryDir)
        Log.i(TAG, "Qwen loaded in ${System.currentTimeMillis() - started} ms")
    }

    /** Renders [prompt] as ChatML (Qwen3). [maxTokens] is ignored: Genie's JNI layer does not expose a per-call limit (doc/MODEL_INTEGRATION.md). */
    override suspend fun generate(prompt: ChatPrompt, maxTokens: Int): LlmResult = withContext(dispatcher) {
        check(handle != 0L) { "GenieLlmEngine.load() has not completed" }
        val rendered = prompt.render(ChatFormat.CHATML)
        native.nativeReset(handle) // fresh conversation for every call
        val text = native.nativeQuery(handle, rendered)
        val (firstMs, totalMs) = native.nativeLastTimings(handle).let { it[0] to it[1] }
        val result = LlmResult(text, rendered.length, prefillMs = firstMs, decodeMs = (totalMs - firstMs).coerceAtLeast(0))
        Log.i(TAG, "generate promptChars=${rendered.length} outChars=${text.length} prefillMs=${result.prefillMs} decodeMs=${result.decodeMs}")
        result
    }

    override fun close() {
        executor.execute {
            if (handle != 0L) native.nativeFree(handle)
            handle = 0
        }
        executor.shutdown()
    }

    companion object {
        const val DEFAULT_MODEL_DIR = "qwen4b"
        private const val TAG = "GenieLlmEngine"
        private val json = Json { prettyPrint = false }

        /** Makes relative file names absolute and forces the deterministic sampler. Pure; unit-testable. */
        fun patchConfig(configJson: String, modelDir: File): String {
            fun abs(name: String) = if (name.startsWith("/")) name else File(modelDir, name).absolutePath
            fun patch(path: List<String>, el: JsonElement): JsonElement = when {
                path.lastOrNull() == "path" && path.getOrNull(path.size - 2) == "tokenizer" -> JsonPrimitive(abs(el.jsonPrimitive.content))
                path.lastOrNull() == "extensions" && el is JsonPrimitive -> JsonPrimitive(abs(el.content))
                path.lastOrNull() == "ctx-bins" -> JsonArray(el.jsonArray.map { JsonPrimitive(abs(it.jsonPrimitive.content)) })
                path.lastOrNull() == "sampler" && el is JsonObject -> buildJsonObject {
                    el.forEach { (k, v) -> put(k, v) }
                    put("temp", JsonPrimitive(0.1))
                    put("top-k", JsonPrimitive(1))
                }
                el is JsonObject -> JsonObject(el.mapValues { (k, v) -> patch(path + k, v) })
                else -> el
            }
            return json.encodeToString(JsonElement.serializer(), patch(emptyList(), json.parseToJsonElement(configJson)))
        }
    }
}
