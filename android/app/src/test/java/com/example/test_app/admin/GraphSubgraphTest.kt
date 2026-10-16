package com.example.test_app.admin

import com.example.test_app.knowledgegraph.PackBuilder
import com.example.test_app.knowledgegraph.admin.graphview.GraphKinds
import com.example.test_app.knowledgegraph.admin.graphview.GraphSubgraph
import com.example.test_app.knowledgegraph.admin.graphview.GraphViewOptions
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subgraph extraction, filters, local depth and search of the admin graph (pure). */
class GraphSubgraphTest {
    /** FIRE -> a1 -> (owner ops) ; FIRE -> hazard H -> consequence C -> a2 ; a3 draft ; orphan node. */
    private fun pack(): GraphPack = PackBuilder.basic("a1")
        .hazard("H").consequence("c").action("a2").rec("a2").action("a3").rec("a3", status = ApprovalStatus.DRAFT).trigger("FIRE", "a3")
        .edge("incident-type:FIRE", Relation.PRODUCES_HAZARD, "hazard:H").edge("hazard:H", Relation.MAY_CAUSE, "consequence:c")
        .edge("consequence:c", Relation.REQUIRES_ACTION, "action:a2")
        .node("lonely:1", NodeType.UTILITY, "Lonely utility").build()

    private fun build(o: GraphViewOptions = GraphViewOptions(), hl: Set<String> = emptySet()) = GraphSubgraph.build(pack(), o, hl)

    @Test fun everyNodeAndSopSourceIsInTheGlobalGraphWithItsKind() {
        val g = build()
        val p = pack()
        assertEquals(p.nodes.size + p.sources.size, g.nodes.size)
        assertEquals(GraphKinds.SOURCE, g.node("sop:ok")!!.kind)
        assertEquals("INCIDENT_TYPE", g.node("incident-type:FIRE")!!.kind)
        assertFalse(g.truncated)
    }

    @Test fun edgesOnlyConnectVisibleNodesAndDegreeCountsThem() {
        val g = build()
        val ids = g.nodes.map { it.id }.toSet()
        assertTrue(g.edges.all { it.sourceId in ids && it.targetId in ids })
        val fire = g.node("incident-type:FIRE")!!
        assertEquals(g.edges.count { it.sourceId == fire.id || it.targetId == fire.id }, fire.degree)
        assertEquals(0, g.node("lonely:1")!!.degree)
    }

    @Test fun hiddenKindsRemoveNodesAndTheirEdges() {
        val g = build(GraphViewOptions(hiddenKinds = setOf("ACTION", GraphKinds.SOURCE)))
        assertTrue(g.nodes.none { it.kind == "ACTION" || it.kind == GraphKinds.SOURCE })
        assertTrue(g.edges.none { it.relation == Relation.TRIGGERS_ACTION || it.relation == Relation.SUPPORTED_BY })
    }

    @Test fun hiddenRelationsRemoveOnlyEdges() {
        val all = build()
        val g = build(GraphViewOptions(hiddenRelations = setOf(Relation.OWNED_BY)))
        assertTrue(g.edges.none { it.relation == Relation.OWNED_BY }); assertEquals(all.nodes.size, g.nodes.size)
    }

    @Test fun onlyApprovedAndActiveHidesDraftRecommendationsAndInactiveItems() {
        val g = build(GraphViewOptions(onlyApprovedActive = true))
        assertNotNull(g.node("rec:a1")); assertNull(g.node("rec:a3"))
        val p = pack()
        val off = p.copy(nodes = p.nodes.map { if (it.id == "action:a1") it.copy(active = false) else it })
        assertNull(GraphSubgraph.build(off, GraphViewOptions(onlyApprovedActive = true)).node("action:a1"))
        assertNotNull(GraphSubgraph.build(off, GraphViewOptions()).node("action:a1"))
        val draftEdge = p.copy(edges = p.edges.map { if (it.relation == Relation.PRODUCES_HAZARD) it.copy(approvalStatus = ApprovalStatus.DRAFT) else it })
        assertTrue(GraphSubgraph.build(draftEdge, GraphViewOptions(onlyApprovedActive = true)).edges.none { it.relation == Relation.PRODUCES_HAZARD })
    }

    @Test fun orphansCanBeHidden() {
        assertNotNull(build().node("lonely:1"))
        val g = build(GraphViewOptions(showOrphans = false))
        assertNull(g.node("lonely:1")); assertTrue(g.nodes.all { it.degree > 0 })
    }

    @Test fun localDepthExpandsLikeABreadthFirstSearch() {
        fun ids(depth: Int) = build(GraphViewOptions(focusNodeId = "incident-type:FIRE", local = true, localDepth = depth)).nodes.map { it.id }.toSet()
        val d1 = ids(1); val d2 = ids(2); val d3 = ids(3)
        assertTrue("incident-type:FIRE" in d1 && "hazard:H" in d1 && "action:a1" in d1)
        assertFalse("consequence:c" in d1); assertTrue("consequence:c" in d2)
        assertFalse("action:a2" in d2); assertTrue("action:a2" in d3)
        assertTrue(d1.size < d2.size && d2.size < d3.size)
        assertFalse("lonely:1" in d3)
    }

    @Test fun localWithoutAKnownFocusFallsBackToTheGlobalGraph() {
        assertEquals(build().nodes.size, build(GraphViewOptions(focusNodeId = null, local = true)).nodes.size)
        assertEquals(build().nodes.size, build(GraphViewOptions(focusNodeId = "nope", local = true)).nodes.size)
    }

    @Test fun localGraphEdgesStayInsideTheNeighbourhood() {
        val g = build(GraphViewOptions(focusNodeId = "hazard:H", local = true, localDepth = 1))
        assertEquals(setOf("hazard:H", "incident-type:FIRE", "consequence:c"), g.nodes.map { it.id }.toSet())
        assertTrue(g.edges.all { it.sourceId in g.nodeIds && it.targetId in g.nodeIds })
    }

    @Test fun searchMarksMatchesByNameIdCodeAndTitleWithoutRemovingOthers() {
        val g = build(GraphViewOptions(query = "ConSEQ"))
        assertEquals(setOf("consequence:c"), g.matchedIds)
        assertEquals(build().nodes.size, g.nodes.size)
        assertTrue(build(GraphViewOptions(query = "fire")).matchedIds.contains("incident-type:FIRE")) // by code
        assertTrue(build(GraphViewOptions(query = "zzzz")).matchedIds.isEmpty())
        assertTrue(build(GraphViewOptions(query = "   ")).matchedIds.isEmpty())
        assertTrue(build(GraphViewOptions(query = "sop:ok")).matchedIds.contains("sop:ok"))
    }

    @Test fun theNodeCapKeepsTheFocusMatchesAndHighlightsThenTheBusiest() {
        val g = build(GraphViewOptions(maxNodes = 5, focusNodeId = "lonely:1", query = "a3"), hl = setOf("hazard:H"))
        assertTrue(g.truncated); assertEquals(5, g.nodes.size)
        assertTrue(g.node("lonely:1") != null && g.node("hazard:H") != null && g.node("action:a3") != null)
        assertTrue(g.edges.all { it.sourceId in g.nodeIds && it.targetId in g.nodeIds })
    }

    @Test fun highlightedNodesStayVisibleEvenWhenTheirKindIsHidden() {
        val g = build(GraphViewOptions(hiddenKinds = setOf("HAZARD")), hl = setOf("hazard:H"))
        assertNotNull(g.node("hazard:H"))
        assertNull(build(GraphViewOptions(hiddenKinds = setOf("HAZARD"))).node("hazard:H"))
    }

    @Test fun neighboursListOutgoingThenIncomingEdges() {
        val g = build()
        val n = GraphSubgraph.neighbours(g, "hazard:H")
        assertEquals(listOf("consequence:c", "incident-type:FIRE"), n.map { it.second.id })
        assertEquals(listOf(Relation.MAY_CAUSE, Relation.PRODUCES_HAZARD), n.map { it.first.relation })
        assertTrue(GraphSubgraph.neighbours(g, "lonely:1").isEmpty())
    }

    @Test fun edgeIndexPairsMatchTheNodeOrder() {
        val g = build()
        val pairs = g.edgeIndexPairs()
        assertEquals(g.edges.size, pairs.size)
        val e = g.edges.first()
        assertTrue((g.index(e.sourceId) to g.index(e.targetId)) in pairs)
        assertEquals(-1, g.index("missing"))
    }

    @Test fun labelsUseTheTitleThenTheNameThenTheId() {
        val p = pack().copy(nodes = pack().nodes + com.example.test_app.knowledgegraph.model.KgNode("x:1", NodeType.UTILITY, ""))
        val g = GraphSubgraph.build(p, GraphViewOptions())
        assertEquals("x:1", g.node("x:1")!!.label)
        assertEquals("a1", g.node("action:a1")!!.label)
        assertEquals("Approved SOP", g.node("sop:ok")!!.label)
    }
}
