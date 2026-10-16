package com.example.test_app.dialogue

import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.asTextOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STRICT, absolute cap (ADR-048): at most `DialogueConfig.maxQuestionsTotal` (2) questions per report in total in every mode.
 * A question is every spoken prompt that expects an answer: the field questions and the URGENT safety check. The read-back is a
 * statement (no question, no listening), like the greeting, opening, defer and closing lines. Per-mode caps apply when lower.
 */
class QuestionCapTest {
    private class Run(
        speech: List<AsrResult?>,
        extractions: List<String>,
        val config: DialogueConfig = DialogueConfig(useLlmRephrase = false),
    ) {
        val logger = DialogueLogger()
        val out = RecordingOut()
        val ext = ScriptExtraction(extractions)
        val manager = DialogueManager(TestAssets.assets, ScriptIn(speech), out, ext, config, logger = logger, clock = { TEST_CLOCK })

        suspend fun review(): IncidentReport = (manager.run() as DialogueOutcome.Review).report
        val lines get() = manager.spoken
        fun count(k: SpokenKind) = lines.count { it.kind == k }
        val questions get() = lines.filter { it.kind == SpokenKind.QUESTION }
        val totalQuestions get() = count(SpokenKind.QUESTION) + count(SpokenKind.SAFETY_CHECK)
    }

    private fun IncidentReport.status(key: String) = fields.getValue(key).effectiveStatus
    private fun IncidentReport.text(key: String) = fields[key]?.value?.asTextOrNull()

    @Test fun theDefaultTotalIsTwo() = assertEquals(2, DialogueConfig().maxQuestionsTotal)

    @Test fun plainAsksTwoAndNeverMore() = runTest {
        val r = Run(listOf(said("Something is broken."), null, null, null), listOf(reportJson("x", "incident_description" to "Something is broken")))
        val report = r.review()
        assertEquals(DialogueMode.PLAIN, r.manager.currentMode)
        assertEquals(2, r.totalQuestions)
        assertEquals(listOf("location", "description"), r.manager.askedFields)
        assertEquals(report.dialogue.askedFields, r.manager.askedFields)
    }

    @Test fun careAsksTwoNotThree() = runTest {
        // "I slipped and I'm hurt ... earlier": CARE; location missing, thin description and a past cue are all wanted
        val r = Run(
            listOf(said("I slipped and I'm hurt, it was earlier."), null, null, null),
            listOf(reportJson("x", "injury" to true, "incident_description" to null)),
        )
        val report = r.review()
        assertEquals(DialogueMode.CARE, r.manager.currentMode)
        assertEquals(2, r.totalQuestions)
        assertEquals(listOf("location", "description"), r.manager.askedFields)
        assertEquals(1, r.count(SpokenKind.READBACK))
        assertEquals("the past cue was still wanted: flagged, not asked", FieldStatus.MISSING_FLAGGED, report.status("date_time"))
    }

    @Test fun urgentIsTheSafetyCheckPlusOneField() = runTest {
        val r = Run(
            listOf(said("There's smoke coming from the server room!"), null, null, null, null),
            listOf(reportJson("x", "incident_description" to null)),
        )
        val report = r.review()
        assertEquals(DialogueMode.URGENT, r.manager.currentMode)
        assertEquals(1, r.count(SpokenKind.SAFETY_CHECK))
        assertEquals(1, r.questions.size)
        assertEquals("location is the top question when missing", "location", r.questions.single().field)
        assertEquals(2, r.totalQuestions)
        assertTrue(report.dialogue.emergency)
        assertEquals("still wanted after the cap: flagged", FieldStatus.MISSING_FLAGGED, report.status("incident_description"))
        assertEquals("defer line is a statement", 1, r.count(SpokenKind.DEFER))
    }

    @Test fun escalatingToUrgentSpendsTheSecondQuestionOnTheSafetyCheck() = runTest {
        // PLAIN: the thin description is asked (1). Its answer reveals smoke -> URGENT: safety check (2), nothing more.
        val r = Run(
            listOf(said("Something broke in lab 3."), said("no but there is smoke coming out of it"), null, null),
            listOf(
                reportJson("x", "incident_description" to "Something broke in lab 3", "incident_location" to "Lab 3", "incident_type" to "Equipment Failure"),
                reportJson("x", "incident_description" to "Smoke coming out of the broken equipment"),
            ),
        )
        val report = r.review()
        assertEquals(DialogueMode.URGENT, r.manager.currentMode)
        assertEquals(1, r.count(SpokenKind.SAFETY_CHECK))
        assertEquals(2, r.totalQuestions)
        assertEquals("description", r.questions.first().field)
        assertTrue(report.dialogue.emergency)
        assertTrue(r.lines.indexOfFirst { it.kind == SpokenKind.SAFETY_CHECK } > r.lines.indexOfFirst { it.kind == SpokenKind.QUESTION })
    }

    @Test fun safetyCheckIsSkippedButTheEmergencyFlagIsSetWhenTheTotalIsAlreadyUsed() = runTest {
        // PLAIN uses both questions; the last answer reveals smoke: URGENT after the cap -> no third question
        val r = Run(
            listOf(said("Something is broken."), said("in the server room"), said("there is smoke coming out of it")),
            listOf(
                reportJson("x", "incident_description" to "Something is broken"),
                reportJson("x", "incident_location" to "Server room"),
                reportJson("x", "incident_description" to "Smoke is coming out of broken equipment"),
            ),
        )
        val report = r.review()
        assertEquals(2, r.questions.size)
        assertEquals("no 3rd question: the safety check is skipped (hard stop)", 0, r.count(SpokenKind.SAFETY_CHECK))
        assertEquals(2, r.totalQuestions)
        assertEquals(DialogueMode.URGENT, r.manager.currentMode)
        assertTrue("the banner flag is still raised", report.dialogue.emergency)
        assertTrue(report.text("severity_level") in listOf("High", "Critical"))
        assertTrue(r.logger.lines.any { it.contains("skipped_question_cap") })
        assertFalse(r.lines.any { it.kind == SpokenKind.SAFETY_CHECK })
    }

    @Test fun aSmallerConfiguredTotalIsHonoured() = runTest {
        val one = Run(
            listOf(said("There's smoke in the lab"), null, null),
            listOf(reportJson("x", "incident_description" to null)),
            DialogueConfig(useLlmRephrase = false, maxQuestionsTotal = 1),
        )
        one.review()
        assertEquals("only the safety check fits", 1, one.totalQuestions)
        assertEquals(0, one.questions.size)

        val plain = Run(
            listOf(said("Something is broken."), null, null),
            listOf(reportJson("x", "incident_description" to "Something is broken")),
            DialogueConfig(useLlmRephrase = false, maxQuestionsTotal = 1),
        )
        plain.review()
        assertEquals(listOf("location"), plain.manager.askedFields)
        assertEquals("opening line promises one detail", "Got it. Just one quick detail.", plain.lines.first { it.kind == SpokenKind.OPENING }.text)
    }

    @Test fun aLargerTotalNeverRaisesThePerModeCaps() = runTest {
        val r = Run(
            listOf(said("Something is broken. It happened earlier."), null, null, null, null),
            listOf(reportJson("x", "incident_description" to "Something is broken")),
            DialogueConfig(useLlmRephrase = false, maxQuestionsTotal = 10),
        )
        r.review()
        assertEquals("PLAIN cap 2 is lower", 2, r.totalQuestions)
    }

    @Test fun readbackIsAStatementThatIsNotCountedAndNotListenedFor() = runTest {
        val r = Run(listOf(said("There's smoke coming from the server room!"), null, null, said("actually the lab")), listOf(reportJson("x")))
        val report = r.review()
        r.manager.close()
        val readback = r.lines.single { it.kind == SpokenKind.READBACK }
        assertFalse("no '?' in the read-back", readback.text.contains('?'))
        assertEquals("the read-back is the last spoken line of the dialogue", readback, r.lines.dropLast(0).last { it.kind != SpokenKind.CLOSING })
        assertEquals(1, r.count(SpokenKind.CLOSING))
        assertEquals(2, r.totalQuestions)
        assertEquals("the late reply was never consumed", null, report.text("incident_location")?.takeIf { it.contains("lab", ignoreCase = true) })
    }
}
