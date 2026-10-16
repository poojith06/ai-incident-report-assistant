package com.example.test_app.knowledgegraph

import com.example.test_app.knowledgegraph.model.EdgeConditions
import com.example.test_app.knowledgegraph.model.IncidentStatus
import com.example.test_app.knowledgegraph.model.KgSeverity
import com.example.test_app.knowledgegraph.rules.ConditionEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec section 20: every edge condition operator, including the "unknown fact fails and names the fact" rule. */
class ConditionEvaluatorTest {
    private fun eval(c: EdgeConditions, i: com.example.test_app.knowledgegraph.model.ValidatedIncident = incident()) = ConditionEvaluator.evaluate(c, i)

    @Test fun emptyConditionsPass() = assertTrue(eval(EdgeConditions()).passed)

    @Test fun incidentTypesAreAnyOfAndCaseInsensitive() {
        assertTrue(eval(EdgeConditions(incidentTypes = listOf("fire", "WATER_LEAK"))).passed)
        assertFalse(eval(EdgeConditions(incidentTypes = listOf("WATER_LEAK"))).passed)
        val unknown = eval(EdgeConditions(incidentTypes = listOf("FIRE")), incident(type = null))
        assertFalse(unknown.passed); assertEquals(setOf("incidentType"), unknown.missingFacts)
    }

    @Test fun locationTypesMatchAndUnknownLocationFailsNamingTheFact() {
        val ok = eval(EdgeConditions(locationTypes = listOf("COMPUTER_LAB", "CLASSROOM")))
        assertTrue(ok.passed); assertTrue(ok.matchedLocationType)
        assertFalse(eval(EdgeConditions(locationTypes = listOf("CLASSROOM"))).passed)
        val unknown = eval(EdgeConditions(locationTypes = listOf("COMPUTER_LAB")), incident(locationType = null))
        assertFalse(unknown.passed); assertEquals(setOf("locationType"), unknown.missingFacts)
    }

    @Test fun assetTypesMatchAndMissingAssetFails() {
        val c = EdgeConditions(assetTypes = listOf("DESKTOP", "SERVER"))
        val ok = eval(c, incident(assets = listOf("server")))
        assertTrue(ok.passed); assertTrue(ok.matchedAssetType)
        assertFalse(eval(c, incident(assets = listOf("UPS"))).passed)
        val unknown = eval(c, incident(assets = emptyList()))
        assertFalse(unknown.passed); assertEquals(setOf("asset"), unknown.missingFacts)
    }

    @Test fun statusesAnyOfAndUnknownStatusOnlyPassesWhenListed() {
        val c = EdgeConditions(statuses = listOf(IncidentStatus.CONTAINED, IncidentStatus.RESOLVED))
        assertTrue(eval(c, incident(status = IncidentStatus.CONTAINED)).passed)
        assertFalse(eval(c, incident(status = IncidentStatus.ACTIVE)).passed)
        val unknown = eval(c, incident(status = IncidentStatus.UNKNOWN))
        assertFalse(unknown.passed); assertEquals(setOf("status"), unknown.missingFacts)
        assertTrue(eval(EdgeConditions(statuses = IncidentStatus.entries), incident(status = IncidentStatus.UNKNOWN)).passed)
    }

    @Test fun hazardsAnyAllNone() {
        val i = incident(hazards = listOf("SMOKE", "ELECTRICAL_DAMAGE"))
        assertTrue(eval(EdgeConditions(hazardsAny = listOf("FIRE", "smoke")), i).passed)
        assertFalse(eval(EdgeConditions(hazardsAny = listOf("FIRE")), i).passed)
        assertTrue(eval(EdgeConditions(hazardsAll = listOf("SMOKE", "ELECTRICAL_DAMAGE")), i).passed)
        assertFalse(eval(EdgeConditions(hazardsAll = listOf("SMOKE", "FIRE")), i).passed)
        assertTrue(eval(EdgeConditions(hazardsNone = listOf("GAS_LEAK")), i).passed)
        assertFalse(eval(EdgeConditions(hazardsNone = listOf("SMOKE")), i).passed)
    }

    @Test fun minAndMaxSeverity() {
        assertTrue(eval(EdgeConditions(minSeverity = KgSeverity.MEDIUM), incident(severity = KgSeverity.HIGH)).passed)
        assertTrue(eval(EdgeConditions(minSeverity = KgSeverity.HIGH), incident(severity = KgSeverity.HIGH)).passed)
        assertFalse(eval(EdgeConditions(minSeverity = KgSeverity.HIGH), incident(severity = KgSeverity.LOW)).passed)
        assertTrue(eval(EdgeConditions(maxSeverity = KgSeverity.MEDIUM), incident(severity = KgSeverity.LOW)).passed)
        assertFalse(eval(EdgeConditions(maxSeverity = KgSeverity.MEDIUM), incident(severity = KgSeverity.CRITICAL)).passed)
        val unknown = eval(EdgeConditions(minSeverity = KgSeverity.LOW), incident(severity = null))
        assertFalse(unknown.passed); assertEquals(setOf("severity"), unknown.missingFacts)
    }

    @Test fun injuryRequiresTheKnownState() {
        assertTrue(eval(EdgeConditions(injury = true), incident(injury = true)).passed)
        assertFalse(eval(EdgeConditions(injury = true), incident(injury = false)).passed)
        assertTrue(eval(EdgeConditions(injury = false), incident(injury = false)).passed)
        val unknown = eval(EdgeConditions(injury = true), incident(injury = null))
        assertFalse(unknown.passed); assertEquals(setOf("injury"), unknown.missingFacts)
    }

    @Test fun minPeopleAffected() {
        assertTrue(eval(EdgeConditions(minPeopleAffected = 2), incident(people = 3)).passed)
        assertFalse(eval(EdgeConditions(minPeopleAffected = 2), incident(people = 1)).passed)
        assertEquals(setOf("peopleAffected"), eval(EdgeConditions(minPeopleAffected = 1), incident(people = null)).missingFacts)
    }

    @Test fun operationalEffectsAny() {
        assertTrue(eval(EdgeConditions(operationalEffectsAny = listOf("ROOM_UNUSABLE")), incident(effects = listOf("room_unusable"))).passed)
        assertFalse(eval(EdgeConditions(operationalEffectsAny = listOf("ROOM_UNUSABLE")), incident(effects = listOf("SERVICE_OUTAGE"))).passed)
    }

    @Test fun minEvidenceConfidence() {
        assertTrue(eval(EdgeConditions(minEvidenceConfidence = 0.8), incident(evidence = 0.9)).passed)
        assertFalse(eval(EdgeConditions(minEvidenceConfidence = 0.8), incident(evidence = 0.5)).passed)
    }

    @Test fun everyFailureGivesAReasonAndConditionsCombineWithAnd() {
        val r = eval(EdgeConditions(locationTypes = listOf("CLASSROOM"), minSeverity = KgSeverity.CRITICAL))
        assertFalse(r.passed)
        assertEquals(2, r.reasons.size)
        assertTrue(r.reason.contains("location type") && r.reason.contains("severity"))
    }
}
