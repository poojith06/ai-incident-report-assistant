package com.example.test_app.ai

import com.example.test_app.ai.AiTestSupport.fixture
import com.example.test_app.ai.AiTestSupport.notNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmOutputParserTest {
    private fun extraction(raw: String): JsonObject? = LlmOutputParser.parseObject(raw, Prompts.EXTRACTION_PREFIX)
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private val sevenKeys = setOf("incident_type", "severity", "location", "datetime", "persons_involved", "actions_taken", "description")

    @Test fun plainContinuationGetsPrefixPrepended() {
        val json = notNull(extraction(fixture("extract_water_leak_plain.txt")))
        assertEquals(sevenKeys, json.keys)
        assertEquals("Water Leak", json.str("incident_type"))
        assertEquals("around 10 AM", json.str("datetime"))
        assertEquals(JsonNull, json["persons_involved"])
    }

    @Test fun repeatedBraceIsCollapsed() {
        val json = notNull(extraction(fixture("extract_repeated_brace.txt")))
        assertEquals("Fire", json.str("incident_type"))
        assertEquals(sevenKeys, json.keys)
    }

    @Test fun repeatedPrefixWithoutBraceIsNotDoubled() {
        val json = notNull(extraction("\"incident_type\": \"Fire\", \"severity\": null}"))
        assertEquals("Fire", json.str("incident_type"))
        assertEquals(JsonNull, json["severity"])
    }

    @Test fun fullObjectWithSpaceAfterBrace() {
        val json = notNull(extraction("{ \"incident_type\": \"Spill\" }"))
        assertEquals("Spill", json.str("incident_type"))
    }

    @Test fun thinkBlockIsStripped() {
        val json = notNull(extraction(fixture("extract_think_block.txt")))
        assertEquals("Spill", json.str("incident_type"))
        assertEquals("corridor", json.str("location"))
    }

    @Test fun trailingImEndAndNextTurnAreCut() {
        val json = notNull(extraction(fixture("extract_trailing_im_end.txt")))
        assertEquals("Gas Leak", json.str("incident_type"))
        assertEquals("10:30 pm", json.str("datetime"))
        assertEquals(sevenKeys, json.keys)
    }

    @Test fun truncatedOutputDropsTheIncompleteLastPair() {
        val json = notNull(extraction(fixture("extract_truncated.txt")))
        assertEquals("Injury", json.str("incident_type"))
        assertEquals("a worker", json.str("persons_involved"))
        assertTrue("description" !in json.keys)
        assertEquals(6, json.size)
    }

    @Test fun truncatedAfterCompletePairIsClosed() {
        val json = notNull(extraction(" \"Fire\", \"severity\": \"High\""))
        assertEquals("High", json.str("severity"))
    }

    @Test fun truncatedAfterDanglingKeyDropsIt() {
        val json = notNull(extraction(" \"Fire\", \"severity\":"))
        assertEquals(setOf("incident_type"), json.keys)
    }

    @Test fun codeFenceAndChatterAreIgnored() {
        val json = notNull(extraction(fixture("extract_fence_and_chatter.txt")))
        assertEquals("Equipment Failure", json.str("incident_type"))
        assertEquals("this morning", json.str("datetime"))
    }

    @Test fun chatterWithoutFence() {
        val json = notNull(extraction("Here you go: {\"incident_type\": \"Other\"} hope it helps"))
        assertEquals("Other", json.str("incident_type"))
    }

    @Test fun trailingTextAfterClosingBraceIsIgnored() {
        val json = notNull(extraction(" \"Fire\"} Note: nobody was hurt."))
        assertEquals(setOf("incident_type"), json.keys)
    }

    @Test fun unquotedNullIsJsonNull() {
        val json = notNull(extraction(" null, \"severity\": null, \"location\": \"lab\"}"))
        assertEquals(JsonNull, json["incident_type"])
        assertEquals("lab", json.str("location"))
    }

    @Test fun updateContinuations() {
        val json = notNull(LlmOutputParser.parseObject(fixture("update_location.txt"), Prompts.UPDATE_PREFIX))
        assertEquals("block B second floor near the lift", json.str("location"))
        val repeated = notNull(LlmOutputParser.parseObject("{\"location\": \"lab\"}", Prompts.UPDATE_PREFIX))
        assertEquals("lab", repeated.str("location"))
        val doubled = notNull(LlmOutputParser.parseObject("{ {\"location\": \"lab\"}}", Prompts.UPDATE_PREFIX))
        assertEquals("lab", doubled.str("location"))
    }

    @Test fun emptyUpdateIsAnEmptyObject() {
        val json = notNull(LlmOutputParser.parseObject(fixture("update_empty.txt"), Prompts.UPDATE_PREFIX))
        assertTrue(json.isEmpty())
        assertTrue(notNull(LlmOutputParser.parseObject("", Prompts.UPDATE_PREFIX)).isEmpty())
    }

    @Test fun nothingUsableIsNull() {
        assertNull(extraction("I cannot help with that."))
        assertNull(extraction(""))
        assertNull(extraction("<think>\nStill thinking about it"))
        assertNull(extraction(" \"Wat"))
        assertNull(LlmOutputParser.parseObject("Nothing to update.", Prompts.UPDATE_PREFIX))
    }

    @Test fun cleanTextStripsThinkAndTokens() {
        assertEquals("hello", LlmOutputParser.cleanText("<think>a\nb</think>\n\nhello<|im_end|>"))
        assertEquals("hello", LlmOutputParser.cleanText("\n\n</think>\n\nhello"))
        assertEquals("", LlmOutputParser.cleanText("<think>never closed"))
        assertEquals("hi", LlmOutputParser.cleanText("hi<|im_start|>user\nmore"))
        assertEquals("hi", LlmOutputParser.cleanText("hi<|endoftext|>"))
    }

    @Test fun cleanLineTakesFirstQuotedLine() {
        assertEquals("Was anyone hurt in the workshop?", LlmOutputParser.cleanLine(fixture("rephrase_quoted.txt")))
        assertEquals("Where did it happen?", LlmOutputParser.cleanLine("\n  \u201CWhere did it happen?\u201D  \nignored"))
        assertEquals("Ok?", LlmOutputParser.cleanLine("'Ok?'<|im_end|>"))
        assertNull(LlmOutputParser.cleanLine("<think>x</think>   "))
        assertNull(LlmOutputParser.cleanLine("\"\""))
    }
}
