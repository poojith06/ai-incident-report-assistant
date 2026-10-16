package com.example.test_app.mock

import com.example.test_app.dialogue.AsrResult
import com.example.test_app.dialogue.DialogueAssets
import com.example.test_app.dialogue.DialogueManager
import com.example.test_app.dialogue.DialogueOutcome
import com.example.test_app.dialogue.DialogueMode
import com.example.test_app.dialogue.FieldAwareSpeechIn
import com.example.test_app.dialogue.SpeechOut
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.service.AudioInput
import com.example.test_app.domain.service.SpeechToTextException
import com.example.test_app.domain.service.SttHint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Drives the mock services (STT + extraction) through the real `DialogueManager`, the way the app does in Mock AI mode. */
class ScenarioLoopTest {
    private val audio = AudioInput(File("x.wav"), 2000)
    private val assets = DialogueAssets.fromDirectory(
        listOf("src/main/assets/dialogue", "app/src/main/assets/dialogue").map { File(it) }.first { it.isDirectory },
    )

    /** Like `MicSpeechIn`, but the "recognised" text comes from the mock STT scripts (the audio is ignored). */
    private class MockMicIn(private val stt: MockSpeechToText) : FieldAwareSpeechIn {
        private var expected: String? = null
        override fun expectAnswerFor(field: String?) {
            expected = field
        }

        override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult =
            AsrResult(stt.transcribe(AudioInput(File("x.wav"), 2000), SttHint(expected)).text, 1f, "en")
    }

    private class SilentOut : SpeechOut {
        val lines = mutableListOf<String>()
        override suspend fun speak(text: String, language: String) {
            lines += text
        }
    }

    private class Result(val asked: List<String>, val report: IncidentReport, val mode: DialogueMode, val spoken: List<String>)

    private suspend fun play(scenario: MockScenario): Result {
        val settings = DevSettingsStore().apply { update { it.copy(scenario = scenario) } }
        val out = SilentOut()
        val manager = DialogueManager(assets, MockMicIn(MockSpeechToText(settings)), out, MockIncidentExtraction(settings))
        val review = manager.run() as DialogueOutcome.Review
        return Result(manager.askedFields, review.report, manager.currentMode, out.lines)
    }

    private fun Result.assertAskOnce() {
        assertEquals(asked.size, asked.toSet().size)
        assertTrue(spoken.size == spoken.toSet().size)
        assertEquals(asked, report.dialogue.askedFields)
    }

    @Test fun completeAsksNothingAndSuggestsLowSeverity() = runTest {
        val r = play(MockScenario.COMPLETE)
        assertTrue(r.asked.isEmpty())
        assertEquals(DialogueMode.PLAIN, r.mode)
        assertEquals("Low", r.report.fields["severity_level"]!!.value.toString().trim('"'))
        assertTrue("no greeting is spoken (ADR-052)", r.spoken.none { it.contains("describe your incident") })
        r.assertAskOnce()
    }

    @Test fun injuryFollowUpsAskActionsThenWhoWasHurt() = runTest {
        val r = play(MockScenario.INJURY_FOLLOW_UPS)
        // ADR-050: injury is never asked; the narration says "hurt his ankle" (injury true, nobody named) so the CARE follow-ups run
        assertEquals(listOf("immediate_actions", "injured_person"), r.asked)
        assertEquals(DialogueMode.CARE, r.mode)
        assertEquals("true", r.report.fields["injury"]!!.value.toString())
        assertEquals("High", r.report.fields["severity_level"]!!.value.toString().trim('"'))
        r.assertAskOnce()
    }

    @Test fun vagueLocationNeverAsksInjuryAndDefaultsTime() = runTest {
        val r = play(MockScenario.VAGUE_LOCATION)
        assertFalse("injury" in r.asked)
        assertTrue(r.asked.size <= 3)
        assertEquals("default_now", r.report.timeSource)
        assertTrue(r.report.fields["date_time"]!!.value.toString().length > 10)
        r.assertAskOnce()
    }

    @Test fun sttFailureHappensOnceThenRecovers() = runTest {
        val settings = DevSettingsStore().apply { update { it.copy(scenario = MockScenario.STT_FAILURE) } }
        val stt = MockSpeechToText(settings)
        try { stt.transcribe(audio); fail("expected failure") } catch (_: SpeechToTextException) {}
        assertTrue(stt.transcribe(audio).text.isNotBlank())
    }

    @Test fun typedTranscriptReplacesTheScriptsLineByLineThenFallsBack() = runTest {
        val settings = DevSettingsStore().apply {
            update { it.copy(typedTranscript = "first line\n\n second line ") }
        }
        val stt = MockSpeechToText(settings)
        assertEquals("first line", stt.transcribe(audio).text)
        assertEquals("second line", stt.transcribe(audio, SttHint("location")).text)
        assertTrue(stt.transcribe(audio, SttHint("location")).text.contains("main building")) // script again
        // safety check and read-back replies never consume typed lines
        settings.update { it.copy(typedTranscript = "typed") }
        assertEquals("Yes, I am safe.", stt.transcribe(audio, SttHint("safety")).text)
        assertEquals("typed", stt.transcribe(audio).text)
    }
}
