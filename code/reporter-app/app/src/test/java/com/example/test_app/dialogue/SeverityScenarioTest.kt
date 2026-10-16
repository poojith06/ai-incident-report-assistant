package com.example.test_app.dialogue

import com.example.test_app.ai.FakeEngine
import com.example.test_app.ai.LlmIncidentExtractionService
import com.example.test_app.ai.Prompts
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.asBooleanOrNull
import com.example.test_app.domain.model.asStringList
import com.example.test_app.domain.model.asTextOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real classifier + severity policy + mapper + dialogue, fed with scripted model JSON shaped like the Qwen output
 * from the device sessions (type Fire, severity Medium). The LLM severity is only a hint.
 */
class SeverityScenarioTest {
    /** Extraction reply (continues after the forced `{"incident_type":` prefix); update replies are empty objects. */
    private class Run(narration: String, extractionReply: String, answers: List<AsrResult?> = emptyList()) {
        val engine = FakeEngine(replyFor = { prompt ->
            if (prompt.endsWith(Prompts.EXTRACTION_PREFIX)) extractionReply else "}"
        })
        val out = RecordingOut()
        val manager = DialogueManager(
            TestAssets.assets, ScriptIn(listOf(said(narration)) + answers), out, LlmIncidentExtractionService(engine) { TEST_CLOCK },
            DialogueConfig(useLlmRephrase = false), clock = { TEST_CLOCK },
        )

        suspend fun review(): IncidentReport = (manager.run() as DialogueOutcome.Review).report
        val lines get() = manager.spoken
        fun count(k: SpokenKind) = lines.count { it.kind == k }
        val totalQuestions get() = count(SpokenKind.QUESTION) + count(SpokenKind.SAFETY_CHECK)
    }

    private fun IncidentReport.text(key: String) = fields[key]?.value?.asTextOrNull()

    // A) "There's a fire in the inner AC in KRBCVIT lab."  (model: Fire / Medium)
    private val fireReply = """ "Fire", "severity": "Medium", "location": "KRBCVIT lab", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "Fire in the inner AC in the KRBCVIT lab."}"""

    @Test fun fireInTheLabIsUrgentHighAndNoInjuryOutcomeIsInvented() = runTest {
        val r = Run("There's a fire in the inner AC in KRBCVIT lab.", fireReply)
        val report = r.review()
        assertEquals(DialogueMode.URGENT, r.manager.currentMode)
        assertEquals("model said Medium, the policy floor is High", "High", report.text("severity_level"))
        assertTrue("emergency banner flag", report.dialogue.emergency)
        assertEquals("URGENT", report.dialogue.mode)
        assertNull("no bogus default outcome", report.text("injury_outcome"))
        assertNull(report.fields["injury"]?.value?.asBooleanOrNull())
        assertEquals("Fire", report.text("incident_type"))
        assertEquals(1, r.count(SpokenKind.SAFETY_CHECK))
        assertTrue("total questions incl. safety check <= 2: ${r.totalQuestions}", r.totalQuestions <= 2)
        assertFalse("injury is never asked (ADR-050)", "injury" in r.manager.askedFields)
        assertTrue("injury stays empty and is not flagged", report.fields["injury"]?.status.let { it == null || it == "empty" })
    }

    // A) "It's very urgent. There's a whole cod fire in front of me. I'm in front of Vindya A4. Need help."
    private val urgentReply = """ "Fire", "severity": "Medium", "location": "Vindya A4", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "A big fire in front of the reporter at Vindya A4."}"""

    @Test fun veryUrgentFireWithDistressCuesIsCritical() = runTest {
        val r = Run("It's very urgent. There's a whole cod fire in front of me. I'm in front of Vindya A4. Need help.", urgentReply)
        val report = r.review()
        assertEquals(DialogueMode.URGENT, r.manager.currentMode)
        assertEquals("Critical", report.text("severity_level"))
        assertTrue(report.dialogue.emergency)
        assertNull(report.text("injury_outcome"))
        assertNull(report.fields["injury"]?.value?.asBooleanOrNull())
        assertTrue(r.totalQuestions <= 2)
    }

    // "In the football ground, some guy fell and he is seriously bleeding."  (model: Injury / High)
    private val bleedingReply = """ "Injury", "severity": "High", "location": "football ground", "datetime": null, "persons_involved": "some guy", "actions_taken": null, "description": "A man fell on the football ground and is seriously bleeding."}"""

    @Test fun seriouslyBleedingPersonIsUrgentAndCritical() = runTest {
        val r = Run("In the football ground, some guy fell and he is seriously bleeding.", bleedingReply)
        val report = r.review()
        assertEquals(DialogueMode.URGENT, r.manager.currentMode)
        assertEquals("Critical", report.text("severity_level"))
        assertTrue(report.dialogue.emergency)
        assertEquals(true, report.fields["injury"]!!.value.asBooleanOrNull())
        assertEquals(listOf("some guy"), report.fields["persons_involved"]!!.value.asStringList())
        assertTrue(r.totalQuestions <= 2)
    }

    // ---- the cues are guarded and the asset really drives it ----

    @Test fun notUrgentWaterLeakStaysPlainAndNotCritical() = runTest {
        val reply = """ "Water Leak", "severity": "Medium", "location": "pantry", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "Small water leak in the pantry."}"""
        val r = Run("It's not urgent, there is a small water leak in the pantry and I don't need help.", reply)
        val report = r.review()
        assertEquals(DialogueMode.PLAIN, r.manager.currentMode)
        assertEquals("Medium", report.text("severity_level"))
        assertFalse(report.dialogue.emergency)
        assertEquals(0, r.count(SpokenKind.SAFETY_CHECK))
    }

    @Test fun distressInALaterAnswerRaisesFireFromHighToCritical() = runTest {
        // safety check unanswered, then the immediate-actions question is answered with "Need help, please hurry": a distress cue
        val r = Run("There's a fire in the inner AC in KRBCVIT lab.", fireReply, answers = listOf(null, said("Need help, please hurry")))
        val report = r.review()
        assertEquals("Critical", report.text("severity_level"))
        assertEquals(listOf("immediate_actions"), r.manager.askedFields)
        assertNull("no injury invented", report.text("injury_outcome"))
        assertNull(report.fields["injury"]!!.value.asBooleanOrNull())
    }

    @Test fun anAnswerMentioningInjuryDoesNotCreateAnInjuryQuestionOrValue() = runTest {
        val r = Run("There's a fire in the inner AC in KRBCVIT lab.", fireReply, answers = listOf(null, said("Yes, one person is hurt")))
        val report = r.review()
        assertFalse("injury" in r.manager.askedFields)
        assertTrue(r.totalQuestions <= 2)
        assertNull(report.fields["injury"]!!.value.asBooleanOrNull())
        assertTrue(report.text("severity_level") in listOf("High", "Critical"))
    }

    @Test fun severityIsNeverBelowHighOnceUrgentEvenWhenNobodyAnswers() = runTest {
        // D: the user never answers anything after the narration; Review must still show the banner flag and High/Critical
        val r = Run("There's smoke coming from the server room!", """ "Fire", "severity": "Low", "location": "server room", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "Smoke from the server room."}""")
        val report = r.review()
        assertTrue(report.dialogue.emergency)
        assertTrue(report.text("severity_level") in listOf("High", "Critical"))
        assertEquals("defer line is still spoken once", 1, r.count(SpokenKind.DEFER))
        assertEquals(1, r.count(SpokenKind.READBACK))
    }
}
