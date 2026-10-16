package com.example.test_app.admin

import com.example.test_app.knowledgegraph.PackBuilder
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.KgSeverity
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.SourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field validation of the Knowledge editor forms, the list rows and the preview choices (pure). */
class EditorFormsTest {
    private val pack = PackBuilder.basic("a").build()

    private fun <T> FormResult<T>.ok(): T = (this as FormResult.Ok).value
    private fun FormResult<*>.errors(): Map<String, String> = (this as FormResult.Invalid).errors

    @Test fun aValidNodeDraftBuildsANode() {
        val n = EditorForms.node(NodeDraft("hazard:new", "HAZARD", " New hazard ", """{"code": "NEW", "activeRisk": true}"""), pack).ok()
        assertEquals("hazard:new", n.id); assertEquals(NodeType.HAZARD, n.type); assertEquals("New hazard", n.name); assertEquals("NEW", n.str("code")); assertEquals(true, n.bool("activeRisk")); assertTrue(n.active)
    }

    @Test fun nodeFieldErrorsAreNamedPerField() {
        val e = EditorForms.node(NodeDraft(id = "", type = "NOPE", name = " ", propertiesJson = "[1]", verification = "MAYBE")).errors()
        assertEquals(setOf("id", "type", "name", "properties", "verification"), e.keys)
        assertEquals("id", EditorForms.node(NodeDraft(id = "bad id!", name = "x")).errors().keys.single())
        assertEquals(setOf("properties"), EditorForms.node(NodeDraft("a:b", "ACTION", "x", "{ broken")).errors().keys)
    }

    @Test fun aDuplicateNodeIdIsRefusedForNewButAllowedWhenEditing() {
        val d = NodeDraft("action:a", "ACTION", "x")
        assertEquals(setOf("id"), EditorForms.node(d, pack, editing = false).errors().keys)
        assertEquals("action:a", EditorForms.node(d, pack, editing = true).ok().id)
        assertEquals("{}", NodeDraft().propertiesJson); assertTrue(EditorForms.node(NodeDraft("n:1", "ACTION", "x", ""), pack).ok().properties.isEmpty())
    }

    @Test fun anEdgeDraftIsCheckedAgainstThePack() {
        val ok = EditorForms.edge(EdgeDraft("edge:new", "incident-type:FIRE", "TRIGGERS_ACTION", "action:a", "70", """{"minSeverity": "HIGH", "locationTypes": ["LAB"]}""", "sop:ok", "APPROVED"), pack).ok()
        assertEquals(Relation.TRIGGERS_ACTION, ok.relation); assertEquals(70, ok.priority); assertEquals(KgSeverity.HIGH, ok.conditions.minSeverity); assertEquals(listOf("LAB"), ok.conditions.locationTypes)
        assertEquals("sop:ok", ok.sourceRef); assertEquals(ApprovalStatus.APPROVED, ok.approvalStatus)
        val bad = EditorForms.edge(EdgeDraft("edge:x", "ghost", "TRIGGERS_ACTION", "ghost2", "500", """{"nope": 1}""", "sop:ghost"), pack).errors()
        assertEquals(setOf("sourceId", "targetId", "priority", "conditions", "sourceRef"), bad.keys)
    }

    @Test fun supportedByEdgesPointAtSourcesNotNodes() {
        assertTrue(EditorForms.edge(EdgeDraft("edge:s", "rec:a", "SUPPORTED_BY", "sop:ok"), pack).let { it is FormResult.Ok })
        assertEquals(setOf("targetId"), EditorForms.edge(EdgeDraft("edge:s", "rec:a", "SUPPORTED_BY", "action:a"), pack).errors().keys)
    }

    @Test fun edgeIdAndEmptyConditionsAndPriorityRules() {
        assertEquals(setOf("id"), EditorForms.edge(EdgeDraft("", "rec:a", "SUPPORTED_BY", "sop:ok"), pack).errors().keys)
        val dup = pack.edges.first().id
        assertEquals(setOf("id"), EditorForms.edge(EdgeDraft(dup, "rec:a", "SUPPORTED_BY", "sop:ok"), pack, editing = false).errors().keys)
        assertTrue(EditorForms.edge(EdgeDraft(dup, "rec:a", "SUPPORTED_BY", "sop:ok", conditionsJson = ""), pack, editing = true) is FormResult.Ok)
        assertEquals(setOf("priority"), EditorForms.edge(EdgeDraft("edge:q", "rec:a", "SUPPORTED_BY", "sop:ok", priority = "x"), pack).errors().keys)
    }

    @Test fun aSourceDraftNeedsTheProductionFieldsToBeApproved() {
        val ok = EditorForms.source(SourceDraft("sop:new:v1", "Fire SOP", "2", "", "Chief Warden", "2026-10-11T10:00:00+05:30", "2027-06-01", "APPROVED", "Fire SOP v2")).ok()
        assertEquals(SourceStatus.APPROVED, ok.status); assertEquals("2", ok.version); assertEquals(null, ok.uri); assertEquals("2027-06-01", ok.expiresAt)
        assertEquals(setOf("approvedBy", "approvedAt"), EditorForms.source(SourceDraft("sop:x", "t", status = "APPROVED")).errors().keys)
        assertEquals(setOf("approvedAt", "expiresAt"), EditorForms.source(SourceDraft("sop:x", "t", approvedAt = "yesterday", expiresAt = "soon")).errors().keys)
        assertEquals(setOf("id", "title", "status"), EditorForms.source(SourceDraft("", "", status = "NOPE")).errors().keys)
        assertTrue(EditorForms.source(SourceDraft("sop:draft", "Draft", status = "NEEDS_ADMIN_VERIFICATION")) is FormResult.Ok)
    }

    @Test fun theApprovalFormNeedsAReviewerAndAFutureExpiry() {
        val now = "2026-10-11T06:00:00Z"
        assertEquals("Dr R" to null, EditorForms.approval(" Dr R ", "", now).ok())
        assertEquals("R" to "2027-01-01", EditorForms.approval("R", "2027-01-01", now).ok())
        assertEquals(setOf("reviewer"), EditorForms.approval("  ", "", now).errors().keys)
        assertTrue(EditorForms.approval("R", "2026-01-01", now).errors().getValue("expiresAt").contains("future"))
        assertTrue(EditorForms.approval("R", "someday", now).errors().getValue("expiresAt").contains("ISO"))
    }

    @Test fun draftsRoundTripThroughTheForms() {
        val n = pack.nodes.first { it.id == "action:a" }
        assertEquals(n.copy(version = 1), EditorForms.node(EditorForms.draftOf(n), pack, editing = true).ok().copy(version = 1))
        val e = pack.edges.first { it.relation == Relation.TRIGGERS_ACTION }
        assertEquals(e.copy(version = 1), EditorForms.edge(EditorForms.draftOf(e), pack, editing = true).ok().copy(version = 1))
        val s = pack.sources.first()
        assertEquals(s, EditorForms.source(EditorForms.draftOf(s)).ok())
    }

    @Test fun theEditorListsAreSearchableAndShapedPerSection() {
        val big = AdminTestSupport.seedPack
        assertEquals(big.nodes.size, EditorRows.of(big, EditorSection.NODES, "").size)
        assertEquals(big.edges.size, EditorRows.of(big, EditorSection.EDGES, "").size)
        assertEquals(big.sources.size, EditorRows.of(big, EditorSection.SOURCES, "").size)
        val recs = EditorRows.of(big, EditorSection.RECOMMENDATIONS, "")
        assertTrue(recs.isNotEmpty() && recs.all { it.id.startsWith("rec:") })
        assertTrue(EditorRows.of(big, EditorSection.TEAMS, "").all { it.id.startsWith("team:") })
        assertTrue(EditorRows.of(big, EditorSection.CONTACTS, "").isEmpty()) // none seeded
        assertEquals(1, EditorRows.of(big, EditorSection.SOURCES, "demo-seed").size)
        assertTrue(EditorRows.of(big, EditorSection.NODES, "computer fire").isNotEmpty()); assertTrue(EditorRows.of(big, EditorSection.NODES, "zzqq").isEmpty())
        assertTrue(EditorRows.of(big, EditorSection.RECOMMENDATIONS, "").any { it.status == "DRAFT" })
    }

    @Test fun thePreviewChoicesComeFromThePackAndTheSpecBuildsAnIncident() {
        val c = PreviewChoices.from(AdminTestSupport.seedPack)
        assertTrue("COMPUTER_FIRE" in c.incidentTypes && "COMPUTER_LAB" in c.locationTypes && "DESKTOP" in c.assets && "SMOKE" in c.hazards)
        assertEquals(c.incidentTypes.sorted(), c.incidentTypes)
        val i = PreviewSpec(type = "COMPUTER_FIRE", locationType = "COMPUTER_LAB", assets = listOf("DESKTOP"), hazards = listOf("SMOKE"), injury = null).toIncident("2026-10-11T06:00:00Z")
        assertTrue(i.userConfirmed && i.evidenceConfidence >= 0.9); assertEquals("PREVIEW", i.incidentId); assertEquals(null, i.injury); assertEquals(KgSeverity.HIGH, i.severity)
    }
}
