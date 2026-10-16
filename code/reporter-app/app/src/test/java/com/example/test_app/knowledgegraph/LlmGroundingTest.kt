package com.example.test_app.knowledgegraph

import com.example.test_app.ai.ChatRole
import com.example.test_app.knowledgegraph.llmgrounding.ApprovedFact
import com.example.test_app.knowledgegraph.llmgrounding.LlmGroundedRenderer
import com.example.test_app.knowledgegraph.llmgrounding.LlmOutputValidator
import com.example.test_app.knowledgegraph.llmgrounding.TemplateRenderer
import com.example.test_app.knowledgegraph.model.RenderMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec sections 14 and 20: the LLM only re-phrases approved facts and Kotlin validates every reply. */
class LlmGroundingTest {
    private val facts = listOf(
        ApprovedFact("rec:isolate", "Ask authorized electricians to isolate the power supply to the damaged desktop.", "Electrical maintenance"),
        ApprovedFact("rec:backup", "Ask authorized IT staff to preserve logs and available backups before restoration.", "IT operations"),
    )

    private fun reply(vararg items: Pair<String, String>) =
        items.joinToString(",", "[", "]") { (id, t) -> "{\"recommendation_id\": \"$id\", \"rendered_text\": \"$t\"}" }

    private val good = reply(
        "rec:isolate" to "Ask authorized electricians to isolate the power to the damaged desktop.",
        "rec:backup" to "Ask authorized IT staff to keep logs and backups before restoring anything.",
    )

    @Test fun aValidReplyIsAcceptedForEveryFact() {
        val out = LlmOutputValidator.validate(facts, good)
        assertTrue(out.structureOk); assertEquals(setOf("rec:isolate", "rec:backup"), out.accepted.keys); assertTrue(out.problems.isEmpty())
    }

    @Test fun codeFencesAndWrapperObjectsAreAccepted() {
        assertEquals(2, LlmOutputValidator.validate(facts, "```json\n$good\n```").accepted.size)
        assertEquals(2, LlmOutputValidator.validate(facts, "{\"items\": $good}").accepted.size)
    }

    @Test fun aMissingIdRejectsTheWholeReply() {
        val out = LlmOutputValidator.validate(facts, reply("rec:isolate" to "Ask authorized electricians to isolate the power to the damaged desktop."))
        assertFalse(out.structureOk); assertTrue(out.accepted.isEmpty()); assertTrue(out.problems.any { it.contains("missing recommendation id rec:backup") })
    }

    @Test fun anUnknownIdRejectsTheWholeReply() {
        val out = LlmOutputValidator.validate(facts, good.dropLast(1) + ",{\"recommendation_id\": \"rec:invented\", \"rendered_text\": \"Do something.\"}]")
        assertFalse(out.structureOk); assertTrue(out.problems.any { it.contains("unknown recommendation id rec:invented") })
    }

    @Test fun aDuplicateIdRejectsTheWholeReply() {
        val out = LlmOutputValidator.validate(facts, reply("rec:isolate" to "Ask authorized electricians to isolate the power.", "rec:isolate" to "Ask authorized electricians to isolate the power.", "rec:backup" to "Ask authorized IT staff to preserve logs."))
        assertFalse(out.structureOk); assertTrue(out.problems.any { it.contains("more than once") })
    }

    @Test fun notJsonOrNullRejectsTheWholeReply() {
        for (r in listOf("Sure, here are the steps!", "", null, "{\"a\": 1}", "[1, 2]", "[{\"recommendation_id\": \"rec:isolate\"}]")) {
            assertFalse(r, LlmOutputValidator.validate(facts, r).structureOk)
        }
    }

    @Test fun anAddedNumberIsRejectedForThatItemOnly() {
        val out = LlmOutputValidator.validate(facts, reply("rec:isolate" to "Ask authorized electricians to isolate the power within 30 minutes.", "rec:backup" to "Ask authorized IT staff to preserve logs and available backups."))
        assertTrue(out.structureOk); assertEquals(setOf("rec:backup"), out.accepted.keys); assertTrue(out.problems.single().contains("number 30"))
        val words = LlmOutputValidator.validate(facts, reply("rec:isolate" to "Ask authorized electricians to isolate the power in two steps.", "rec:backup" to "Ask authorized IT staff to preserve logs and available backups."))
        assertTrue(words.problems.single().contains("number word two"))
    }

    @Test fun aNewCapitalisedNameIsRejected() {
        val out = LlmOutputValidator.validate(facts, reply("rec:isolate" to "Ask authorized electricians from Nilgiri to isolate the power.", "rec:backup" to "Ask authorized IT staff to preserve logs and available backups."))
        assertEquals(setOf("rec:backup"), out.accepted.keys); assertTrue(out.problems.single().contains("Nilgiri"))
    }

    @Test fun aKnownRoleNameFromTheFactIsAllowed() {
        val out = LlmOutputValidator.validate(facts.take(1), reply("rec:isolate" to "Ask authorized electricians to isolate the power. Electrical maintenance owns this."))
        assertEquals(setOf("rec:isolate"), out.accepted.keys)
    }

    @Test fun medicalWordingIsRejected() {
        val out = LlmOutputValidator.validate(facts, reply("rec:isolate" to "Ask authorized electricians to isolate the power and take paracetamol.", "rec:backup" to "Ask authorized IT staff to treat the logs and preserve backups."))
        assertTrue(out.accepted.isEmpty()); assertEquals(2, out.problems.size)
        assertTrue(out.problems.all { it.contains("medical") })
    }

    @Test fun phoneNumbersAndDispatchClaimsAreRejected() {
        val out = LlmOutputValidator.validate(facts, reply("rec:isolate" to "Ask authorized electricians on 040 2345 6789 to isolate the power.", "rec:backup" to "Ask authorized IT staff to preserve logs because help is coming."))
        assertTrue(out.accepted.isEmpty())
        assertTrue(out.problems.any { it.contains("number") || it.contains("phone") }); assertTrue(out.problems.any { it.contains("claims") })
    }

    @Test fun anEmptyOrBloatedTextIsRejected() {
        val out = LlmOutputValidator.validate(facts, reply("rec:isolate" to "", "rec:backup" to "Ask authorized IT staff to preserve logs and available backups " + "and so on ".repeat(30)))
        assertTrue(out.accepted.isEmpty()); assertTrue(out.problems.any { it.contains("empty") }); assertTrue(out.problems.any { it.contains("longer") })
    }

    @Test fun theTemplateRendererReturnsTheCanonicalTextUnchanged() = runTest {
        val out = TemplateRenderer().render(facts)
        assertEquals(facts.map { it.canonicalText }, out.map { it.text }); assertTrue(out.all { it.mode == RenderMode.TEMPLATE })
    }

    @Test fun theLlmRendererUsesAValidReplyAndFallsBackOtherwise() = runTest {
        val ok = LlmGroundedRenderer { good }.render(facts)
        assertTrue(ok.all { it.mode == RenderMode.LLM }); assertEquals("Ask authorized IT staff to keep logs and backups before restoring anything.", ok[1].text)
        val bad = LlmGroundedRenderer { "nonsense" }.render(facts)
        assertTrue(bad.all { it.mode == RenderMode.FALLBACK }); assertEquals(facts.map { it.canonicalText }, bad.map { it.text })
        val partial = LlmGroundedRenderer { reply("rec:isolate" to "Ask authorized electricians to isolate the power in 5 minutes.", "rec:backup" to "Ask authorized IT staff to keep logs and backups.") }.render(facts)
        assertEquals(listOf(RenderMode.FALLBACK, RenderMode.LLM), partial.map { it.mode }); assertEquals(facts[0].canonicalText, partial[0].text)
    }

    @Test fun aFailingOrNullGenerationFallsBackToTheCanonicalText() = runTest {
        assertTrue(LlmGroundedRenderer { null }.render(facts).all { it.mode == RenderMode.FALLBACK })
        assertTrue(LlmGroundedRenderer { throw IllegalStateException("engine down") }.render(facts).all { it.mode == RenderMode.FALLBACK })
        assertTrue(LlmGroundedRenderer { good }.render(emptyList()).isEmpty())
    }

    @Test fun thePromptCarriesOnlyTheApprovedFactsAndTheContract() = runTest {
        var seen: com.example.test_app.ai.ChatPrompt? = null
        LlmGroundedRenderer { seen = it; good }.render(facts)
        val p = seen!!
        val system = p.messages.first { it.role == ChatRole.SYSTEM }.text
        assertTrue(system.startsWith("Rewrite only APPROVED_FACTS into concise user-facing steps."))
        assertTrue(system.contains("Do not add actions, causes, numbers, locations, medical guidance or safety claims.") && system.contains("Preserve owner roles, uncertainty and prerequisites."))
        val user = p.messages.first { it.role == ChatRole.USER }.text
        assertTrue(user.contains("rec:isolate") && user.contains("rec:backup") && user.contains(facts[0].canonicalText))
        assertTrue(p.media.isEmpty())
    }
}
