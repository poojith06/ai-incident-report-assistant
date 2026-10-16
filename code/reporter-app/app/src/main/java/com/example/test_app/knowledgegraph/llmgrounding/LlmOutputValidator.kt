package com.example.test_app.knowledgegraph.llmgrounding

import com.example.test_app.knowledgegraph.rules.MedicalOutputGuard
import com.example.test_app.knowledgegraph.rules.PhoneNumberGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Result of validating one LLM reply: [accepted] maps recommendation id to the re-phrased text that passed; [problems] lists everything that was rejected (for the audit log). */
data class ValidationOutcome(val accepted: Map<String, String>, val problems: List<String>, val structureOk: Boolean)

/**
 * Spec section 14: Kotlin validates every LLM output. STRUCTURE (whole reply rejected): not JSON, no array of
 * `{recommendation_id, rendered_text}` items, an unknown id, a missing id or an id given twice. CONTENT (that item rejected): text that
 * is blank or much longer than the fact, a number or number word that the canonical text does not have, a capitalised name that the
 * canonical text does not have, a prohibited medical term, a phone number, or a claim that help was sent or notified.
 */
object LlmOutputValidator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val NUMBER = Regex("\\d+")
    private val NUMBER_WORDS = setOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "twenty", "thirty", "hundred", "thousand", "first", "second", "third", "half")
    private val DISPATCH_CLAIM = Regex("\\b(dispatched|on the way|has been notified|have been notified|been informed|help is coming|is coming|sent a team|are on their way|is on its way)\\b", RegexOption.IGNORE_CASE)
    private val WORD = Regex("[A-Za-z][A-Za-z'-]*")

    /** Words that may start a sentence of a re-phrased instruction without being "new entities". */
    private val STARTERS = setOf(
        "ask", "keep", "stay", "do", "report", "contact", "tell", "wait", "move", "make", "please", "call", "follow", "inform", "check", "let", "use", "avoid",
        "never", "only", "if", "when", "after", "before", "until", "this", "that", "the", "a", "an", "it", "they", "you", "your", "all", "any", "no", "not", "to",
    )

    fun validate(facts: List<ApprovedFact>, rawReply: String?): ValidationOutcome {
        val problems = mutableListOf<String>()
        val items = parse(rawReply) ?: return ValidationOutcome(emptyMap(), listOf("reply is not a JSON array of recommendation_id / rendered_text items"), false)
        val ids = facts.map { it.recommendationId }
        val seen = mutableSetOf<String>()
        for ((id, _) in items) {
            if (id !in ids) problems += "unknown recommendation id $id"
            if (!seen.add(id)) problems += "recommendation id $id given more than once"
        }
        (ids - seen).forEach { problems += "missing recommendation id $it" }
        if (problems.isNotEmpty() || items.size != facts.size) return ValidationOutcome(emptyMap(), problems.ifEmpty { listOf("wrong number of items") }, false)
        val accepted = linkedMapOf<String, String>()
        val byId = items.toMap()
        for (f in facts) {
            val text = byId.getValue(f.recommendationId).trim()
            val why = contentProblem(f, text)
            if (why == null) accepted[f.recommendationId] = text else problems += "${f.recommendationId}: $why"
        }
        return ValidationOutcome(accepted, problems, true)
    }

    private fun parse(raw: String?): List<Pair<String, String>>? {
        val text = raw?.trim()?.removePrefix("```json")?.removePrefix("```")?.removeSuffix("```")?.trim() ?: return null
        val element = try {
            val start = text.indexOfFirst { it == '[' || it == '{' }
            if (start < 0) return null
            json.parseToJsonElement(text.substring(start))
        } catch (e: Exception) {
            return null
        }
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["items"] ?: element["recommendations"]) as? JsonArray ?: return null
            else -> return null
        }
        return array.map { e ->
            val o = e as? JsonObject ?: return null
            val id = (o["recommendation_id"] as? JsonPrimitive)?.contentOrNull ?: return null
            val t = (o["rendered_text"] as? JsonPrimitive)?.contentOrNull ?: return null
            id to t
        }
    }

    private fun contentProblem(f: ApprovedFact, text: String): String? {
        if (text.isBlank()) return "empty text"
        if (text.length > f.canonicalText.length * 2 + 40) return "text is much longer than the approved fact"
        val allowedNumbers = NUMBER.findAll(f.canonicalText).map { it.value }.toSet()
        NUMBER.findAll(text).firstOrNull { it.value !in allowedNumbers }?.let { return "adds the number ${it.value}" }
        val canonicalWords = WORD.findAll(f.canonicalText).map { it.value.lowercase() }.toSet() + (f.ownerRole?.let { r -> WORD.findAll(r).map { it.value.lowercase() }.toSet() } ?: emptySet())
        val words = WORD.findAll(text).toList()
        words.firstOrNull { it.value.lowercase() in NUMBER_WORDS && it.value.lowercase() !in canonicalWords }?.let { return "adds the number word ${it.value}" }
        for (m in words) {
            val w = m.value
            if (!w[0].isUpperCase()) continue
            val lower = w.lowercase()
            val sentenceStart = m.range.first == 0 || text.substring(0, m.range.first).trimEnd().let { it.isEmpty() || it.last() in ".!?:;" }
            val ok = lower in canonicalWords || (sentenceStart && lower in STARTERS)
            if (!ok) return "adds the name or word $w"
        }
        val medical = MedicalOutputGuard.violations(text)
        if (medical.isNotEmpty()) return "prohibited medical wording ($medical)"
        if (PhoneNumberGuard.containsUnverifiedNumber(text)) return "contains a phone number"
        if (DISPATCH_CLAIM.containsMatchIn(text)) return "claims that help was sent or notified"
        return null
    }
}
