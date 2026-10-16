package com.example.test_app.domain

import com.example.test_app.dialogue.TestAssets
import com.example.test_app.domain.config.IncidentConfig
import com.example.test_app.domain.model.ClarificationTurn
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InjuryOutcome
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.listValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.model.Clarification
import com.example.test_app.domain.service.DefaultValidationService
import com.example.test_app.domain.service.IncidentJsonCodec
import com.example.test_app.domain.service.IncidentPolicies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ValidationAndPoliciesTest {
    private val config = IncidentConfig()
    private val validation = DefaultValidationService()
    private val cues = TestAssets.assets.severityCues

    private fun report(vararg fields: Pair<IncidentField, FieldEntry>, turns: List<ClarificationTurn> = emptyList()) = IncidentReport(
        reportId = "INC-TEST", status = "x", createdAt = "2026-10-10T10:00:00+05:30",
        input = ReportInput("voice", "t"),
        fields = fields.associate { it.first.key to it.second },
        clarification = Clarification(history = turns),
    )

    private fun ai(text: String, c: Double = 0.9) = FieldEntry(textValue(text), c, FieldSource.AI)

    private val full = arrayOf(
        IncidentField.INCIDENT_DESCRIPTION to ai("Leak"),
        IncidentField.INJURY_OUTCOME to ai(InjuryOutcome.NONE),
        IncidentField.INCIDENT_LOCATION to ai("Room 2"),
        IncidentField.DATE_TIME to ai("2026-10-10T10:00:00+05:30"),
    )

    @Test fun completeReportHasNoAttentionFields() {
        val v = validation.validate(report(*full), config)
        assertTrue(v.validation.isComplete)
        assertTrue(v.validation.attentionFields.isEmpty())
        assertEquals(IncidentReport.STATUS_COMPLETE, v.status)
    }

    @Test fun emptyStringAndEmptyListCountAsMissing() {
        val r = report(
            IncidentField.INCIDENT_DESCRIPTION to ai("  "),
            IncidentField.INJURY_OUTCOME to FieldEntry(listValue(emptyList()), 0.9),
        )
        val v = validation.validate(r, config).validation
        assertTrue("incident_description" in v.missingFields)
        assertTrue("injury_outcome is not mandatory (ADR-050)", "injury_outcome" !in v.missingFields)
        assertTrue("incident_location" in v.missingFields)
    }

    @Test fun injuryIsNotMandatoryAndNeverAsked() {
        assertTrue("injury_outcome" !in config.mandatoryFields)
        assertTrue("injury_outcome" !in config.askOrder)
        val noInjury = validation.validate(report(*full.filter { it.first != IncidentField.INJURY_OUTCOME }.toTypedArray()), config)
        assertEquals(emptyList<String>(), noInjury.validation.missingFields)
        assertTrue(noInjury.validation.isComplete)
        val odd = validation.validate(report(*full.filter { it.first != IncidentField.INJURY_OUTCOME }.toTypedArray(), IncidentField.INJURY_OUTCOME to ai("kinda")), config)
        assertEquals(emptyList<String>(), odd.validation.missingFields)
    }

    @Test fun lowConfidenceAiFieldIsFlaggedButUserFieldIsNot() {
        val low = report(*full.filter { it.first != IncidentField.INCIDENT_LOCATION }.toTypedArray(),
            IncidentField.INCIDENT_LOCATION to ai("Near stairs", 0.45))
        assertEquals(listOf("incident_location"), validation.validate(low, config).validation.lowConfidenceFields)

        val user = report(*full.filter { it.first != IncidentField.INCIDENT_LOCATION }.toTypedArray(),
            IncidentField.INCIDENT_LOCATION to FieldEntry(textValue("Near stairs"), 0.45, FieldSource.USER_VOICE))
        assertTrue(validation.validate(user, config).validation.isComplete)
    }

    @Test fun dateTimeIsNeverLowConfidence() {
        val r = report(*full.filter { it.first != IncidentField.DATE_TIME }.toTypedArray(),
            IncidentField.DATE_TIME to ai("2026-10-10T10:00:00+05:30", 0.1))
        assertTrue(validation.validate(r, config).validation.isComplete)
    }

    @Test fun injuredPersonTriggersWhoWasHurtOnce() {
        val injured = arrayOf(*full.filter { it.first != IncidentField.INJURY_OUTCOME }.toTypedArray(),
            IncidentField.INJURY_OUTCOME to ai(InjuryOutcome.MINOR))
        val v = validation.validate(report(*injured), config).validation
        assertEquals(listOf("persons_involved"), v.followUpFields)
        assertTrue(v.isComplete) // follow-ups never block completeness

        val asked = validation.validate(
            report(*injured, turns = listOf(ClarificationTurn("Who was hurt?", "nobody", listOf("persons_involved"), emptyList()))),
            config,
        ).validation
        assertTrue(asked.followUpFields.isEmpty())
    }

    @Test fun highSeverityOrActiveHazardTriggersActionsQuestion() {
        val hazard = report(*full, IncidentField.HAZARD_STILL_ACTIVE to FieldEntry(boolValue(true), 0.9))
        assertEquals(listOf("immediate_actions_taken"), validation.validate(hazard, config).validation.followUpFields)

        val calm = report(*full, IncidentField.HAZARD_STILL_ACTIVE to FieldEntry(boolValue(false), 0.9))
        assertTrue(validation.validate(calm, config).validation.followUpFields.isEmpty())
    }

    @Test fun severityIsSuggestedFromInjuryAndHazard() {
        assertNull(IncidentPolicies.suggestSeverity(null, false))
        assertEquals("Low", IncidentPolicies.suggestSeverity(InjuryOutcome.NONE, false))
        assertEquals("Medium", IncidentPolicies.suggestSeverity(InjuryOutcome.NONE, true))
        assertEquals("Medium", IncidentPolicies.suggestSeverity(InjuryOutcome.MINOR, false))
        assertEquals("High", IncidentPolicies.suggestSeverity(InjuryOutcome.MINOR, true))
        assertEquals("High", IncidentPolicies.suggestSeverity(InjuryOutcome.SERIOUS, false))
        assertEquals("Critical", IncidentPolicies.suggestSeverity(InjuryOutcome.FATAL, false))
    }

    @Test fun userSetSeverityIsNeverOverwritten() {
        val r = report(*full, IncidentField.SEVERITY_LEVEL to FieldEntry(textValue("Critical"), 1.0, FieldSource.USER_EDIT))
        val out = IncidentPolicies.apply(r, cues = cues)
        assertEquals("Critical", out.fields["severity_level"]!!.value.asTextOrNull())
    }

    @Test fun missingTimeDefaultsToNow() {
        val now = OffsetDateTime.parse("2026-10-10T21:42:10+05:30")
        val out = IncidentPolicies.apply(report(*full.filter { it.first != IncidentField.DATE_TIME }.toTypedArray()), now, cues)
        val entry = out.fields["date_time"]!!
        assertEquals("2026-10-10T21:42:10+05:30", entry.value.asTextOrNull())
        assertFalse(entry.confidence < config.confidenceThreshold)
    }

    @Test fun reportRoundTripsThroughJson() {
        val r = IncidentPolicies.apply(report(*full), cues = cues)
        assertEquals(r, IncidentJsonCodec.decode(IncidentJsonCodec.encode(r)))
    }
}
