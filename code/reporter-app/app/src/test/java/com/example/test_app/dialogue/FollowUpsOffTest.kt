package com.example.test_app.dialogue

import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.asTextOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User setting "Follow-up questions" OFF (and "Hands-free answers" OFF): the dialogue asks NOTHING (no safety check, no field question,
 * no opening), listens only for the narration, flags what it would have asked as `missing_flagged`, still raises the emergency flag
 * and the severity floors for URGENT and still speaks the read-back statement (no greeting is spoken at all, ADR-052).
 */
class FollowUpsOffTest {
    private class CountingIn(script: List<AsrResult?>) : SpeechIn {
        private val queue = ArrayDeque(script)
        var listens = 0
        override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult? {
            listens++
            return queue.removeFirstOrNull()
        }
    }

    private class Run(narration: String, vararg fields: Pair<String, Any?>, config: DialogueConfig = OFF_CONFIG) {
        val inp = CountingIn(listOf(said(narration)) + List(6) { said("an answer that must never be listened for") })
        val out = RecordingOut()
        val logger = DialogueLogger()
        val manager = DialogueManager(
            TestAssets.assets, inp, out, ScriptExtraction(listOf(reportJson("x", *fields))), config, logger = logger, clock = { TEST_CLOCK },
        )
        lateinit var report: IncidentReport
        suspend fun go(): Run {
            report = (manager.run() as DialogueOutcome.Review).report
            return this
        }

        val lines get() = manager.spoken
        fun count(k: SpokenKind) = lines.count { it.kind == k }
        fun status(key: String) = report.fields.getValue(key).effectiveStatus
    }

    private companion object {
        val OFF_CONFIG = DialogueConfig(useLlmRephrase = false, followUpsEnabled = false)
    }

    private fun Run.assertSilent() {
        assertEquals("no question", 0, count(SpokenKind.QUESTION))
        assertEquals("no safety check", 0, count(SpokenKind.SAFETY_CHECK))
        assertEquals("no opening", 0, count(SpokenKind.OPENING))
        assertEquals("only the narration is listened for", 1, inp.listens)
        assertEquals(emptyList<String>(), manager.askedFields)
        assertEquals(emptyList<String>(), report.dialogue.askedFields)
        assertEquals("no greeting is spoken any more (ADR-052)", 0, count(SpokenKind.GREETING))
        assertEquals("read-back statement still spoken once", 1, count(SpokenKind.READBACK))
        assertFalse(lines.single { it.kind == SpokenKind.READBACK }.text.contains('?'))
        assertEquals("no mic-tap wait either", false, manager.uiState.value.state == DialogueState.WAITING_FOR_ANSWER_TAP)
    }

    @Test fun s01_completeWaterLeak() = runTest {
        val r = Run(
            "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.",
            "incident_description" to "Water leak near Conference Room 2", "incident_type" to "Water Leak",
            "incident_location" to "Conference Room 2", "date_time" to "2026-10-11T10:00:00+05:30", "injury" to false,
            "immediate_actions_taken" to "Maintenance isolated the area.",
        ).go()
        r.assertSilent()
        assertTrue(r.report.fields.values.none { it.status == FieldStatus.MISSING_FLAGGED })
    }

    @Test fun s02_waterOnFloorFlagsTheMissingLocationAndThinDescription() = runTest {
        val r = Run("There's water all over the floor.", "incident_description" to "Water on the floor", "incident_type" to "Water Leak").go()
        r.assertSilent()
        assertEquals(FieldStatus.MISSING_FLAGGED, r.status("incident_location"))
        assertNull(r.report.fields.getValue("incident_location").value.asTextOrNull())
        assertEquals(FieldStatus.MISSING_FLAGGED, r.status("incident_description"))
    }

    @Test fun s03_slippedAndHurtFlagsTheInjuryType() = runTest {
        val r = Run(
            "I slipped in the pantry and I'm hurt.", "incident_description" to "Slipped in the pantry", "incident_type" to "Slip / Fall",
            "incident_location" to "Pantry", "injury" to true, "persons_involved" to listOf("Reporter (injured)"),
        ).go()
        r.assertSilent()
        assertEquals(DialogueMode.CARE, r.manager.currentMode)
        assertEquals(FieldStatus.MISSING_FLAGGED, r.status("injury_type"))
        assertFalse("no sympathy opening either", r.lines.any { it.kind == SpokenKind.OPENING })
    }

    @Test fun s04_negatedInjury() = runTest {
        val r = Run(
            "Nobody was hurt but the printer gave off a spark in room 4.", "incident_description" to "Printer sparked in room 4",
            "incident_type" to "Equipment Fault", "incident_location" to "Room 4", "injury" to false,
        ).go()
        r.assertSilent()
        assertEquals(DialogueMode.PLAIN, r.manager.currentMode)
    }

    @Test fun s05_smokeStillRaisesTheEmergencyAndTheSeverityFloor() = runTest {
        val r = Run(
            "There's smoke coming from the server room!", "incident_description" to "Smoke from the server room",
            "incident_type" to "Fire / Smoke", "incident_location" to "Server room", "severity_level" to "Medium",
        ).go()
        r.assertSilent()
        assertEquals(DialogueMode.URGENT, r.manager.currentMode)
        assertTrue("emergency banner flag", r.report.dialogue.emergency)
        assertTrue(r.manager.uiState.value.emergency)
        assertEquals("URGENT", r.report.dialogue.mode)
        assertTrue(r.report.fields.getValue("severity_level").value.asTextOrNull() in listOf("High", "Critical"))
        assertEquals("injury is optional: never flagged", FieldStatus.EMPTY, r.status("injury"))
        assertEquals("the question it would have asked is flagged", FieldStatus.MISSING_FLAGGED, r.status("immediate_actions_taken"))
        assertEquals("defer is a statement and is spoken once", 1, r.count(SpokenKind.DEFER))
        assertTrue(r.logger.lines.any { it.contains("skipped_follow_ups_off") })
    }

    @Test fun s05b_fireWithDistressCuesIsCriticalWithoutAnyQuestion() = runTest {
        val r = Run(
            "It's very urgent. There's a fire in front of me. Need help.", "incident_type" to "Fire", "incident_location" to "Vindhya A4",
            "incident_description" to "A fire", "severity_level" to "Medium",
        ).go()
        r.assertSilent()
        assertEquals("Critical", r.report.fields.getValue("severity_level").value.asTextOrNull())
        assertTrue(r.report.dialogue.emergency)
    }

    @Test fun s06_harassmentHasNoTypingOfferAndFlagsThePeopleOnly() = runTest {
        val r = Run(
            "Someone has been harassing me near the parking lot.", "incident_description" to "Harassment near the parking lot",
            "incident_type" to "Harassment", "incident_location" to "Parking lot",
        ).go()
        r.assertSilent()
        assertEquals(DialogueMode.CARE, r.manager.currentMode)
        assertEquals(FieldStatus.EMPTY, r.status("injury"))
        assertEquals(FieldStatus.MISSING_FLAGGED, r.status("persons_involved"))
        assertFalse(r.lines.any { it.text.contains("type if you'd prefer") })
    }

    @Test fun s07_thinDescriptionAndPastCueAreFlagged() = runTest {
        val r = Run(
            "Something happened earlier in lab 3, nobody was hurt.", "incident_description" to "Something happened earlier in lab 3",
            "incident_location" to "Lab 3", "injury" to false,
        ).go()
        r.assertSilent()
        assertEquals(FieldStatus.MISSING_FLAGGED, r.status("incident_description"))
        assertEquals(FieldStatus.MISSING_FLAGGED, r.status("date_time"))
    }

    @Test fun s08_accidentDoesNotFlagTheInjury() = runTest {
        val r = Run(
            "A ceiling panel dropped in block C.", "incident_description" to "Ceiling panel dropped in block C",
            "incident_type" to "Accident", "incident_location" to "Block C",
        ).go()
        r.assertSilent()
        assertEquals(FieldStatus.EMPTY, r.status("injury"))
    }

    @Test fun s09_somethingIsBrokenFlagsLocationAndDescription() = runTest {
        val r = Run("Something is broken.", "incident_description" to "Something is broken").go()
        r.assertSilent()
        assertEquals(FieldStatus.MISSING_FLAGGED, r.status("incident_location"))
        assertEquals(FieldStatus.MISSING_FLAGGED, r.status("incident_description"))
        assertTrue("the report is still produced for Review", r.report.fields.isNotEmpty())
    }

    @Test fun s10_doorHandleFlagsTheDescription() = runTest {
        val r = Run(
            "Someone pulled the door handle in block A and it broke.", "incident_description" to null,
            "incident_type" to "Equipment Damage", "incident_location" to "Block A",
        ).go()
        r.assertSilent()
        assertEquals(FieldStatus.EMPTY, r.status("injury"))
        assertEquals(FieldStatus.MISSING_FLAGGED, r.status("incident_description"))
        assertEquals(DialogueMode.PLAIN, r.manager.currentMode)
    }

    @Test fun theSameNarrationAsksQuestionsWhenFollowUpsAreOn() = runTest {
        val on = Run("Something is broken.", "incident_description" to "Something is broken", config = DialogueConfig(useLlmRephrase = false)).go()
        assertEquals(2, on.count(SpokenKind.QUESTION))
        assertTrue(on.inp.listens > 1)
    }

    // ---- hands-free answers ----

    private class Gate(narration: String, config: DialogueConfig, vararg fields: Pair<String, Any?>) {
        val inp = CountingIn(listOf(said(narration), said("block B")))
        val manager = DialogueManager(
            TestAssets.assets, inp, RecordingOut(),
            ScriptExtraction(listOf(reportJson("x", *fields), reportJson("x", "incident_location" to "Block B"))), config, clock = { TEST_CLOCK },
        )
    }

    private val handsOff = DialogueConfig(useLlmRephrase = false, handsFreeAnswers = false)

    private val brokenFields = arrayOf<Pair<String, Any?>>(
        "incident_description" to "A pipe burst near the lift", "incident_type" to "Water Leak",
    )

    @Test fun withHandsFreeOffTheMicWaitsForATapBeforeListening() = runTest {
        val g = Gate("There's water all over the floor and a pipe burst.", handsOff, *brokenFields)
        var outcome: DialogueOutcome? = null
        val job = launch { outcome = g.manager.run() }
        runCurrent()
        assertEquals(DialogueState.WAITING_FOR_ANSWER_TAP, g.manager.uiState.value.state)
        assertEquals("only the narration was listened for so far", 1, g.inp.listens)
        advanceTimeBy(5_000); runCurrent()
        assertEquals("still waiting", DialogueState.WAITING_FOR_ANSWER_TAP, g.manager.uiState.value.state)
        g.manager.onMicTap()
        runCurrent()
        job.join()
        assertEquals(2, g.inp.listens)
        val report = (outcome as DialogueOutcome.Review).report
        assertEquals("Block B", report.fields.getValue("incident_location").value.asTextOrNull())
        assertEquals(FieldStatus.FILLED, report.fields.getValue("incident_location").effectiveStatus)
    }

    @Test fun withHandsFreeOffNoTapIsNoResponseAndNothingIsRecorded() = runTest {
        val g = Gate("There's water all over the floor and a pipe burst.", handsOff, *brokenFields)
        var outcome: DialogueOutcome? = null
        val job = launch { outcome = g.manager.run() }
        runCurrent()
        advanceTimeBy(11_000); runCurrent()
        job.join()
        assertEquals("the microphone never started for the question", 1, g.inp.listens)
        val report = (outcome as DialogueOutcome.Review).report
        assertEquals(FieldStatus.NO_RESPONSE, report.fields.getValue("incident_location").effectiveStatus)
        assertEquals(listOf("location"), report.dialogue.askedFields)
    }

    @Test fun aTapDuringTheQuestionDoesNotCountAsTheAnswerTap() = runTest {
        val g = Gate("There's water all over the floor and a pipe burst.", handsOff, *brokenFields)
        g.manager.onMicTap() // an early tap (it skipped the spoken question): forgotten when the wait starts
        var outcome: DialogueOutcome? = null
        val job = launch { outcome = g.manager.run() }
        runCurrent()
        // the early tap completed the greeting hint (not used: narration came at once); the answer wait needs a fresh tap
        assertEquals(DialogueState.WAITING_FOR_ANSWER_TAP, g.manager.uiState.value.state)
        g.manager.onMicTap()
        runCurrent()
        job.join()
        assertEquals(2, g.inp.listens)
    }

    @Test fun withHandsFreeOnThereIsNoTapWait() = runTest {
        val g = Gate("There's water all over the floor and a pipe burst.", DialogueConfig(useLlmRephrase = false), *brokenFields)
        val seen = mutableSetOf<DialogueState>()
        val job = launch { g.manager.run() }
        runCurrent()
        job.join()
        seen += g.manager.uiState.value.state
        assertFalse(DialogueState.WAITING_FOR_ANSWER_TAP in seen)
        assertEquals(2, g.inp.listens)
    }

    @Test fun followUpsOffIgnoresTheHandsFreeSetting() = runTest {
        val r = Run("Something is broken.", "incident_description" to "Something is broken", config = DialogueConfig(useLlmRephrase = false, followUpsEnabled = false, handsFreeAnswers = false)).go()
        r.assertSilent()
    }
}
