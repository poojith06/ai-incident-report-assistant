package com.example.test_app.knowledgegraph.llmgrounding

import com.example.test_app.ai.ChatPrompt
import com.example.test_app.ai.Prompts
import com.example.test_app.knowledgegraph.model.RenderMode

/** A graph-approved statement the renderer may rephrase: nothing else may reach the LLM (spec section 14). */
data class ApprovedFact(val recommendationId: String, val canonicalText: String, val ownerRole: String? = null)

data class RenderedFact(val recommendationId: String, val text: String, val mode: RenderMode)

/** Spec section 14: turns approved facts into user-facing text. Implementations must return exactly one [RenderedFact] per input fact, in order. */
interface GroundedRecommendationRenderer {
    suspend fun render(facts: List<ApprovedFact>): List<RenderedFact>
}

/** Default renderer: the canonical graph text, unchanged. Deterministic, never fails. */
class TemplateRenderer : GroundedRecommendationRenderer {
    override suspend fun render(facts: List<ApprovedFact>): List<RenderedFact> = facts.map { RenderedFact(it.recommendationId, it.canonicalText, RenderMode.TEMPLATE) }
}

/**
 * Optional LLM path (OFF by default, ADR-056): asks the model ([generate], one fresh call built by `Prompts.groundedRephrase`) to
 * re-phrase the facts, then validates the reply with [LlmOutputValidator]. A reply that cannot be parsed, has missing, extra, unknown or
 * duplicate ids, or a call that fails or times out, gives the canonical text for ALL facts ([RenderMode.FALLBACK]); an item whose text adds a
 * number, a new capitalised name, medical wording, a phone number or a dispatch claim falls back for that item alone.
 */
class LlmGroundedRenderer(private val generate: suspend (ChatPrompt) -> String?) : GroundedRecommendationRenderer {
    override suspend fun render(facts: List<ApprovedFact>): List<RenderedFact> {
        if (facts.isEmpty()) return emptyList()
        val raw = try {
            generate(Prompts.groundedRephrase(facts.map { it.recommendationId to it.canonicalText }))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val outcome = LlmOutputValidator.validate(facts, raw)
        return facts.map { f ->
            val text = outcome.accepted[f.recommendationId]
            if (text != null) RenderedFact(f.recommendationId, text, RenderMode.LLM) else RenderedFact(f.recommendationId, f.canonicalText, RenderMode.FALLBACK)
        }
    }
}
