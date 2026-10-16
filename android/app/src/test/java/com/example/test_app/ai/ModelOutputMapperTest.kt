package com.example.test_app.ai

import com.example.test_app.ai.AiTestSupport.NOW
import com.example.test_app.ai.AiTestSupport.obj
import com.example.test_app.ai.AiTestSupport.request
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.asStringList
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.isEmptyValue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ModelOutputMapperTest {
    private val leakTranscript = "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area."
    private val leakJson = """{"incident_type": "water leak", "severity": "medium", "location": "Conference Room 2", "datetime": "around 10 AM",
        "persons_involved": null, "actions_taken": "Maintenance isolated the area", "description": "Water leak near Conference Room 2, area isolated."}"""

    private fun first(json: String, transcript: String) =
        ModelOutputMapper.firstPass(obj(json), transcript, request(transcript), NOW)

    private fun leakReport() = first(leakJson, leakTranscript)

    private fun IncidentReport.entry(key: String): FieldEntry = fields.getValue(key)
    private fun IncidentReport.text(key: String): String? = entry(key).value.asTextOrNull()
    private fun IncidentReport.empty(key: String) = entry(key).value.isEmptyValue()

    // ---- first pass --------------------------------------------------------------------------

    @Test fun firstPassMapsEveryKey() {
        val r = leakReport()
        assertEquals("Water Leak", r.text("incident_type"))
        assertEquals("Medium", r.text("severity_level"))
        assertEquals("Conference Room 2", r.text("incident_location"))
        assertEquals(0.85, r.entry("incident_location").confidence, 1e-9)
        assertEquals(OffsetDateTime.parse("2026-10-11T10:00:00+05:30"), OffsetDateTime.parse(r.text("date_time")))
        assertTrue(r.empty("persons_involved"))
        assertEquals("Maintenance isolated the area", r.text("immediate_actions_taken"))
        assertEquals("Water leak near Conference Room 2, area isolated.", r.text("incident_description"))
        assertTrue(r.fields.values.all { it.source == FieldSource.AI })
        assertEquals(leakTranscript, r.input.transcript)
        assertEquals(IncidentReport.STATUS_NEEDS_CLARIFICATION, r.status)
        assertTrue(r.empty("injury"))
        assertTrue(r.empty("hazard_still_active"))
    }

    @Test fun emptyLikeStringsNeverBecomeValues() {
        val r = first(
            """{"incident_type": "null", "severity": "unknown", "location": "none", "datetime": " ", "persons_involved": "N/A",
               "actions_taken": "None", "description": ""}""",
            "hello there",
        )
        for (k in listOf("incident_type", "severity_level", "incident_location", "date_time", "persons_involved", "immediate_actions_taken", "incident_description")) {
            assertTrue(k, r.empty(k))
            assertEquals(k, 0.0, r.entry(k).confidence, 0.0)
        }
    }

    @Test fun typeIsNormalisedOrKept() {
        assertEquals("Fire", first("""{"incident_type": "fire"}""", "x").text("incident_type"))
        assertEquals("Electrical Hazard", first("""{"incident_type": " ELECTRICAL hazard "}""", "x").text("incident_type"))
        assertEquals("Slip / Fall", first("""{"incident_type": "Slip / Fall"}""", "x").text("incident_type"))
    }

    @Test fun severityOnlyFromTheAllowedList() {
        assertEquals("Critical", first("""{"severity": "critical"}""", "x").text("severity_level"))
        assertTrue(first("""{"severity": "Severe"}""", "x").empty("severity_level"))
        assertTrue(first("""{"severity": null}""", "x").empty("severity_level"))
    }

    @Test fun personsAreSplit() {
        val r = first("""{"persons_involved": "Ravi, Sam and the guard / Priya"}""", "x")
        assertEquals(listOf("Ravi", "Sam", "the guard", "Priya"), r.entry("persons_involved").value.asStringList())
        val arr = first("""{"persons_involved": ["a worker", "Ravi"]}""", "x")
        assertEquals(listOf("a worker", "Ravi"), arr.entry("persons_involved").value.asStringList())
    }

    @Test fun unparsableDatetimeStaysEmpty() {
        val r = first("""{"datetime": "sometime last week-ish"}""", "x")
        assertTrue(r.empty("date_time"))
    }

    @Test fun explicitNegationSetsInjuryFalse() {
        for (t in listOf(
            "glass broke in the corridor nobody is hurt we cleaned it",
            "No one was injured.", "nobody got hurt", "none of them were hurt", "There were no injuries", "No injury, just a spill",
            "he was not hurt", "she wasn't injured", "luckily no one got badly hurt",
        )) {
            val r = first("""{"incident_type": "Spill"}""", t)
            assertEquals(t, JsonPrimitive(false), r.entry("injury").value)
            assertEquals(t, 0.85, r.entry("injury").confidence, 1e-9)
        }
    }

    @Test fun injuryTrueFromTypeOrTranscript() {
        val byType = first("""{"incident_type": "Injury"}""", "the worker is on the floor")
        assertEquals(JsonPrimitive(true), byType.entry("injury").value)
        assertEquals(0.8, byType.entry("injury").confidence, 1e-9)
        val byText = first("""{"incident_type": "Accident"}""", "a worker cut his hand on the machine")
        assertEquals(JsonPrimitive(true), byText.entry("injury").value)
        val negatedType = first("""{"incident_type": "Injury"}""", "nobody was hurt")
        assertEquals(JsonPrimitive(false), negatedType.entry("injury").value)
    }

    @Test fun injuryStaysEmptyWithoutEvidence() {
        for (t in listOf(leakTranscript, "something is broken", "I'm not sure who was involved")) {
            assertTrue(t, first("""{"incident_type": "Other"}""", t).empty("injury"))
        }
    }

    @Test fun emergencyKeywordRaisesSeverityToHigh() {
        for ((sev, expected) in listOf("null" to "High", "\"Low\"" to "High", "\"Medium\"" to "High")) {
            val r = first("""{"incident_type": "Other", "severity": $sev}""", "there is smoke coming out of the lab")
            assertEquals(sev, expected, r.text("severity_level"))
            assertEquals(0.6, r.entry("severity_level").confidence, 1e-9)
        }
        for (kw in listOf("fire", "gas leak", "explosion", "unconscious", "not breathing", "heavy bleeding", "electrocuted", "trapped", "collapsed", "weapon", "knife", "gun")) {
            assertEquals(kw, "High", first("""{"severity": "Low"}""", "there is a $kw here").text("severity_level"))
        }
    }

    @Test fun emergencyNeverLowersSeverity() {
        val critical = first("""{"severity": "Critical"}""", "a fire and someone is trapped")
        assertEquals("Critical", critical.text("severity_level"))
        assertEquals(0.7, critical.entry("severity_level").confidence, 1e-9)
        assertEquals("High", first("""{"severity": "High"}""", "a fire").text("severity_level"))
    }

    @Test fun noEmergencyKeywordLeavesSeverity() {
        assertTrue(first("""{"severity": null}""", "a pipe is dripping").empty("severity_level"))
        assertEquals("Low", first("""{"severity": "Low"}""", "a firefighter drill? no, a firm handshake").text("severity_level"))
    }

    @Test fun hazardStillActiveOnlyWhenSaid() {
        assertEquals(JsonPrimitive(true), first("{}", "the gas is still leaking").entry("hazard_still_active").value)
        assertEquals(JsonPrimitive(true), first("{}", "water is still dripping from the ceiling").entry("hazard_still_active").value)
        assertTrue(first("{}", "gas leak in the kitchen").empty("hazard_still_active"))
        assertTrue(first("{}", "I am still here and the fire happened earlier").empty("hazard_still_active"))
    }

    // ---- SpokenTime ---------------------------------------------------------------------------

    private fun t(text: String?) = SpokenTime.parse(text, NOW)
    private fun at(iso: String) = OffsetDateTime.parse(iso)

    @Test fun spokenClockTimes() {
        assertEquals(at("2026-10-11T10:00:00+05:30"), t("around 10 AM"))
        assertEquals(at("2026-10-11T10:00:00+05:30"), t("10am"))
        assertEquals(at("2026-10-11T13:45:00+05:30"), t("13:45"))
        assertEquals(at("2026-10-11T12:00:00+05:30"), t("at 12 p.m."))
        assertEquals(at("2026-10-11T00:15:00+05:30"), t("12:15 am"))
        // later than now today means the previous day
        assertEquals(at("2026-10-10T22:30:00+05:30"), t("10:30 pm"))
    }

    @Test fun spokenNow() {
        assertEquals(NOW, t("just now"))
        assertEquals(NOW, t("now"))
        assertEquals(NOW, t("Right now"))
    }

    @Test fun spokenRelativeTimes() {
        assertEquals(at("2026-10-11T13:55:00+05:30"), t("5 minutes ago"))
        assertEquals(at("2026-10-11T13:55:00+05:30"), t("like 5 mins ago"))
        assertEquals(at("2026-10-11T13:00:00+05:30"), t("an hour ago"))
        assertEquals(at("2026-10-11T12:00:00+05:30"), t("two hours ago"))
        assertEquals(at("2026-10-11T13:30:00+05:30"), t("half an hour ago"))
        assertEquals(at("2026-10-11T13:58:00+05:30"), t("a couple of minutes ago"))
        assertEquals(at("2026-10-09T14:00:00+05:30"), t("2 days ago"))
    }

    @Test fun spokenPartsOfDay() {
        assertEquals(at("2026-10-11T09:00:00+05:30"), t("this morning"))
        assertEquals(at("2026-10-11T14:00:00+05:30"), t("this evening")) // 18:00 would be in the future: capped at now
        assertEquals(at("2026-10-10T12:00:00+05:30"), t("yesterday"))
        assertEquals(at("2026-10-10T09:00:00+05:30"), t("yesterday morning"))
        assertEquals(at("2026-10-10T21:00:00+05:30"), t("last night"))
        assertEquals(at("2026-10-10T15:00:00+05:30"), t("yesterday at 3 pm"))
        assertEquals(at("2026-10-11T08:30:00+05:30"), t("8:30 this morning"))
    }

    @Test fun unparsableTimesAreNull() {
        assertNull(t("room 3"))
        assertNull(t("sometime"))
        assertNull(t(""))
        assertNull(t(null))
        assertNull(t("a few minutes ago"))
        assertNull(t("25:99"))
    }

    @Test fun formatIsIsoWithOffset() {
        assertEquals("2026-10-11T10:00:00+05:30", SpokenTime.format(at("2026-10-11T10:00:00.123+05:30")))
    }

    // ---- deterministic answers -----------------------------------------------------------------

    @Test fun fireTranscriptsLeaveInjuryAndOutcomeEmpty() {
        // device sessions: type Fire, model severity Medium. No injury evidence => no injury, no bogus "Minor injury" default.
        for (t in listOf(
            "There's a fire in the inner AC in KRBCVIT lab.",
            "It's very urgent. There's a whole cod fire in front of me. I'm in front of Vindya A4. Need help.",
        )) {
            val r = first("""{"incident_type": "Fire", "severity": "Medium", "location": "KRBCVIT lab", "datetime": null,
                "persons_involved": null, "actions_taken": null, "description": "Fire."}""", t)
            assertTrue(t, r.empty("injury"))
            assertTrue(t, r.empty("injury_outcome"))
            assertEquals(t, "High", r.text("severity_level")) // the mapper's own emergency floor; the policy decides the final value
        }
    }

    @Test fun injuredPersonAnswers() {
        for (a in listOf("me", "I", "myself", "it was me", "I was hurt")) {
            val r = ModelOutputMapper.applyUpdate(leakReport(), null, "injured_person", a, NOW)
            assertEquals(a, listOf("Reporter (injured)"), r.entry("persons_involved").value.asStringList())
        }
        val named = ModelOutputMapper.applyUpdate(leakReport(), null, "injured_person", "uh it was my colleague Ravi", NOW)
        assertEquals(listOf("My colleague Ravi (injured)"), named.entry("persons_involved").value.asStringList())
        assertEquals(FieldSource.USER_VOICE, named.entry("persons_involved").source)
    }

    @Test fun injuredPersonReplacesPlainDuplicateAndKeepsOthers() {
        val base = leakReport().let { it.copy(fields = it.fields + ("persons_involved" to FieldEntry(com.example.test_app.domain.model.listValue(listOf("Ravi", "Sam")), 0.8))) }
        val r = ModelOutputMapper.applyUpdate(base, null, "injured_person", "Ravi", NOW)
        assertEquals(listOf("Sam", "Ravi (injured)"), r.entry("persons_involved").value.asStringList())
        val again = ModelOutputMapper.applyUpdate(r, null, "injured_person", "ravi", NOW)
        assertEquals(listOf("Sam", "Ravi (injured)"), again.entry("persons_involved").value.asStringList())
    }

    @Test fun injuryTypeIsCleanedWithoutLlm() {
        assertEquals("Deep cut", ModelOutputMapper.applyUpdate(leakReport(), null, "injury_type", "it's a deep cut", NOW).text("injury_type"))
        assertEquals("Burn", ModelOutputMapper.applyUpdate(leakReport(), null, "injury_type", "uh I think a burn.", NOW).text("injury_type"))
        assertEquals("Broke my arm", ModelOutputMapper.applyUpdate(leakReport(), null, "injury_type", "I broke my arm", NOW).text("injury_type"))
        val before = leakReport()
        assertSame(before, ModelOutputMapper.applyUpdate(before, null, "injury_type", "uh", NOW))
    }

    @Test fun needsLlmOnlyForTheNonDeterministicFields() {
        for (f in listOf("injured_person", "injury_type")) assertTrue(f, !ModelOutputMapper.needsLlm(f))
        for (f in listOf("location", "time", "immediate_actions", "people_involved", "description", null)) assertTrue(f.toString(), ModelOutputMapper.needsLlm(f))
    }

    // ---- LLM update JSON ------------------------------------------------------------------------

    @Test fun updateLocationOnlyTouchesLocation() {
        val before = leakReport()
        val after = ModelOutputMapper.applyUpdate(before, obj("""{"location": "block B second floor near the lift"}"""), "location", "uh it's in block B second floor near the lift", NOW)
        assertEquals("block B second floor near the lift", after.text("incident_location"))
        assertEquals(FieldSource.USER_VOICE, after.entry("incident_location").source)
        assertEquals(0.85, after.entry("incident_location").confidence, 1e-9)
        for (k in before.fields.keys - "incident_location") assertEquals(k, before.entry(k), after.entry(k))
    }

    @Test fun updateWithMultipleKeys() {
        val after = ModelOutputMapper.applyUpdate(
            leakReport(),
            obj("""{"location": "room 3", "datetime": "5 minutes ago", "persons_involved": "Ravi and Sam", "actions_taken": "closed the valve",
                   "incident_type": "fire", "severity": "high"}"""),
            null, "x", NOW,
        )
        assertEquals("room 3", after.text("incident_location"))
        assertEquals(at("2026-10-11T13:55:00+05:30"), OffsetDateTime.parse(after.text("date_time")))
        assertEquals(listOf("Ravi", "Sam"), after.entry("persons_involved").value.asStringList())
        assertEquals("closed the valve", after.text("immediate_actions_taken"))
        assertEquals("Fire", after.text("incident_type"))
        assertEquals("High", after.text("severity_level"))
        assertEquals(FieldSource.USER_VOICE, after.entry("severity_level").source)
    }

    @Test fun updateNeverErasesOrInventsValues() {
        val before = leakReport()
        val after = ModelOutputMapper.applyUpdate(
            before,
            obj("""{"location": null, "datetime": "whenever", "persons_involved": "none", "actions_taken": "", "description": "unknown", "severity": "Severe", "incident_type": "null"}"""),
            "location", "dunno", NOW,
        )
        assertEquals(before.fields, after.fields)
    }

    @Test fun badOrEmptyUpdateLeavesReportUnchanged() {
        val before = leakReport()
        assertSame(before, ModelOutputMapper.applyUpdate(before, null, "location", "block B", NOW))
        assertEquals(before.fields, ModelOutputMapper.applyUpdate(before, obj("{}"), "location", "block B", NOW).fields)
    }

    @Test fun peopleInvolvedAreMergedWithoutDroppingInjuredOnes() {
        val base = ModelOutputMapper.applyUpdate(leakReport(), null, "injured_person", "me", NOW)
        val after = ModelOutputMapper.applyUpdate(base, obj("""{"persons_involved": "Ravi"}"""), "people_involved", "Ravi was there", NOW)
        assertEquals(listOf("Reporter (injured)", "Ravi"), after.entry("persons_involved").value.asStringList())
    }

    @Test fun descriptionUpdateIsAppended() {
        val after = ModelOutputMapper.applyUpdate(leakReport(), obj("""{"description": "The pipe burst near the lift."}"""), "description", "the pipe burst", NOW)
        assertEquals("Water leak near Conference Room 2, area isolated. The pipe burst near the lift.", after.text("incident_description"))
        val empty = ModelOutputMapper.applyUpdate(
            first("{}", "x"), obj("""{"description": "The pipe burst."}"""), "description", "the pipe burst", NOW,
        )
        assertEquals("The pipe burst.", empty.text("incident_description"))
    }

    @Test fun currentFormHasShortKeysAndNoEmptyFields() {
        val form = ModelOutputMapper.currentForm(leakReport())
        assertEquals(setOf("incident_type", "location", "datetime", "actions_taken", "description"), form.keys)
        assertEquals("Water Leak", form["incident_type"])
        val noTime = ModelOutputMapper.currentForm(leakReport().copy(timeSource = "default_now"))
        assertTrue("datetime" !in noTime.keys)
    }

    @Test fun jsonNullEntryHelper() {
        assertEquals(JsonNull, leakReport().entry("injury").value)
    }
}
