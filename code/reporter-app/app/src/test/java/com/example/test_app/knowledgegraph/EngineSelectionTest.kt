package com.example.test_app.knowledgegraph

import com.example.test_app.knowledgegraph.engine.EngineConfig
import com.example.test_app.knowledgegraph.engine.PhaseLimits
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.EdgeConditions
import com.example.test_app.knowledgegraph.model.KgSeverity
import com.example.test_app.knowledgegraph.model.Likelihood
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Phase
import com.example.test_app.knowledgegraph.model.Relation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec section 12: ordering, prerequisites, limits, de-duplication, paths, consequences and arrangements. */
class EngineSelectionTest {
    private fun base(): PackBuilder = PackBuilder().incidentType("FIRE").team("ops")

    @Test fun safetyOrderBeatsAHigherScore() {
        val b = base()
            .action("restore", phase = "IMMEDIATE", orderClass = "RESTORATION").rec("restore").trigger("FIRE", "restore", priority = 100)
            .action("evacuate", phase = "IMMEDIATE", orderClass = "LIFE_SAFETY").rec("evacuate").trigger("FIRE", "evacuate", priority = 10)
            .action("isolate", phase = "IMMEDIATE", orderClass = "HAZARD_ISOLATION").rec("isolate").trigger("FIRE", "isolate", priority = 30)
            .action("escalate", phase = "IMMEDIATE", orderClass = "RESPONDER_ESCALATION").rec("escalate").trigger("FIRE", "escalate", priority = 20)
        assertEquals(listOf("rec:evacuate", "rec:isolate", "rec:escalate"), b.run().ids())
    }

    @Test fun theFullOrderingChainIsFollowed() {
        val classes = listOf("PREVENTION", "RESTORATION", "EVIDENCE_PRESERVATION", "SERVICE_CONTINUITY", "RESPONDER_ESCALATION", "HAZARD_ISOLATION", "LIFE_SAFETY")
        val b = base()
        classes.forEach { c -> b.action(c.lowercase(), phase = "OPERATIONAL", orderClass = c).rec(c.lowercase()).trigger("FIRE", c.lowercase()) }
        val cfg = EngineConfig(limits = PhaseLimits(operational = 10))
        assertEquals(classes.reversed().map { "rec:" + it.lowercase() }, b.run(config = cfg).ids())
    }

    @Test fun withinAClassTheHigherGraphPriorityWins() {
        val b = base().action("a").rec("a").trigger("FIRE", "a", priority = 40).action("b").rec("b").trigger("FIRE", "b", priority = 90)
        assertEquals(listOf("rec:b", "rec:a"), b.run().ids())
    }

    @Test fun aPrerequisiteIsPlacedBeforeItsDependentEvenWithALowerScore() {
        val b = base()
            .action("inspect", orderClass = "RESTORATION", phase = "OPERATIONAL").rec("inspect").trigger("FIRE", "inspect", priority = 20)
            .action("clearance", orderClass = "LIFE_SAFETY", phase = "OPERATIONAL").rec("clearance").trigger("FIRE", "clearance", priority = 100)
            .edge("action:clearance", Relation.DEPENDS_ON, "action:inspect")
        assertEquals(listOf("rec:inspect", "rec:clearance"), b.run().operationalSteps.map { it.id })
    }

    @Test fun aDependentWhosePrerequisiteIsNotAvailableIsSuppressed() {
        val b = base()
            .action("clearance").rec("clearance").trigger("FIRE", "clearance")
            .action("inspect").rec("inspect", status = ApprovalStatus.DRAFT).trigger("FIRE", "inspect")
            .edge("action:clearance", Relation.DEPENDS_ON, "action:inspect")
        val set = b.run()
        assertTrue(set.ids().isEmpty())
        assertTrue(set.whyNot("rec:clearance")!!.contains("prerequisite"))
        // for the admin preview the draft prerequisite exists, so the dependent is shown after it
        assertEquals(listOf("rec:inspect", "rec:clearance"), b.run(audience = com.example.test_app.knowledgegraph.model.Audience.ADMIN_PREVIEW).ids())
    }

    @Test fun aPrerequisiteThatDoesNotFitTheLimitsAlsoRemovesItsDependent() {
        val b = base()
            .action("first", orderClass = "LIFE_SAFETY").rec("first").trigger("FIRE", "first", priority = 90)
            .action("dependent", orderClass = "LIFE_SAFETY").rec("dependent").trigger("FIRE", "dependent", priority = 80)
            .action("pre", orderClass = "RESTORATION").rec("pre").trigger("FIRE", "pre", priority = 10)
            .edge("action:dependent", Relation.DEPENDS_ON, "action:pre")
        val set = b.run(config = EngineConfig(limits = PhaseLimits(immediate = 2)))
        // order: first (life safety), pre (needed by dependent), dependent -> the two slots go to first and pre; dependent does not fit
        assertEquals(listOf("rec:first", "rec:pre"), set.ids())
        assertTrue(set.suppressed.any { it.candidateId == "rec:dependent" })
    }

    @Test fun aPrerequisiteCycleIsSuppressedWithAReason() {
        val b = base().action("a").rec("a").trigger("FIRE", "a").action("b").rec("b").trigger("FIRE", "b")
            .edge("action:a", Relation.DEPENDS_ON, "action:b").edge("action:b", Relation.DEPENDS_ON, "action:a")
            .action("c").rec("c").trigger("FIRE", "c")
        val set = b.run()
        assertEquals(listOf("rec:c"), set.ids())
        assertTrue(set.suppressed.any { it.reason.contains("cycle") || it.reason.contains("prerequisite") })
    }

    @Test fun outputLimitsPerPhaseAreEnforcedAndTheRestIsLogged() {
        val b = base()
        for (i in 1..5) b.action("i$i", phase = "IMMEDIATE").rec("i$i").trigger("FIRE", "i$i", priority = 50 + i)
        for (i in 1..5) b.action("o$i", phase = "OPERATIONAL").rec("o$i").trigger("FIRE", "o$i", priority = 50 + i)
        for (i in 1..5) b.action("r$i", phase = "RECOVERY").rec("r$i").trigger("FIRE", "r$i", priority = 50 + i)
        for (i in 1..5) b.action("p$i", phase = "PREVENTION").rec("p$i").trigger("FIRE", "p$i", priority = 50 + i)
        val set = b.run()
        assertEquals(3, set.immediateActions.size); assertEquals(3, set.operationalSteps.size); assertEquals(3, set.recoverySteps.size); assertEquals(2, set.preventiveActions.size)
        assertEquals(listOf("rec:i5", "rec:i4", "rec:i3"), set.immediateActions.map { it.id })
        assertEquals(listOf(1, 2, 3), set.immediateActions.map { it.rank })
        assertEquals(9, set.suppressed.count { it.reason.contains("phase limit") })
        assertTrue(set.unresolvedQuestions.size <= 1)
        assertEquals(set.allRecommendations.map { it.id }.toSet(), set.recommendationIds.toSet())
    }

    @Test fun semanticallyEquivalentActionsAreDeduplicatedByCanonicalKey() {
        val b = base()
            .action("alert-a", key = "ALERT_SECURITY").rec("alert-a").trigger("FIRE", "alert-a", priority = 40)
            .action("alert-b", key = "ALERT_SECURITY").rec("alert-b").trigger("FIRE", "alert-b", priority = 90)
            .action("other", key = "OTHER").rec("other").trigger("FIRE", "other", priority = 10)
        val set = b.run()
        assertEquals(listOf("rec:alert-b", "rec:other"), set.ids())
        assertTrue(set.whyNot("rec:alert-a")!!.contains("duplicate of rec:alert-b"))
    }

    @Test fun anActionReachedByTwoPathsAppearsOnceWithBothPaths() {
        val b = base().hazard("SMOKE").consequence("blocked-exit").action("shared").rec("shared")
            .trigger("FIRE", "shared", priority = 40)
            .edge("incident-type:FIRE", Relation.PRODUCES_HAZARD, "hazard:SMOKE")
            .edge("hazard:SMOKE", Relation.MAY_CAUSE, "consequence:blocked-exit")
            .edge("consequence:blocked-exit", Relation.REQUIRES_ACTION, "action:shared", priority = 80)
        val item = b.run().allRecommendations.single()
        assertEquals(2, item.provenance.size)
        assertTrue(item.provenance.any { it.contains("MAY_CAUSE") })
    }

    @Test fun onlyWhitelistedPathsProduceCandidates() {
        // an action linked from the incident type by a relation that is not whitelisted must not appear
        val b = base().action("sneaky").rec("sneaky").edge("incident-type:FIRE", Relation.ESCALATES_TO, "action:sneaky")
        assertTrue(b.run().ids().isEmpty())
        val b2 = base().locationType("COMPUTER_LAB").action("viaLocation").rec("viaLocation").edge("location-type:COMPUTER_LAB", Relation.REQUIRES_ACTION, "action:viaLocation")
        assertTrue(b2.run().ids().isEmpty())
    }

    @Test fun assetTypePathProducesAnActionWhenTheAssetIsAffected() {
        val b = base().assetType("DESKTOP").action("isolate-asset").rec("isolate-asset").edge("asset-type:DESKTOP", Relation.REQUIRES_ACTION, "action:isolate-asset", priority = 90)
        assertEquals(listOf("rec:isolate-asset"), b.run(incident(assets = listOf("DESKTOP"))).ids())
        assertTrue(b.run(incident(assets = emptyList())).ids().isEmpty())
        assertTrue(b.run(incident(assets = listOf("DESKTOP"))).allRecommendations.single().provenance.single().startsWith("asset-type:DESKTOP"))
    }

    @Test fun consequencesAreTaggedAndNeverCertainUnlessObserved() {
        val b = base().hazard("SMOKE").hazard("ELECTRICAL_DAMAGE")
            .consequence("smoke-spread", "Smoke may spread to nearby rooms.", "SECONDARY")
            .consequence("room-closed", "The room could be unusable for a while.", "OPERATIONAL", observedWhenEffect = "ROOM_UNUSABLE")
            .consequence("equipment", "Equipment could be damaged.", "ASSET")
            .edge("incident-type:FIRE", Relation.PRODUCES_HAZARD, "hazard:SMOKE").edge("incident-type:FIRE", Relation.PRODUCES_HAZARD, "hazard:ELECTRICAL_DAMAGE")
            .edge("hazard:SMOKE", Relation.MAY_CAUSE, "consequence:smoke-spread").edge("hazard:SMOKE", Relation.MAY_CAUSE, "consequence:room-closed")
            .edge("hazard:ELECTRICAL_DAMAGE", Relation.MAY_CAUSE, "consequence:equipment")
        val quiet = b.run(incident(hazards = emptyList()))
        assertTrue(quiet.consequences.all { it.likelihood == Likelihood.POSSIBLE })
        val seen = b.run(incident(hazards = listOf("SMOKE"), effects = listOf("ROOM_UNUSABLE")))
        assertEquals(Likelihood.CONFIRMED, seen.consequences.first { it.id == "consequence:room-closed" }.likelihood)
        assertEquals(Likelihood.LIKELY, seen.consequences.first { it.id == "consequence:smoke-spread" }.likelihood)
        assertEquals(Likelihood.POSSIBLE, seen.consequences.first { it.id == "consequence:equipment" }.likelihood)
        assertEquals(Likelihood.CONFIRMED, seen.consequences.first().likelihood) // most certain first
        assertTrue(seen.consequences.all { it.provenance.single().contains("PRODUCES_HAZARD") })
    }

    @Test fun consequencesWithMedicalTextAreSuppressed() {
        val b = base().hazard("SMOKE").consequence("bad", "The person has a sprained ankle.").edge("incident-type:FIRE", Relation.PRODUCES_HAZARD, "hazard:SMOKE")
            .edge("hazard:SMOKE", Relation.MAY_CAUSE, "consequence:bad")
        val set = b.run()
        assertTrue(set.consequences.isEmpty()); assertTrue(set.suppressed.any { it.kind == "consequence" })
    }

    @Test fun theConsequenceListIsCapped() {
        val b = base().hazard("SMOKE").edge("incident-type:FIRE", Relation.PRODUCES_HAZARD, "hazard:SMOKE")
        for (i in 1..7) b.consequence("c$i").edge("hazard:SMOKE", Relation.MAY_CAUSE, "consequence:c$i")
        assertEquals(4, b.run().consequences.size)
    }

    @Test fun edgeConditionsDecideWhatIsReachable() {
        val b = base()
            .action("recovery", phase = "RECOVERY").rec("recovery").trigger("FIRE", "recovery", EdgeConditions(statuses = listOf(com.example.test_app.knowledgegraph.model.IncidentStatus.CONTAINED, com.example.test_app.knowledgegraph.model.IncidentStatus.RESOLVED)))
            .action("serious").rec("serious").trigger("FIRE", "serious", EdgeConditions(minSeverity = KgSeverity.HIGH))
        assertTrue(b.run(incident(status = com.example.test_app.knowledgegraph.model.IncidentStatus.ACTIVE, severity = KgSeverity.LOW)).ids().isEmpty())
        val resolved = b.run(incident(status = com.example.test_app.knowledgegraph.model.IncidentStatus.RESOLVED, severity = KgSeverity.HIGH))
        assertEquals(setOf("rec:recovery", "rec:serious"), resolved.ids().toSet())
        assertTrue(b.run(incident(status = com.example.test_app.knowledgegraph.model.IncidentStatus.ACTIVE, severity = KgSeverity.LOW)).whyNot("rec:recovery")!!.contains("status"))
    }

    @Test fun responsibleTeamsAreTheOwnersOfShownItemsWithoutDuplicates() {
        val b = base().team("it").action("a", team = "team:it").rec("a").trigger("FIRE", "a").action("b", team = "team:it").rec("b").trigger("FIRE", "b")
            .action("c", team = "team:ops").rec("c").trigger("FIRE", "c")
        val set = b.run()
        assertEquals(setOf("team:it", "team:ops"), set.responsibleTeams.map { it.id }.toSet())
        assertEquals(2, set.responsibleTeams.size)
        assertTrue(set.responsibleTeams.all { !it.verified && it.contactNumber == null })
        assertEquals("it", set.immediateActions.first { it.id == "rec:a" }.ownerTeamName)
    }

    @Test fun scoringUsesTheConfiguredWeights() {
        val b = base().action("a").rec("a").trigger("FIRE", "a", priority = 100)
        val normal = b.run().allRecommendations.single().score
        val zero = b.run(config = EngineConfig(weights = com.example.test_app.knowledgegraph.engine.RankingWeights(graphPriority = 0.0))).allRecommendations.single().score
        assertEquals(0.30, normal - zero, 0.002)
    }

    @Test fun temporaryArrangementsAreLimitedToTwoAndRequireASource() {
        val b = base().locationType("CLASSROOM")
        for (i in 1..3) {
            b.node("arrangement:t$i", NodeType.TEMPORARY_ARRANGEMENT, "t$i", props("title" to "Arrangement $i", "text" to "Ask the academic office to find another suitable room.", "requirements" to mapOf<String, Any>()))
            b.edge("location-type:CLASSROOM", Relation.HAS_CONTINUITY_OPTION, "arrangement:t$i", priority = 90 - i, sourceRef = "sop:ok")
        }
        b.node("arrangement:nosrc", NodeType.TEMPORARY_ARRANGEMENT, "n", props("title" to "x", "text" to "Ask someone.", "requirements" to mapOf<String, Any>()))
        b.edge("location-type:CLASSROOM", Relation.HAS_CONTINUITY_OPTION, "arrangement:nosrc", priority = 99)
        val set = b.run(incident(locationType = "CLASSROOM"))
        assertEquals(listOf("arrangement:t1", "arrangement:t2"), set.temporaryArrangements.map { it.id })
        assertTrue(set.whyNot("arrangement:nosrc")!!.contains("sourceless"))
        assertTrue(set.suppressed.any { it.candidateId == "arrangement:t3" && it.reason.contains("limit") })
    }
}
