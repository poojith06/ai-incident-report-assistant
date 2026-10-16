package com.example.test_app.ai

import com.example.test_app.domain.config.IncidentConfig
import com.example.test_app.domain.model.ClarificationQuestion
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InputMode
import com.example.test_app.domain.service.ExtractionRequest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime

/** Shared helpers for the `ai` unit tests. */
internal object AiTestSupport {
    /** Fixed clock: Sunday 2026-10-11 14:00 IST. */
    val NOW: OffsetDateTime = OffsetDateTime.parse("2026-10-11T14:00:00+05:30")

    /** Raw model output fixture from `src/test/resources/llm_outputs` (hand-written, see README.txt there). */
    fun fixture(name: String): String =
        checkNotNull(AiTestSupport::class.java.classLoader.getResource("llm_outputs/$name")) { "missing fixture $name" }.readText()

    /** JUnit 4 `assertNotNull` returns nothing; this returns the value. */
    fun <T : Any> notNull(value: T?): T = checkNotNull(value) { "expected a value but got null" }

    fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    fun request(
        transcript: String,
        previous: IncidentReport? = null,
        answer: String? = null,
        field: String? = null,
    ) = ExtractionRequest(
        mode = InputMode.VOICE,
        originalTranscript = transcript,
        previousReport = previous,
        answerTranscript = answer,
        answeredQuestion = if (answer == null) null else ClarificationQuestion(listOfNotNull(field), "question?"),
        imageHints = emptyList(),
        media = emptyList(),
        config = IncidentConfig(),
    )

    inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName} but got $e", e)
        }
        throw AssertionError("expected ${T::class.simpleName} but nothing was thrown")
    }
}

/** Scripted [LlmEngine]: returns [reply] (or the result of [replyFor], given the ChatML rendering) and records every prompt. */
internal class FakeEngine(
    var reply: String = "",
    var loadError: Throwable? = null,
    var generateError: Throwable? = null,
    var delayMs: Long = 0,
    var replyFor: ((String) -> String)? = null,
) : LlmEngine {
    /** Every prompt as ChatML text (what Genie would get). */
    val prompts = mutableListOf<String>()
    val chatPrompts = mutableListOf<ChatPrompt>()
    val maxTokens = mutableListOf<Int>()
    var loads = 0

    override suspend fun load() {
        loads++
        loadError?.let { throw it }
    }

    override suspend fun generate(prompt: ChatPrompt, maxTokens: Int): LlmResult {
        val rendered = prompt.render(ChatFormat.CHATML)
        prompts += rendered
        chatPrompts += prompt
        this.maxTokens += maxTokens
        if (delayMs > 0) delay(delayMs)
        generateError?.let { throw it }
        return LlmResult(replyFor?.invoke(rendered) ?: reply, promptChars = rendered.length, prefillMs = 120, decodeMs = 340)
    }

    override fun close() = Unit
}
