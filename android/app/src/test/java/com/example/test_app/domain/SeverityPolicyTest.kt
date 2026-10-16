package com.example.test_app.domain

import com.example.test_app.dialogue.DialogueAssetException
import com.example.test_app.dialogue.DialogueAssets
import com.example.test_app.dialogue.TestAssets
import com.example.test_app.domain.model.Clarification
import com.example.test_app.domain.model.ClarificationTurn
import com.example.test_app.domain.model.DialogueInfo
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InjuryOutcome
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.service.IncidentPolicies
import com.example.test_app.domain.service.SeverityCues
import com.example.test_app.domain.service.SeverityInput
import com.example.test_app.domain.service.SeverityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/** The deterministic severity policy: `final = max(model hint, floors)`; the LLM severity is only a hint. */
class SeverityPolicyTest {
    private val cues = TestAssets.assets.severityCues
    private val now = OffsetDateTime.parse("2026-10-11T14:00:00+05:30")

    private fun decide(
        hint: String? = null,
        urgent: Boolean = false,
        text: String = "",
        injury: Boolean? = null,
        outcome: String? = null,
        hazard: Boolean = false,
        type: String? = null,
    ) = SeverityPolicy.decide(SeverityInput(hint, urgent, listOf(text), injury, outcome, hazard, type), cues)

    // ---- the rule table ----

    @Test fun nothingKnownMeansNoSeverity() = assertNull(decide())

    @Test fun modelHintIsKeptWhenNoFloorIsHigher() {
        assertEquals("Medium", decide(hint = "Medium"))
        assertEquals("Low", decide(hint = "low"))
    }

    @Test fun hintIsNeverLowered() {
        assertEquals("High", decide(hint = "High", outcome = InjuryOutcome.NONE))
        assertEquals("Critical", decide(hint = "Critical", injury = false))
    }

    @Test fun injuryHazardTableIsAFloor() {
        assertEquals("Low", decide(outcome = InjuryOutcome.NONE))
        assertEquals("Medium", decide(outcome = InjuryOutcome.NONE, hazard = true))
        assertEquals("Medium", decide(outcome = InjuryOutcome.MINOR))
        assertEquals("High", decide(outcome = InjuryOutcome.MINOR, hazard = true))
        assertEquals("High", decide(outcome = InjuryOutcome.SERIOUS))
        assertEquals("Critical", decide(outcome = InjuryOutcome.SERIOUS, hazard = true))
        assertEquals("Critical", decide(outcome = InjuryOutcome.FATAL))
        assertEquals("High", decide(hint = "Low", outcome = InjuryOutcome.SERIOUS))
    }

    @Test fun injuryAnswerWithoutOutcomeCountsAsMinorOrNone() {
        assertEquals("Medium", decide(injury = true))
        assertEquals("High", decide(injury = true, hazard = true))
        assertEquals("Low", decide(injury = false))
    }

    @Test fun fireGasAndElectricalAreAtLeastHigh() {
        for (t in listOf("Fire", "Gas Leak", "Electrical Hazard", "fire", "GAS LEAK")) {
            assertEquals(t, "High", decide(hint = "Medium", type = t))
            assertEquals(t, "High", decide(type = t))
        }
        assertEquals("Critical", decide(hint = "Critical", type = "Fire"))
        assertEquals("Medium", decide(hint = "Medium", type = "Water Leak"))
        assertEquals("Low", decide(hint = "Low", type = "Spill"))
    }

    @Test fun urgentModeIsAtLeastHigh() {
        assertEquals("High", decide(urgent = true))
        assertEquals("High", decide(hint = "Low", urgent = true))
        assertEquals("High", decide(hint = "Medium", urgent = true, text = "smoke in the server room"))
    }

    @Test fun urgentWithEveryEscalationCueIsCritical() {
        assertTrue(cues.escalation.size >= 15)
        for (cue in cues.escalation) {
            assertEquals(cue, "Critical", decide(hint = "Medium", urgent = true, text = "something is wrong, $cue"))
        }
    }

    @Test fun requiredCuesAreInTheAsset() {
        for (c in listOf(
            "urgent", "emergency", "need help", "please help", "hurry", "come fast", "trapped", "not breathing", "unconscious",
            "heavy bleeding", "serious bleeding", "a lot of bleeding", "bleeding badly", "seriously bleeding",
        )) assertTrue(c, c in cues.escalation)
        for (t in listOf("fire", "gas leak", "electrical hazard")) assertTrue(t, t in cues.highIncidentTypes)
    }

    @Test fun urgentWithAnInjuryIsCritical() {
        assertEquals("Critical", decide(hint = "Medium", urgent = true, injury = true))
        assertEquals("Critical", decide(hint = "Medium", urgent = true, outcome = InjuryOutcome.MINOR))
        assertEquals("High", decide(hint = "Medium", urgent = true, injury = false))
        assertEquals("High", decide(hint = "Medium", urgent = true, outcome = InjuryOutcome.NONE))
    }

    @Test fun escalationCuesDoNothingOutsideUrgentMode() {
        assertEquals("Low", decide(hint = "Low", text = "it is very urgent, I need help"))
        assertEquals("Medium", decide(hint = "Medium", text = "emergency"))
    }

    @Test fun negatedCuesDoNotEscalate() {
        for (t in listOf("it is not urgent", "this is not an emergency", "I don't need help", "we do not need help", "nobody is trapped")) {
            assertEquals(t, "High", decide(hint = "Medium", urgent = true, text = t))
        }
        // a negation outside the 4-token window or in another clause does not cancel
        assertEquals("Critical", decide(hint = "Medium", urgent = true, text = "No, I need help"))
        assertEquals("Critical", decide(hint = "Medium", urgent = true, text = "nobody else is here and it is an emergency"))
    }

    @Test fun cuesNeedWholeWords() {
        assertEquals("High", decide(hint = "Medium", urgent = true, text = "the urgently needed parts and a helpdesk"))
    }

    @Test fun anyOfTheTextsCanCarryTheCue() {
        val out = SeverityPolicy.decide(
            SeverityInput("Medium", true, listOf("There is smoke", "Are you safe", "no, please help"), null, null, false, null), cues,
        )
        assertEquals("Critical", out)
    }

    @Test fun noCuesAtAllStillGivesTheModeFloor() {
        assertEquals("High", SeverityPolicy.decide(SeverityInput(null, true, emptyList(), null, null, false, null), SeverityCues.NONE))
        assertNull(SeverityPolicy.decide(SeverityInput(null, false, listOf("emergency"), null, null, false, "Fire"), SeverityCues.NONE))
    }

    @Test fun malformedSeverityCuesFailLoudly() {
        for (bad in listOf("""{"escalation_cues": [], "high_incident_types": ["Fire"]}""", """{"escalation_cues": ["urgent"], "high_incident_types": []}""", "not json")) {
            try {
                DialogueAssets.load("en") { name -> if (name == "severity_cues.json") bad else java.io.File(TestAssets.dir, name).readText() }
                throw AssertionError("expected DialogueAssetException for $bad")
            } catch (e: DialogueAssetException) {
                assertTrue(e.message!!, e.message!!.isNotEmpty())
            }
        }
    }

    @Test fun negationWordsAreSharedWithTheModeKeywords() {
        assertEquals(TestAssets.assets.modeKeywords.negationWords, cues.negationWords)
        assertEquals(TestAssets.assets.modeKeywords.negationWindowTokens, cues.negationWindowTokens)
    }

    @Test fun maxOrdersTheFourLevels() {
        assertEquals("Critical", SeverityPolicy.max("High", "Critical"))
        assertEquals("High", SeverityPolicy.max("high", null))
        assertNull(SeverityPolicy.max(null, "bogus"))
    }

    // ---- through IncidentPolicies.apply (what Review and the dialogue use) ----

    private fun report(
        vararg fields: Pair<IncidentField, FieldEntry>,
        mode: String = "PLAIN",
        emergency: Boolean = false,
        transcript: String = "x",
        answers: List<String> = emptyList(),
    ) = IncidentReport(
        reportId = "INC-TEST", status = "x", createdAt = "2026-10-11T14:00:00+05:30",
        input = ReportInput("voice", transcript),
        fields = fields.associate { it.first.key to it.second },
        clarification = Clarification(history = answers.map { ClarificationTurn("q", it, emptyList(), emptyList()) }),
        dialogue = DialogueInfo(mode, emptyList(), emergency),
    )

    private fun ai(v: String, c: Double = 0.7) = FieldEntry(textValue(v), c, FieldSource.AI)
    private fun severityOf(r: IncidentReport) = r.fields["severity_level"]!!.value.asTextOrNull()

    @Test fun applyUsesTheReportsOwnModeAndTranscript() {
        val r = report(
            IncidentField.INCIDENT_TYPE to ai("Fire"), IncidentField.SEVERITY_LEVEL to ai("Medium"),
            mode = "URGENT", emergency = true, transcript = "It's very urgent. There's a fire in front of me. Need help.",
        )
        assertEquals("Critical", severityOf(IncidentPolicies.apply(r, now, cues)))
    }

    @Test fun applyGivesHighForUrgentFireWithoutCue() {
        val r = report(
            IncidentField.INCIDENT_TYPE to ai("Fire"), IncidentField.SEVERITY_LEVEL to ai("Medium"),
            mode = "URGENT", transcript = "There's a fire in the inner AC in the lab.",
        )
        val out = IncidentPolicies.apply(r, now, cues)
        assertEquals("High", severityOf(out))
        assertEquals(IncidentPolicies.SUGGESTED_SEVERITY_CONFIDENCE, out.fields["severity_level"]!!.confidence, 0.0)
        assertEquals(FieldSource.AI, out.fields["severity_level"]!!.source)
    }

    @Test fun emergencyFlagAloneCountsAsUrgent() {
        val out = IncidentPolicies.apply(report(IncidentField.SEVERITY_LEVEL to ai("Low"), emergency = true), now, cues)
        assertEquals("High", severityOf(out))
    }

    @Test fun answersInTheHistoryAndExtraTextsFeedTheCues() {
        val base = report(IncidentField.SEVERITY_LEVEL to ai("Low"), mode = "URGENT")
        assertEquals("High", severityOf(IncidentPolicies.apply(base, now, cues)))
        val viaHistory = base.copy(clarification = Clarification(history = listOf(ClarificationTurn("q", "please help me", emptyList(), emptyList()))))
        assertEquals("Critical", severityOf(IncidentPolicies.apply(viaHistory, now, cues)))
        assertEquals("Critical", severityOf(IncidentPolicies.apply(base, now, cues, extraTexts = listOf("he is not breathing"))))
    }

    @Test fun anUnchangedModelHintKeepsItsConfidence() {
        val r = report(IncidentField.SEVERITY_LEVEL to ai("High", 0.7), IncidentField.INJURY_OUTCOME to ai(InjuryOutcome.NONE, 0.9))
        val out = IncidentPolicies.apply(r, now, cues)
        assertEquals("High", severityOf(out))
        assertEquals(0.7, out.fields["severity_level"]!!.confidence, 0.0)
    }

    @Test fun userEditedSeverityIsNeverTouchedNotEvenInUrgentMode() {
        val r = report(
            IncidentField.SEVERITY_LEVEL to FieldEntry(textValue("Low"), 1.0, FieldSource.USER_EDIT),
            IncidentField.INCIDENT_TYPE to ai("Fire"), mode = "URGENT", emergency = true, transcript = "very urgent fire, need help",
        )
        val out = IncidentPolicies.apply(r, now, cues)
        assertEquals("Low", severityOf(out))
        assertEquals(FieldSource.USER_EDIT, out.fields["severity_level"]!!.source)
    }

    @Test fun severityIsEscalateOnlyAcrossRepeatedPasses() {
        var r = report(IncidentField.INCIDENT_TYPE to ai("Fire"), IncidentField.SEVERITY_LEVEL to ai("Low"), mode = "URGENT")
        r = IncidentPolicies.apply(r, now, cues)
        assertEquals("High", severityOf(r))
        // later facts that would only justify a lower value do not lower it
        r = r.copy(fields = r.fields + (IncidentField.INJURY.key to FieldEntry(boolValue(false), 0.9)) +
            (IncidentField.INJURY_OUTCOME.key to ai(InjuryOutcome.NONE, 0.9)))
        r = IncidentPolicies.apply(r, now, cues)
        assertEquals("High", severityOf(r))
        // and a later injury answer raises it
        r = r.copy(fields = r.fields + (IncidentField.INJURY.key to FieldEntry(boolValue(true), 0.9)))
        assertEquals("Critical", severityOf(IncidentPolicies.apply(r, now, cues)))
    }

    @Test fun nonUrgentReportsKeepTheOldTableBehaviour() {
        val none = IncidentPolicies.apply(report(IncidentField.INJURY_OUTCOME to ai(InjuryOutcome.NONE, 0.9)), now, cues)
        assertEquals("Low", severityOf(none))
        val unknown = IncidentPolicies.apply(report(), now, cues)
        assertTrue(unknown.fields["severity_level"]!!.value.asTextOrNull() == null)
    }

    @Test fun noInjuryOutcomeIsInventedWhenInjuryIsUnknown() {
        val out = IncidentPolicies.apply(
            report(IncidentField.INCIDENT_TYPE to ai("Fire"), IncidentField.SEVERITY_LEVEL to ai("Medium"), mode = "URGENT"), now, cues,
        )
        assertNull(out.fields["injury_outcome"]?.value?.asTextOrNull())
        assertTrue(out.fields["injury"]?.value?.asTextOrNull() == null)
    }
}
