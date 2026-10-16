package com.example.test_app.ai

import com.example.test_app.dialogue.DialogueFields
import com.example.test_app.dialogue.LanguageDetect
import com.example.test_app.dialogue.LlmCallMetrics
import com.example.test_app.dialogue.LlmMetricsProvider
import com.example.test_app.dialogue.RephraseRequest
import com.example.test_app.dialogue.Rephraser
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.service.ExtractionException
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.IncidentExtractionService
import com.example.test_app.domain.service.IncidentJsonCodec
import com.example.test_app.domain.service.ModelLoadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.OffsetDateTime

/**
 * [IncidentExtractionService] on the on-device LLM ([LlmEngine]: Qwen3 via Genie or Gemma 4 via llama.cpp). First pass: extraction
 * prompt -> [LlmOutputParser] -> [ModelOutputMapper.firstPass]. Follow-up answers: `injury`, `injured_person`, `injury_type` are
 * mapped by Kotlin rules (no LLM call); every other answer and free corrections use the update prompt and merge the returned
 * JSON into the previous report. An unreadable update leaves the report unchanged (the dialogue then marks the field unverified).
 * [precomputed] (Gemma one-call narration) may supply the first-pass JSON for a transcript so no second LLM call is needed.
 * See `doc/DIALOGUE.md` ("Extraction contract") and `doc/MODEL_INTEGRATION.md`.
 */
class LlmIncidentExtractionService(
    private val engine: LlmEngine,
    private val precomputed: PrecomputedFirstPass? = null,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) : IncidentExtractionService, LlmMetricsProvider {

    @Volatile private var lastMetrics: LlmCallMetrics? = null

    /** Prompt length (characters) of the last LLM call; null before the first call or after a call without LLM. */
    @Volatile var lastPromptChars: Int? = null
        private set

    override fun lastCallMetrics(): LlmCallMetrics? = lastMetrics

    override suspend fun extract(request: ExtractionRequest): String {
        try {
            engine.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ModelLoadException("The on-device assistant could not be loaded.", e)
        } catch (e: LinkageError) {
            throw ModelLoadException("The on-device assistant could not be loaded.", e)
        }
        val now = clock()
        val previous = request.previousReport
        val report = if (previous == null) firstPass(request, now) else update(request, previous, now)
        return IncidentJsonCodec.encode(report)
    }

    private suspend fun firstPass(request: ExtractionRequest, now: OffsetDateTime): IncidentReport {
        val transcript = request.originalTranscript
        // photo descriptions ride in the normal extraction prompt: the one-call JSON (made from the audio alone) is not used then
        val json = (if (request.imageHints.isEmpty()) precomputed?.take(transcript) else null) ?: run {
            // nobody spoke, only photos: the photo-only prompt says so (ADR-062); with a narration the photo is an extra PHOTO line
            val prompt = if (transcript.isBlank() && request.imageHints.isNotEmpty()) Prompts.photoOnlyExtraction(request.imageHints) else Prompts.extraction(transcript, request.imageHints)
            val text = generate(prompt, FIRST_PASS_MAX_TOKENS)
            LlmOutputParser.parseObject(text, Prompts.EXTRACTION_PREFIX)
        }
        if (json == null || json.isEmpty()) throw ExtractionException("The assistant returned something I couldn't read.")
        return ModelOutputMapper.firstPass(json, transcript, request, now)
    }

    private suspend fun update(request: ExtractionRequest, previous: IncidentReport, now: OffsetDateTime): IncidentReport {
        val answer = request.answerTranscript.orEmpty().trim()
        if (answer.isEmpty()) return previous
        val field = request.answeredQuestion?.targetFields?.firstOrNull()?.takeIf { it in DialogueFields.PRIORITY }
        if (!ModelOutputMapper.needsLlm(field)) {
            lastPromptChars = null
            return ModelOutputMapper.applyUpdate(previous, null, field, translateIfNeeded(field, answer), now)
        }
        val prompt = Prompts.update(ModelOutputMapper.currentForm(previous), Prompts.askedKey(field), answer)
        val text = generate(prompt, UPDATE_MAX_TOKENS)
        val json = LlmOutputParser.parseObject(text, Prompts.UPDATE_PREFIX)
        return ModelOutputMapper.applyUpdate(previous, json, field, answer, now)
    }

    /**
     * An answer in ANY non-Latin script (Tamil, Odia, Bengali/Assamese, Devanagari, ... any Indian language, no language list) to a
     * Kotlin-parsed field (`injured_person`, `injury_type`) is translated by the model ONCE and then read by the same English parsers
     * (ask-once still holds: this is not a re-ask). A translation failure keeps the original, which the parsers then leave
     * unmapped (`unverified`).
     */
    private suspend fun translateIfNeeded(field: String?, answer: String): String {
        if (!LanguageDetect.hasNonLatin(answer)) return answer
        return try {
            LlmOutputParser.cleanLine(generate(Prompts.translateAnswer(answer), TRANSLATE_MAX_TOKENS))
                ?.takeIf { it.isNotBlank() && !LanguageDetect.hasNonLatin(it) } ?: answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtractionException) {
            answer
        }
    }

    private suspend fun generate(prompt: ChatPrompt, maxTokens: Int): String {
        try {
            val result = engine.generate(prompt, maxTokens)
            lastPromptChars = result.promptChars
            lastMetrics = LlmCallMetrics(promptTokens = result.promptTokens, prefillMs = result.prefillMs, decodeMs = result.decodeMs)
            return result.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtractionException) {
            throw e
        } catch (e: Exception) {
            throw ExtractionException("The assistant couldn't process that.", e)
        }
    }

    private companion object {
        const val FIRST_PASS_MAX_TOKENS = 400
        const val UPDATE_MAX_TOKENS = 200
        const val TRANSLATE_MAX_TOKENS = 60
    }
}

/**
 * First-pass extraction JSON that was already produced together with the transcript (Gemma one-call narration:
 * audio + photos in one LLM call). [take] returns it once for the matching transcript, else null.
 */
fun interface PrecomputedFirstPass {
    fun take(transcript: String): kotlinx.serialization.json.JsonObject?
}

/**
 * [Rephraser] on the on-device LLM: one short generation (thinking off), cleaned to a single line. A timeout cancels the call,
 * which stops a Gemma generation between tokens (Genie cannot be interrupted, ADR-036).
 * Never throws (except coroutine cancellation); returns null on timeout, engine failure or empty output so the
 * dialogue falls back to the template question. Validation of the text is the dialogue's job (`PhraseValidator`).
 */
class LlmRephraser(
    private val engine: LlmEngine,
    private val timeoutMs: Long = 1500,
) : Rephraser, LlmMetricsProvider {

    @Volatile private var lastMetrics: LlmCallMetrics? = null

    /** Prompt length (characters) of the last completed call; null before the first. */
    @Volatile var lastPromptChars: Int? = null
        private set

    override fun lastCallMetrics(): LlmCallMetrics? = lastMetrics

    override suspend fun rephrase(req: RephraseRequest): String? =
        try {
            withTimeoutOrNull(timeoutMs) {
                engine.load()
                val result = engine.generate(Prompts.rephrase(req), REPHRASE_MAX_TOKENS)
                lastPromptChars = result.promptChars
                lastMetrics = LlmCallMetrics(promptTokens = result.promptTokens, prefillMs = result.prefillMs, decodeMs = result.decodeMs)
                LlmOutputParser.cleanLine(result.text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        /** A rephrased question is at most 20 words. */
        const val REPHRASE_MAX_TOKENS = 48
    }
}
