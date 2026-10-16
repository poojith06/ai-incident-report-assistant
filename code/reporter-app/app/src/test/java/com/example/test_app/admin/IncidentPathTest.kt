package com.example.test_app.admin

import com.example.test_app.admin.AdminTestSupport.auditOf
import com.example.test_app.admin.AdminTestSupport.scenarioIncident
import com.example.test_app.admin.AdminTestSupport.seedPack
import com.example.test_app.knowledgegraph.PackBuilder
import com.example.test_app.knowledgegraph.admin.graphview.GraphSubgraph
import com.example.test_app.knowledgegraph.admin.graphview.GraphViewOptions
import com.example.test_app.knowledgegraph.admin.graphview.IncidentPath
import com.example.test_app.knowledgegraph.admin.graphview.PathInput
import com.example.test_app.knowledgegraph.incident
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The incident-path highlight is derived from the engine's own provenance (ADR-058). */
class IncidentPathTest {
    @Test fun aProvenanceStringIsParsedIntoNodesAndHops() {
        val (ids, hops) = IncidentPath.parseProvenance("incident-type:fire -PRODUCES_HAZARD-> hazard:smoke -MAY_CAUSE-> consequence:spread -REQUIRES_ACTION-> action:evacuate [edge:x>y]")
        assertEquals(listOf("incident-type:fire", "hazard:smoke", "consequence:spread", "action:evacuate"), ids)
        assertEquals(
            listOf(Triple("incident-type:fire", Relation.PRODUCES_HAZARD, "hazard:smoke"), Triple("hazard:smoke", Relation.MAY_CAUSE, "consequence:spread"), Triple("consequence:spread", Relation.REQUIRES_ACTION, "action:evacuate")),
            hops,
        )
    }

    @Test fun oddProvenanceDoesNotCrash() {
        assertEquals(emptyList<String>() to emptyList<Any>(), IncidentPath.parseProvenance("   "))
        assertEquals(listOf("only-a-node"), IncidentPath.parseProvenance("only-a-node").first)
        assertTrue(IncidentPath.parseProvenance("a -NOT_A_RELATION-> b").second.isEmpty())
    }

    @Test fun theHighlightOfASmallGraphHasTheTraversedNodesEdgesSourceAndOwner() {
        val b = PackBuilder.basic("a1", "a2")
        val set = b.run(incident())
        val i = incident()
        val h = IncidentPath.fromPreview(b.build(), i, set)
        assertTrue("incident-type:FIRE" in h.nodeIds); assertEquals("incident-type:FIRE", h.rootNodeId)
        assertTrue(setOf("action:a1", "rec:a1", "action:a2", "rec:a2", "sop:ok", "team:ops").all { it in h.nodeIds })
        val trigger = b.build().edges.filter { it.relation == Relation.TRIGGERS_ACTION }.map { it.id }
        assertTrue(h.edgeIds.containsAll(trigger))
        assertTrue(b.build().edges.filter { it.relation == Relation.SUPPORTED_BY }.all { it.id in h.edgeIds })
        assertTrue(b.build().edges.filter { it.relation == Relation.OWNED_BY }.all { it.id in h.edgeIds })
    }

    @Test fun suppressedCandidatesAreSeparateFromTheTraversedPath() {
        val b = PackBuilder.basic("ok").action("draft").rec("draft", status = ApprovalStatus.DRAFT).trigger("FIRE", "draft")
        val set = b.run(incident())
        val h = IncidentPath.fromPreview(b.build(), incident(), set)
        assertTrue("action:draft" in h.suppressedNodeIds || "rec:draft" in h.suppressedNodeIds)
        assertTrue(h.suppressedNodeIds.none { it in h.nodeIds })
    }

    @Test fun suppressedEdgeIdsBecomeSuppressedEdges() {
        val pack = PackBuilder.basic("ok").build()
        val edgeId = pack.edges.first { it.relation == Relation.TRIGGERS_ACTION }.id
        val h = IncidentPath.derive(pack, PathInput(null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(edgeId, "edge:ghost", "node:ghost")))
        assertEquals(setOf(edgeId), h.suppressedEdgeIds); assertTrue(h.suppressedNodeIds.isEmpty()); assertTrue(h.nodeIds.isEmpty())
    }

    @Test fun anEmptyAuditGivesAnEmptyHighlight() {
        val h = IncidentPath.derive(PackBuilder.basic("a").build(), PathInput(null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        assertTrue(h.isEmpty); assertEquals(null, h.rootNodeId)
    }

    // ---- with the real seed pack and an audit log (the "Show in graph" path) ---------------------------------------

    @Test fun theComputerFireAuditHighlightsTheWholePathInTheSeedGraph() {
        val log = auditOf(scenarioIncident(2))
        val h = IncidentPath.fromAudit(seedPack, log)
        val byId = seedPack.nodes.associateBy { it.id }
        assertEquals("incident-type:computer-fire", h.rootNodeId)
        assertTrue("location type", h.nodeIds.any { byId[it]?.str("code") == "COMPUTER_LAB" })
        assertTrue("asset type", h.nodeIds.any { byId[it]?.str("code") == "DESKTOP" })
        assertTrue("hazard", h.nodeIds.any { byId[it]?.type?.name == "HAZARD" })
        assertTrue("consequence", h.nodeIds.any { byId[it]?.type?.name == "CONSEQUENCE" })
        assertTrue("action", h.nodeIds.any { byId[it]?.type?.name == "ACTION" })
        assertTrue("team", h.nodeIds.any { byId[it]?.type?.name == "RESPONSE_TEAM" })
        assertTrue("source", "sop:demo-seed:v1" in h.nodeIds)
        assertTrue(h.edgeIds.isNotEmpty())
        // every highlighted edge connects highlighted nodes
        val edges = seedPack.edges.associateBy { it.id }
        for (id in h.edgeIds) { val e = edges.getValue(id); assertTrue(id, e.sourceId in h.nodeIds && e.targetId in h.nodeIds) }
        // every shown recommendation is highlighted
        assertTrue(log.recommendationIds.all { it in h.nodeIds })
    }

    @Test fun theHighlightShowsExactlyWhatWasShownNotTheWholeGraph() {
        val h = IncidentPath.fromAudit(seedPack, auditOf(scenarioIncident(2)))
        assertTrue(h.nodeIds.size < seedPack.nodes.size / 3)
        val other = IncidentPath.fromAudit(seedPack, auditOf(scenarioIncident(8)))
        assertFalse(other.nodeIds == h.nodeIds)
        assertEquals("incident-type:sports-injury", other.rootNodeId)
    }

    @Test fun theSeedSuppressionsAppearAsWarningCandidates() {
        val h = IncidentPath.fromAudit(seedPack, auditOf(scenarioIncident(2)))
        assertTrue(h.suppressedNodeIds.isNotEmpty() || h.suppressedEdgeIds.isNotEmpty())
        assertTrue(h.suppressedNodeIds.none { it in h.nodeIds } && h.suppressedEdgeIds.none { it in h.edgeIds })
    }

    @Test fun theAdminPreviewPathIncludesTheDraftRecommendations() {
        val i = scenarioIncident(2)
        val reporter = IncidentPath.fromPreview(seedPack, i, AdminTestSupport.engine().recommend(i, Audience.REPORTER))
        val admin = IncidentPath.fromPreview(seedPack, i, AdminTestSupport.engine().recommend(i, Audience.ADMIN_PREVIEW))
        assertTrue(admin.nodeIds.containsAll(reporter.nodeIds)); assertTrue(admin.nodeIds.size > reporter.nodeIds.size)
    }

    @Test fun theBuilderKeepsTheHighlightedNodesEvenWithHiddenKindsAndTheyAreInTheGraph() {
        val h = IncidentPath.fromAudit(seedPack, auditOf(scenarioIncident(2)))
        val g = GraphSubgraph.build(seedPack, GraphViewOptions(hiddenKinds = setOf("ACTION", "SOURCE")), h.nodeIds)
        assertTrue(h.nodeIds.all { g.node(it) != null })
    }

    @Test fun anOldAuditLogWithoutNewFieldsStillWorks() {
        // the audit schema was not changed: a log written by the engine round-trips and still yields a path
        val log = auditOf(scenarioIncident(1))
        val json = com.example.test_app.knowledgegraph.admin.GraphPackCodec.pretty.encodeToString(com.example.test_app.knowledgegraph.model.RecommendationLog.serializer(), log)
        val back = com.example.test_app.knowledgegraph.admin.GraphPackCodec.pretty.decodeFromString(com.example.test_app.knowledgegraph.model.RecommendationLog.serializer(), json)
        assertEquals(IncidentPath.fromAudit(seedPack, log), IncidentPath.fromAudit(seedPack, back))
    }
}
