package com.example.test_app.ai

import com.example.test_app.ai.AiTestSupport.NOW
import com.example.test_app.ai.AiTestSupport.fixture
import com.example.test_app.ai.AiTestSupport.notNull
import com.example.test_app.ai.AiTestSupport.request
import com.example.test_app.dialogue.DialogueMode
import com.example.test_app.dialogue.RephraseRequest
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.ImageHint
import com.example.test_app.domain.service.IncidentJsonCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Gemma backend adds to the shared extraction service: photo hints, token budgets, the one-call JSON. */
class LlmServiceOptionsTest {
    private val reply = """ "Water Leak", "severity": "Medium", "location": "lab", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "A pipe leaks."}"""

    @Test fun photoHintsGoIntoTheFirstPassPrompt() = runTest {
        val engine = FakeEngine(reply = reply)
        val hints = listOf(ImageHint("m1", listOf("pipe"), "A pipe leaks onto the floor."))
        LlmIncidentExtractionService(engine) { NOW }.extract(request("pipe leaking in the lab").copy(imageHints = hints))
        assertTrue(engine.prompts.single().contains("\nPHOTO: A pipe leaks onto the floor."))
    }

    @Test fun tokenBudgetsPerCall() = runTest {
        val engine = FakeEngine(reply = reply)
        val svc = LlmIncidentExtractionService(engine) { NOW }
        val first = IncidentJsonCodec.decode(svc.extract(request("pipe leaking in the lab")))
        assertEquals(400, engine.maxTokens.single())
        svc.extract(request("pipe leaking in the lab", previous = first, answer = "block b", field = "location"))
        assertEquals(listOf(400, 200), engine.maxTokens)
        LlmRephraser(engine).rephrase(RephraseRequest("location", DialogueMode.PLAIN, "Where did it happen?", "pipe", "en"))
        assertEquals(48, engine.maxTokens.last())
    }

    @Test fun precomputedJsonReplacesTheFirstPassCallOnce() = runTest {
        val engine = FakeEngine(reply = reply)
        val json = notNull(LlmOutputParser.parseObject(fixture("gemma_multimodal_audio_photo.txt"), Prompts.MULTIMODAL_PREFIX))
        var taken = 0
        val svc = LlmIncidentExtractionService(engine, { transcript ->
            taken++
            if (transcript.startsWith("a pipe is leaking")) json else null
        }) { NOW }
        val report = IncidentJsonCodec.decode(svc.extract(request("a pipe is leaking in front of me, I am in KRB third floor in front of CVIT.")))
        assertTrue("no LLM call for the first pass", engine.prompts.isEmpty())
        assertEquals("Water Leak", report.fields["incident_type"]?.value?.asTextOrNull())
        assertEquals("KRB third floor in front of CVIT", report.fields["incident_location"]?.value?.asTextOrNull())
        // another transcript is not covered: the normal prompt runs
        svc.extract(request("something else"))
        assertEquals(1, engine.prompts.size)
        assertEquals(2, taken)
    }

    @Test fun requestKeepsImageHintsFieldInTheRequestType() {
        val r: ExtractionRequest = request("x")
        assertTrue(r.imageHints.isEmpty())
        assertNull(r.previousReport)
    }
}
