package com.example.test_app.dialogue

/** Everything a rephrase call gets (Spec v2 section 8.1). */
data class RephraseRequest(
    /** Dialogue field key, e.g. `location`. */
    val field: String,
    val mode: DialogueMode,
    /** The safe default question. */
    val template: String,
    /** The user's words only. */
    val transcriptSoFar: String,
    /** `en`, `hi` or `te`. */
    val language: String,
)

/** Optional LLM layer that makes ONE question refer to what the user said. It never chooses what to ask. */
interface Rephraser {
    /** Returns a rephrased question, or null to use the template. Must never throw. */
    suspend fun rephrase(req: RephraseRequest): String?
}

/** Per-call LLM numbers for the JSONL log (all optional; Genie may not expose them). */
data class LlmCallMetrics(val promptTokens: Int? = null, val prefillMs: Long? = null, val decodeMs: Long? = null)

/** Implemented by a [Rephraser] that can report the metrics of its last call. */
interface LlmMetricsProvider {
    fun lastCallMetrics(): LlmCallMetrics?
}

/** The rephrase system prompt of Spec v2 section 8.2, rendered for an LLM engine. Short: stays far below the 500-token cap. */
object RephrasePrompt {
    private const val MAX_WORDS = 60

    fun render(req: RephraseRequest): String = """
        You rewrite ONE follow-up question for a voice incident-report assistant.
        Rules:
        - Ask exactly one question, about the field: ${req.field}. Nothing else.
        - Tone: ${req.mode.styleDescription}.
        - 20 words or fewer. End with a question mark.
        - You may refer to something the user already said.
        - This question is asked only once. Never say you are asking again or that an answer is needed.
        - Never ask "why". Never blame. Never give medical advice. Never promise help.
        - Output only the question text. No quotes, no explanation.
        Template to adapt: "${req.template}"
        User's words so far: "${trimWords(req.transcriptSoFar)}"
    """.trimIndent()

    /** The last [MAX_WORDS] words (build prompt section 4: rephrase prompts carry only the last ~60 words). */
    fun trimWords(text: String): String =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.takeLast(MAX_WORDS).joinToString(" ")
}
