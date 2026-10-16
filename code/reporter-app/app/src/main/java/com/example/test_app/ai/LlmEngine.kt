package com.example.test_app.ai

/** Why generation ended. */
enum class StopReason { END_OF_TURN, MAX_TOKENS, ABORTED }

/** One completed LLM call: raw model text plus timing, for logging and the eval report. */
data class LlmResult(
    val text: String,
    /** Length of the rendered prompt in characters (media excluded). */
    val promptChars: Int,
    /** Time from query start to the first generated token (approximately prefill; with media it includes the media encoder), ms. */
    val prefillMs: Long,
    /** Remaining generation time after the first chunk, ms. */
    val decodeMs: Long,
    /** Prompt size in tokens including media tokens; null when the engine does not report it (Genie). */
    val promptTokens: Int? = null,
    val generatedTokens: Int? = null,
    val stopReason: StopReason? = null,
)

/**
 * Chat-in/text-out on-device LLM. Implementations: `GenieLlmEngine` (Qwen3-4B on the Hexagon NPU, text only) and
 * `LlamaGemmaEngine` (Gemma 4 E2B through llama.cpp: text, audio, image). A [ChatPrompt] is rendered by the engine in its own
 * chat format. Every call MUST start from a fresh conversation: implementations reset state before generating.
 * See doc/MODEL_INTEGRATION.md.
 */
interface LlmEngine {
    /** Loads the model once. Safe to call repeatedly; later calls return immediately when already loaded. */
    suspend fun load()

    /**
     * Generates the assistant reply to [prompt] and returns the RAW model text (the continuation after [ChatPrompt.assistantPrefix]).
     * [maxTokens] bounds the reply where the engine supports it (Gemma; Genie ignores it). Cancelling the calling coroutine (for
     * example a timeout) stops a Gemma generation between tokens; Genie cannot be interrupted (ADR-036).
     */
    suspend fun generate(prompt: ChatPrompt, maxTokens: Int = DEFAULT_MAX_TOKENS): LlmResult

    /** Releases native resources. */
    fun close()

    companion object {
        const val DEFAULT_MAX_TOKENS = 512
    }
}
