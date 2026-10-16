package com.example.test_app.ai

import com.example.test_app.ai.AiTestSupport.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Real Qwen3-4B outputs captured on the QIDK by GenieLlmBenchmarkTest (see doc/TESTING.md). */
class CapturedOutputsTest {
    private val sevenKeys = setOf("incident_type", "severity", "location", "datetime", "persons_involved", "actions_taken", "description")

    @Test fun everyCapturedExtractionOutputParsesToAllKeys() {
        for (i in 1..8) {
            val name = "captured_qwen_extract_%02d.txt".format(i)
            val json = LlmOutputParser.parseObject(fixture(name), Prompts.EXTRACTION_PREFIX)
            assertNotNull("$name did not parse", json)
            assertEquals(name, sevenKeys, json!!.keys)
        }
    }
}
