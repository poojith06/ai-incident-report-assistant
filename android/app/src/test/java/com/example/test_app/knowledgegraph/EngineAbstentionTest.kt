package com.example.test_app.knowledgegraph

import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.EdgeConditions
import com.example.test_app.knowledgegraph.model.IncidentStatus
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec sections 3, 12 and 20: unknown facts cause abstention and ONE unresolved question, never invention. */
class EngineAbstentionTest {
    private fun base(): PackBuilder = PackBuilder().incidentType("FIRE").locationType("COMPUTER_LAB").assetType("DESKTOP").team("ops")

    @Test fun anUnknownIncidentTypeAbstainsWithAQuestion() {
        val set = base().action("a").rec("a").trigger("FIRE", "a").run(incident(type = null))
        assertTrue(set.abstained); assertTrue(set.ids().isEmpty())
        assertEquals("q:incidentType", set.unresolvedQuestions.single().id)
    }

    @Test fun anIncidentTypeThatIsNotInTheGraphAbstainsWithAWarning() {
        val set = base().action("a").rec("a").trigger("FIRE", "a").run(incident(type = "UNICORN_SIGHTING"))
        assertTrue(set.abstained); assertTrue(set.warnings.single().contains("UNICORN_SIGHTING"))
    }

    @Test fun lowEvidenceAbstainsAndAsksToConfirm() {
        val set = base().action("a").rec("a").trigger("FIRE", "a").run(incident(evidence = 0.1))
        assertTrue(set.abstained); assertEquals("q:evidence", set.unresolvedQuestions.single().id)
    }

    /** Required: unknown location abstention. */
    @Test fun unknownLocationTypeSuppressesLocationConditionedGuidanceAndAsksOnce() {
        val b = base().action("lab-only").rec("lab-only").trigger("FIRE", "lab-only", EdgeConditions(locationTypes = listOf("COMPUTER_LAB")), priority = 80)
            .action("anywhere").rec("anywhere").trigger("FIRE", "anywhere")
        val set = b.run(incident(locationType = null))
        assertEquals(listOf("rec:anywhere"), set.ids())
        assertTrue(set.whyNot("action:lab-only")!!.contains("location type unknown"))
        assertEquals("q:locationType", set.unresolvedQuestions.single().id)
        assertFalse(set.abstained)
    }

    /** Required: unknown asset abstention. */
    @Test fun unknownAssetSuppressesAssetConditionedGuidanceAndAsksOnce() {
        val b = base().action("pc").rec("pc").trigger("FIRE", "pc", EdgeConditions(assetTypes = listOf("DESKTOP")), priority = 95)
        val set = b.run(incident(assets = emptyList()))
        assertTrue(set.ids().isEmpty()); assertTrue(set.whyNot("action:pc")!!.contains("asset unknown"))
        assertEquals("q:asset", set.unresolvedQuestions.single().id)
        assertEquals(listOf("rec:pc"), b.run(incident(assets = listOf("DESKTOP"))).ids())
    }

    @Test fun anUnknownStatusFailsStatusConditionedEdgesAndAsksAboutTheStatus() {
        val b = base().action("rec1", phase = "RECOVERY").rec("rec1").trigger("FIRE", "rec1", EdgeConditions(statuses = listOf(IncidentStatus.CONTAINED, IncidentStatus.RESOLVED)))
        val set = b.run(incident(status = IncidentStatus.UNKNOWN))
        assertTrue(set.ids().isEmpty()); assertEquals("q:status", set.unresolvedQuestions.single().id)
    }

    @Test fun onlyTheHighestPriorityQuestionIsReturned() {
        val b = base()
            .action("a").rec("a").trigger("FIRE", "a", EdgeConditions(locationTypes = listOf("COMPUTER_LAB")), priority = 60)
            .action("b").rec("b").trigger("FIRE", "b", EdgeConditions(assetTypes = listOf("DESKTOP")), priority = 90)
        val set = b.run(incident(locationType = null, assets = emptyList()))
        assertEquals(1, set.unresolvedQuestions.size)
        assertEquals("q:asset", set.unresolvedQuestions.single().id)
    }

    @Test fun noQuestionWhenNothingNeededIsMissing() {
        assertTrue(base().action("a").rec("a").trigger("FIRE", "a").run().unresolvedQuestions.isEmpty())
    }

    @Test fun aLocationTypeThatIsNotInTheGraphIsAWarningNotAGuess() {
        val set = base().action("a").rec("a").trigger("FIRE", "a").run(incident(locationType = "MOON_BASE"))
        assertTrue(set.warnings.any { it.contains("MOON_BASE") })
        assertTrue(set.temporaryArrangements.isEmpty())
    }

    /** Required negative test: do not invent a replacement classroom when capacity data is unknown. */
    @Test fun doesNotInventAReplacementClassroomWhenCapacityIsUnknown() {
        val b = base().locationType("CLASSROOM")
            .node("arrangement:move", NodeType.TEMPORARY_ARRANGEMENT, "move", props("title" to "Move the class", "text" to "Move the session to the alternate room.", "requirements" to mapOf("knownCapacity" to true, "alternateLocationVerified" to true)))
            .edge("location-type:CLASSROOM", Relation.HAS_CONTINUITY_OPTION, "arrangement:move", priority = 90, sourceRef = "sop:ok")
        val none = b.run(incident(locationType = "CLASSROOM", locationId = "building:nilgiri"))
        assertTrue(none.temporaryArrangements.isEmpty())
        assertTrue(none.whyNot("arrangement:move")!!.contains("capacity data unknown"))
    }

    @Test fun anArrangementThatNeedsCapacityAppearsOnlyWhenTheCampusDataHasIt() {
        fun withData(capacity: Int?, verifiedAlternate: Boolean) = base().locationType("CLASSROOM")
            .node("arrangement:move", NodeType.TEMPORARY_ARRANGEMENT, "move", props("title" to "Move the class", "text" to "Move the session to the alternate room.", "requirements" to mapOf("knownCapacity" to true, "alternateLocationVerified" to true)))
            .edge("location-type:CLASSROOM", Relation.HAS_CONTINUITY_OPTION, "arrangement:move", priority = 90, sourceRef = "sop:ok")
            .node("building:nilgiri", NodeType.BUILDING, "Nilgiri", props("capacity" to capacity))
            .node("room:alt", NodeType.ROOM, "Alternate", props("alternateForLocationTypes" to listOf("CLASSROOM")), if (verifiedAlternate) VerificationStatus.VERIFIED else VerificationStatus.NEEDS_ADMIN_VERIFICATION)
        val i = incident(locationType = "CLASSROOM", locationId = "building:nilgiri")
        assertEquals(listOf("arrangement:move"), withData(60, true).run(i).temporaryArrangements.map { it.id })
        assertTrue(withData(60, false).run(i).temporaryArrangements.isEmpty())
        assertTrue(withData(null, true).run(i).temporaryArrangements.isEmpty())
    }

    @Test fun unknownLocationTypeGivesNoArrangementAndTheQuestion() {
        val b = base().locationType("CLASSROOM")
            .node("arrangement:ask", NodeType.TEMPORARY_ARRANGEMENT, "ask", props("title" to "Ask", "text" to "Ask the academic office to find another room.", "requirements" to mapOf<String, Any>()))
            .edge("location-type:CLASSROOM", Relation.HAS_CONTINUITY_OPTION, "arrangement:ask", sourceRef = "sop:ok")
        val set = b.run(incident(locationType = null))
        assertTrue(set.temporaryArrangements.isEmpty()); assertEquals("q:locationType", set.unresolvedQuestions.single().id)
        assertEquals(listOf("arrangement:ask"), b.run(incident(locationType = "CLASSROOM")).temporaryArrangements.map { it.id })
    }

    @Test fun anAdminPreviewStillAbstainsForMissingFacts() {
        val set = base().action("pc").rec("pc").trigger("FIRE", "pc", EdgeConditions(assetTypes = listOf("DESKTOP"))).run(incident(assets = emptyList()), Audience.ADMIN_PREVIEW)
        assertTrue(set.ids().isEmpty()); assertNull(set.guidanceLabel)
    }
}
