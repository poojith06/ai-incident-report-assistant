package com.example.test_app.dialogue

import com.example.test_app.domain.model.IncidentReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-048 relevance policy (ADR-050: injury is never asked) on realistic transcripts: the real classifier, planner, form reader and dialogue with scripted
 * extraction JSON (what the model returns for each narration) and a user who stays silent. Asserts the EXACT asked list and
 * that no report ever gets more than 2 questions (safety check included).
 */
class RelevancePolicyTest {
    private class Case(
        val name: String,
        val narration: String,
        val fields: Array<Pair<String, Any?>>,
        val asked: List<String>,
        val safety: Boolean = false,
        val mode: DialogueMode? = null,
    )

    private fun f(vararg p: Pair<String, Any?>) = arrayOf(*p)

    private val cases = listOf(
        Case(
            "water leak, complete", "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.",
            f("incident_type" to "Water Leak", "incident_location" to "Conference Room 2", "date_time" to "2026-10-11T10:00:00+05:30",
                "incident_description" to "Water leak near Conference Room 2", "immediate_actions_taken" to "Area isolated"),
            asked = emptyList(), mode = DialogueMode.PLAIN,
        ),
        Case(
            "fire with 'need help'", "There's a fire in the canteen kitchen, please, need help",
            f("incident_type" to "Fire", "incident_location" to "Canteen kitchen", "incident_description" to "Fire in the canteen kitchen"),
            asked = listOf("immediate_actions"), safety = true, mode = DialogueMode.URGENT,
        ),
        Case(
            "slip with ankle", "I slipped in the corridor and twisted my ankle badly",
            f("incident_type" to "Slip / Fall", "incident_location" to "Corridor", "incident_description" to "Slipped and twisted an ankle",
                "injury" to true, "persons_involved" to listOf("Reporter (injured)")),
            asked = listOf("injury_type"), mode = DialogueMode.CARE,
        ),
        Case(
            "slip, nobody named", "A worker fell in the workshop and hurt his leg",
            f("incident_type" to "Injury", "incident_location" to "Workshop", "incident_description" to "A worker fell and hurt his leg", "injury" to true),
            asked = listOf("injured_person", "injury_type"), mode = DialogueMode.CARE,
        ),
        Case(
            "harassment", "Someone has been harassing me near the parking lot.",
            f("incident_type" to "Harassment", "incident_location" to "Parking lot", "incident_description" to "Harassment near the parking lot"),
            asked = listOf("people_involved"), mode = DialogueMode.CARE,
        ),
        Case(
            "threat, no location", "Someone threatened me and I feel scared",
            f("incident_type" to "Security", "incident_description" to "Someone is threatening the reporter"),
            asked = listOf("location", "people_involved"), mode = DialogueMode.CARE,
        ),
        Case(
            "thin: something is broken", "Something is broken.",
            f("incident_description" to "Something is broken"),
            asked = listOf("location", "description"), mode = DialogueMode.PLAIN,
        ),
        Case(
            "Hindi fire", "लैब में आग लग गई है",
            f("incident_type" to "Fire", "incident_location" to "Lab", "incident_description" to "Fire in the lab"),
            asked = listOf("immediate_actions"), safety = true, mode = DialogueMode.URGENT,
        ),
        Case(
            "Hindi water leak (device log)", "के आर बी के वॉशरूम में पानी लीक हो रहा है और यह थर्ड फ्लोर में हो रहा है",
            f("incident_type" to "Water Leak", "incident_location" to "KRB washroom, third floor", "incident_description" to "Water is leaking in the KRB washroom on the third floor"),
            asked = emptyList(), mode = DialogueMode.PLAIN,
        ),
        Case(
            "snake sighting", "I saw a snake in front of the Kalamba block ten minutes ago",
            f("incident_type" to "Other", "incident_location" to "Kalamba block", "date_time" to "2026-10-11T09:20:00+05:30",
                "incident_description" to "A snake was seen in front of the Kalamba block"),
            asked = emptyList(), mode = DialogueMode.PLAIN,
        ),
        Case(
            "power cut", "The power is out in block C since this morning",
            f("incident_type" to "Other", "incident_location" to "Block C", "date_time" to "2026-10-11T09:00:00+05:30",
                "incident_description" to "Power cut in block C"),
            asked = emptyList(), mode = DialogueMode.PLAIN,
        ),
        Case(
            "gas smell", "I can smell gas in the canteen near the cooking area",
            f("incident_type" to "Gas Leak", "incident_location" to "Canteen", "incident_description" to "Smell of gas in the canteen"),
            asked = listOf("immediate_actions"), safety = true, mode = DialogueMode.URGENT,
        ),
        Case(
            "electrical spark, unknown injury", "There was a spark from the socket in room 4 and the wiring looks burnt",
            f("incident_type" to "Electrical Hazard", "incident_location" to "Room 4", "incident_description" to "Spark from a socket, burnt wiring"),
            asked = listOf("immediate_actions"),
        ),
        Case(
            "accident, ceiling panel", "A ceiling panel dropped in block C.",
            f("incident_type" to "Accident", "incident_location" to "Block C", "incident_description" to "Ceiling panel dropped in block C"),
            asked = emptyList(), mode = DialogueMode.PLAIN,
        ),
        Case(
            "AC leaking (facility)", "The AC in block C is leaking water onto the corridor floor",
            f("incident_type" to "Water Leak", "incident_location" to "Block C", "incident_description" to "AC leaking in block C"),
            asked = emptyList(), mode = DialogueMode.PLAIN,
        ),
        Case(
            "printer broken, vague place", "The printer is broken near the office",
            f("incident_type" to "Equipment Failure", "incident_location" to "near the office", "incident_description" to "Printer broken"),
            asked = listOf("location", "description"), mode = DialogueMode.PLAIN,
        ),
        Case(
            "spill with a person", "Someone dropped a tray of oil in the canteen near the counter",
            f("incident_type" to "Spill", "incident_location" to "Canteen", "incident_description" to "A tray of oil was dropped"),
            asked = emptyList(), mode = DialogueMode.PLAIN,
        ),
        Case(
            "spill without a person", "Oil spilled on the canteen floor near the counter",
            f("incident_type" to "Spill", "incident_location" to "Canteen", "incident_description" to "Oil spilled on the canteen floor"),
            asked = emptyList(), mode = DialogueMode.PLAIN,
        ),
        Case(
            "past cue, no time", "A pipe burst in block B earlier this morning",
            f("incident_type" to "Water Leak", "incident_location" to "Block B", "incident_description" to "A pipe burst in block B"),
            asked = listOf("time"), mode = DialogueMode.PLAIN,
        ),
        Case(
            "nobody hurt is not asked again", "Nobody was hurt but the printer gave off a spark in room 4.",
            f("incident_type" to "Electrical Hazard", "incident_location" to "Room 4", "incident_description" to "Printer sparked", "injury" to false),
            asked = listOf("immediate_actions"),
        ),
    )

    private suspend fun run(c: Case, config: DialogueConfig = DialogueConfig(useLlmRephrase = false)): Result {
        val out = RecordingOut()
        val logger = DialogueLogger()
        val manager = DialogueManager(
            TestAssets.assets, ScriptIn(listOf(said(c.narration)) + List(8) { null }), out,
            ScriptExtraction(listOf(reportJson("x", *c.fields))), config, logger = logger, clock = { TEST_CLOCK },
        )
        val report = (manager.run() as DialogueOutcome.Review).report
        return Result(manager, report, logger)
    }

    private class Result(val manager: DialogueManager, val report: IncidentReport, val logger: DialogueLogger) {
        val safety get() = manager.spoken.count { it.kind == SpokenKind.SAFETY_CHECK }
        val questions get() = manager.spoken.count { it.kind == SpokenKind.QUESTION }
    }

    @Test fun everyTranscriptAsksExactlyTheRelevantQuestions() = runTest {
        assertTrue(cases.size >= 15)
        for (c in cases) {
            val r = run(c)
            assertEquals("${c.name}: asked fields", c.asked, r.manager.askedFields)
            assertEquals("${c.name}: safety check", if (c.safety) 1 else 0, r.safety)
            assertTrue("${c.name}: ${r.questions} questions + ${r.safety} safety > 2", r.questions + r.safety <= 2)
            c.mode?.let { assertEquals("${c.name}: mode", it, r.manager.currentMode) }
            assertEquals("${c.name}: read-back is a statement", 0, r.manager.spoken.single { it.kind == SpokenKind.READBACK }.text.count { ch -> ch == '?' })
        }
    }

    private fun relevanceLines(r: Result) = r.logger.lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["relevance"].toString() != "null" }

    @Test fun theLogExplainsEverySkippedAndAskedField() = runTest {
        val c = cases.first { it.name == "AC leaking (facility)" }
        val result = run(c)
        val plan = relevanceLines(result).first { it["state"]!!.jsonPrimitive.content == "CLASSIFY_MODE" }["relevance"]!!.jsonObject
        assertEquals(DialogueFields.PRIORITY.toSet(), plan.keys)
        assertFalse("injury has no planner row (ADR-050)", "injury" in plan.keys)
        assertEquals("skip: location already given", plan.getValue("location").jsonPrimitive.content)
        val final = relevanceLines(result).last()["relevance"]!!.jsonObject
        assertEquals(DialogueFields.PRIORITY.toSet(), final.keys)
    }

    @Test fun anAskedTurnLogsItsReason() = runTest {
        val c = cases.first { it.name == "past cue, no time" }
        val result = run(c)
        val ask = relevanceLines(result).single { it["state"]!!.jsonPrimitive.content == "ASK_LOOP" }
        assertEquals("time", ask["field"]!!.jsonPrimitive.content)
        assertEquals("ask: past cue and no time given", ask["relevance"]!!.jsonObject.getValue("time").jsonPrimitive.content)
    }

    @Test fun theFinalLogNamesFieldsCutByTheCap() = runTest {
        val c = cases.first { it.name == "thin: something is broken" }
        val result = run(c, DialogueConfig(useLlmRephrase = false, maxQuestionsTotal = 1))
        val final = relevanceLines(result).last()["relevance"]!!.jsonObject
        assertTrue(final.getValue("description").jsonPrimitive.content.startsWith("skip: question cap reached"))
    }

    @Test fun injuryIsNeverAskedForAnyTranscriptEvenWithAPeopleCue() = runTest {
        for (c in cases) assertFalse("${c.name}: injury asked", "injury" in run(c).manager.askedFields)
        val cue = Case("snake bite", "Someone saw a snake in the Kalamba block", cases.first { it.name == "snake sighting" }.fields, emptyList())
        assertFalse("injury" in run(cue).manager.askedFields)
    }
}
