package com.example.test_app.ai

import com.example.test_app.ai.AiTestSupport.NOW
import com.example.test_app.ai.AiTestSupport.expectThrows
import com.example.test_app.ai.AiTestSupport.fixture
import com.example.test_app.ai.AiTestSupport.notNull
import com.example.test_app.ai.AiTestSupport.request
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.service.ExtractionException
import com.example.test_app.domain.service.IncidentJsonCodec
import com.example.test_app.domain.service.ModelLoadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmIncidentExtractionServiceTest {
    private val transcript = "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area."

    private fun service(engine: FakeEngine) = LlmIncidentExtractionService(engine) { NOW }

    private fun extract(service: LlmIncidentExtractionService, request: com.example.test_app.domain.service.ExtractionRequest): IncidentReport =
        IncidentJsonCodec.decode(runBlocking { service.extract(request) })

    private fun firstPass(engine: FakeEngine = FakeEngine(reply = fixture("extract_water_leak_plain.txt"))): IncidentReport =
        extract(service(engine), request(transcript))

    private fun IncidentReport.text(key: String) = fields.getValue(key).value.asTextOrNull()

    @Test fun firstPassBuildsAFullReportAndSendsTheExtractionPrompt() {
        val engine = FakeEngine(reply = fixture("extract_water_leak_plain.txt"))
        val report = firstPass(engine)
        assertEquals("Water Leak", report.text("incident_type"))
        assertEquals("Conference Room 2", report.text("incident_location"))
        assertEquals(transcript, report.input.transcript)
        assertEquals(14, report.fields.size)
        assertEquals(1, engine.loads)
        val prompt = engine.prompts.single()
        assertTrue(prompt.startsWith("<|im_start|>system"))
        assertTrue(prompt.endsWith(Prompts.EXTRACTION_PREFIX))
        assertTrue(prompt.contains(transcript))
    }

    @Test fun firstPassToleratesQuirkyOutputs() {
        for (name in listOf("extract_repeated_brace.txt", "extract_think_block.txt", "extract_trailing_im_end.txt", "extract_truncated.txt", "extract_fence_and_chatter.txt")) {
            val report = firstPass(FakeEngine(reply = fixture(name)))
            assertTrue(name, report.text("incident_type") != null)
        }
    }

    @Test fun emergencyRuleRunsOnTheRealTranscript() {
        val engine = FakeEngine(reply = fixture("extract_water_leak_plain.txt"))
        val report = extract(service(engine), request("there is smoke near the printer"))
        assertEquals("High", report.text("severity_level"))
    }

    @Test fun loadFailureBecomesModelLoadException() {
        val e = expectThrows<ModelLoadException> {
            runBlocking { service(FakeEngine(loadError = IllegalStateException("no model"))).extract(request(transcript)) }
        }
        assertTrue(e.cause is IllegalStateException)
        expectThrows<ModelLoadException> {
            runBlocking { service(FakeEngine(loadError = UnsatisfiedLinkError("libgenie.so"))).extract(request(transcript)) }
        }
    }

    @Test fun unreadableFirstPassIsAnExtractionException() {
        for (reply in listOf("I cannot help with that.", "", "<think>hmm", "}")) {
            val e = expectThrows<ExtractionException> { runBlocking { service(FakeEngine(reply = reply)).extract(request(transcript)) } }
            assertFalse(e is ModelLoadException)
            assertEquals("The assistant returned something I couldn't read.", e.message)
        }
    }

    @Test fun otherEngineFailuresAreFriendly() {
        val e = expectThrows<ExtractionException> {
            runBlocking { service(FakeEngine(generateError = RuntimeException("npu died"))).extract(request(transcript)) }
        }
        assertEquals("The assistant couldn't process that.", e.message)
        assertTrue(e.cause is RuntimeException)
    }

    @Test fun cancellationIsNotWrapped() {
        val caught = runBlocking {
            try {
                service(FakeEngine(generateError = CancellationException("stop"))).extract(request(transcript))
                null
            } catch (e: CancellationException) {
                e
            }
        }
        notNull(caught)
    }

    @Test fun updateUsesTheCompactUpdatePromptAndMergesTheAnswer() {
        val previous = firstPass()
        val engine = FakeEngine(reply = fixture("update_location.txt"))
        val answer = "uh it's in block B second floor near the lift"
        val after = extract(service(engine), request(transcript, previous, answer, "location"))
        assertEquals("block B second floor near the lift", after.text("incident_location"))
        assertEquals(FieldSource.USER_VOICE, after.fields.getValue("incident_location").source)
        assertEquals("Water Leak", after.text("incident_type"))
        assertEquals(previous.reportId, after.reportId)
        val prompt = engine.prompts.single()
        assertTrue(prompt.endsWith(Prompts.UPDATE_PREFIX))
        assertTrue(prompt.contains("ASKED: location\nUSER: $answer<|im_end|>"))
        assertTrue(prompt.contains("CURRENT_FORM: {\"incident_type\": \"Water Leak\", \"location\": \"Conference Room 2\""))
        assertFalse("conversation must not be resent", prompt.contains(transcript))
    }

    @Test fun timeAnswerIsParsedToIso() {
        val previous = firstPass()
        val engine = FakeEngine(reply = "\"datetime\": \"5 minutes ago\"}")
        val after = extract(service(engine), request(transcript, previous, "five minutes ago", "time"))
        assertEquals("2026-10-11T13:55:00+05:30", after.text("date_time"))
        assertTrue(engine.prompts.single().contains("ASKED: datetime"))
    }

    @Test fun deterministicDialogueFieldsDoNotCallTheModel() {
        val previous = firstPass()
        val engine = FakeEngine(reply = "should not be used")
        val svc = service(engine)
        val who = extract(svc, request(transcript, previous, "me", "injured_person"))
        assertEquals("Reporter (injured)", who.fields.getValue("persons_involved").value.toString().trim('[', ']', '"'))
        val type = extract(svc, request(transcript, who, "it's a deep cut", "injury_type"))
        assertEquals("Deep cut", type.text("injury_type"))
        assertTrue(engine.prompts.isEmpty())
    }

    @Test fun unreadableUpdateLeavesTheReportUnchanged() {
        val previous = firstPass()
        for (reply in listOf("Nothing to update.", "", "\"location\": \"bl")) {
            val after = extract(service(FakeEngine(reply = reply)), request(transcript, previous, "block B", "location"))
            assertEquals(reply, previous.fields, after.fields)
        }
    }

    @Test fun emptyUpdateObjectLeavesTheReportUnchanged() {
        val previous = firstPass()
        val after = extract(service(FakeEngine(reply = fixture("update_empty.txt"))), request(transcript, previous, "block B", "location"))
        assertEquals(previous.fields, after.fields)
    }

    @Test fun freeCorrectionUsesTheModelWithAskedNone() {
        val previous = firstPass()
        val engine = FakeEngine(reply = "\"location\": \"room 3\"}")
        val after = extract(service(engine), request(transcript, previous, "actually it was room 3", null))
        assertEquals("room 3", after.text("incident_location"))
        assertTrue(engine.prompts.single().contains("ASKED: none"))
    }

    @Test fun blankAnswerChangesNothingAndSkipsTheModel() {
        val previous = firstPass()
        val engine = FakeEngine()
        val after = extract(service(engine), request(transcript, previous, "  ", "location"))
        assertEquals(previous.fields, after.fields)
        assertTrue(engine.prompts.isEmpty())
    }

    @Test fun metricsOfTheLastCallAreExposed() {
        val svc = service(FakeEngine(reply = fixture("extract_water_leak_plain.txt")))
        assertNull(svc.lastCallMetrics())
        runBlocking { svc.extract(request(transcript)) }
        val m = notNull(svc.lastCallMetrics())
        assertNull(m.promptTokens)
        assertEquals(120L, m.prefillMs)
        assertEquals(340L, m.decodeMs)
        assertEquals(Prompts.extraction(transcript).render(ChatFormat.CHATML).length, svc.lastPromptChars)
    }
}
