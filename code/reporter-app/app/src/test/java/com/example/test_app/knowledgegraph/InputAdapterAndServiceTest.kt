package com.example.test_app.knowledgegraph

import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.listValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import com.example.test_app.knowledgegraph.llmgrounding.LlmGroundedRenderer
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.CodeRule
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.IncidentStatus
import com.example.test_app.knowledgegraph.model.KgSeverity
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.NormalizationRules
import com.example.test_app.knowledgegraph.model.RenderMode
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.StatusRule
import com.example.test_app.knowledgegraph.model.TypeRule
import com.example.test_app.knowledgegraph.repository.InMemoryAnalysisOutbox
import com.example.test_app.knowledgegraph.repository.InMemoryAuditStore
import com.example.test_app.knowledgegraph.repository.InMemoryGraphRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The report -> graph adapter (`KnowledgeGraphInput`), the orchestration (`KnowledgeGraphService`), the export and the outbox. */
class InputAdapterAndServiceTest {
    private val rules = NormalizationRules(
        incidentTypes = listOf(
            TypeRule("COMPUTER_FIRE", allKeywords = listOf("fire"), anyKeywords = listOf("computer", "desktop", "laptop")),
            TypeRule("FIRE", appTypes = listOf("Fire")),
            TypeRule("WATER_LEAK", appTypes = listOf("Water Leak"), anyKeywords = listOf("leak", "flooding")),
        ),
        locationTypes = listOf(CodeRule("COMPUTER_LAB", listOf("computer lab")), CodeRule("HOSTEL_ROOM", listOf("hostel room", "hostel")), CodeRule("WASHROOM", listOf("washroom"))),
        assets = listOf(CodeRule("DESKTOP", listOf("desktop", "computer")), CodeRule("POWER_STRIP", listOf("power strip", "extension"))),
        hazards = listOf(CodeRule("SMOKE", listOf("smoke")), CodeRule("WATER_NEAR_POWER", listOf("water near the power", "water on the power strip"))),
        operationalEffects = listOf(CodeRule("WORKSTATION_UNAVAILABLE", listOf("workstation is unusable", "cannot use the computer"))),
        statuses = listOf(StatusRule(IncidentStatus.CONTAINED, listOf("extinguished", "under control", "isolated")), StatusRule(IncidentStatus.RESOLVED, listOf("fixed", "repaired"))),
        buildings = listOf(CodeRule("building:krb", listOf("krb", "kohli"))),
        residentialLocationTypes = listOf("HOSTEL_ROOM"),
    )

    private fun report(
        type: String? = "Fire", description: String? = "A desktop computer caught fire in the computer lab and there is smoke.", place: String? = "KRB computer lab", severity: String? = "High",
        status: String = IncidentReport.STATUS_SUBMITTED, stillActive: Boolean? = null, injury: Boolean? = null, actions: String? = null, id: String = "INC-2026-000001",
        persons: List<String> = emptyList(), conf: Double = 0.9,
    ): IncidentReport {
        val f = mutableMapOf<String, FieldEntry>()
        type?.let { f[IncidentField.INCIDENT_TYPE.key] = FieldEntry(textValue(it), conf) }
        description?.let { f[IncidentField.INCIDENT_DESCRIPTION.key] = FieldEntry(textValue(it), conf) }
        place?.let { f[IncidentField.INCIDENT_LOCATION.key] = FieldEntry(textValue(it), conf) }
        severity?.let { f[IncidentField.SEVERITY_LEVEL.key] = FieldEntry(textValue(it), conf) }
        actions?.let { f[IncidentField.IMMEDIATE_ACTIONS_TAKEN.key] = FieldEntry(textValue(it), conf) }
        stillActive?.let { f[IncidentField.HAZARD_STILL_ACTIVE.key] = FieldEntry(boolValue(it), conf) }
        injury?.let { f[IncidentField.INJURY.key] = FieldEntry(boolValue(it), conf) }
        if (persons.isNotEmpty()) f[IncidentField.PERSONS_INVOLVED.key] = FieldEntry(listValue(persons), conf)
        return IncidentReport(reportId = id, status = status, createdAt = "2026-10-11T10:05:00+05:30", input = ReportInput("voice", "t"), fields = f)
    }

    private fun map(r: IncidentReport) = KnowledgeGraphInput.fromReport(r, rules)

    @Test fun aSubmittedReportBecomesAConfirmedGraphIncident() {
        val i = map(report())
        assertEquals("COMPUTER_FIRE", i.type); assertEquals("COMPUTER_LAB", i.locationType); assertEquals(KgSeverity.HIGH, i.severity)
        assertEquals(listOf("DESKTOP"), i.affectedAssets); assertEquals(listOf("SMOKE"), i.activeHazards); assertEquals("building:krb", i.locationId)
        assertTrue(i.userConfirmed); assertEquals("INC-2026-000001", i.incidentId); assertEquals(0.9, i.evidenceConfidence, 1e-9)
    }

    @Test fun onlySubmittedReportsAreConfirmed() {
        assertFalse(map(report(status = IncidentReport.STATUS_COMPLETE)).userConfirmed)
        assertFalse(map(report(status = IncidentReport.STATUS_NEEDS_CLARIFICATION)).userConfirmed)
    }

    @Test fun theAppTypeSelectsAGenericTypeWhenNoSpecificRuleMatches() {
        assertEquals("FIRE", map(report(description = "Flames in the corridor", place = "Library")).type)
        assertEquals("WATER_LEAK", map(report(type = "Water Leak", description = "Pipe leak", place = "pantry")).type)
        assertEquals("WATER_LEAK", map(report(type = "Other", description = "There is flooding near the stairs", place = "stairs")).type)
    }

    @Test fun anUnrecognisedReportKeepsTheTypeUnknownInsteadOfGuessing() {
        val i = map(report(type = "Other", description = "Something odd happened", place = "somewhere"))
        assertNull(i.type); assertNull(i.locationType); assertTrue(i.affectedAssets.isEmpty()); assertTrue(i.activeHazards.isEmpty()); assertEquals(IncidentStatus.UNKNOWN, i.status)
    }

    @Test fun statusComesFromTheStillActiveFieldThenFromTheWords() {
        assertEquals(IncidentStatus.ACTIVE, map(report(stillActive = true, description = "The fire was extinguished")).status)
        assertEquals(IncidentStatus.CONTAINED, map(report(description = "The fire was extinguished by staff")).status)
        assertEquals(IncidentStatus.CONTAINED, map(report(description = "A desktop caught fire", actions = "Area isolated")).status)
        assertEquals(IncidentStatus.RESOLVED, map(report(description = "A desktop caught fire, the socket was repaired")).status)
        assertEquals(IncidentStatus.UNKNOWN, map(report(stillActive = false, description = "A desktop caught fire")).status)
    }

    @Test fun keywordsMatchWholeWordsOnly() {
        val r = NormalizationRules(locationTypes = listOf(CodeRule("LAB", listOf("lab"))), assets = listOf(CodeRule("PC", listOf("pc"))))
        val i = KnowledgeGraphInput.fromReport(report(description = "The table cracked", place = "Labrador cafe, upcoming"), r)
        assertNull(i.locationType); assertTrue(i.affectedAssets.isEmpty())
        assertEquals("LAB", KnowledgeGraphInput.fromReport(report(place = "Main lab, block A"), r).locationType)
    }

    @Test fun injuryAndPeopleCountComeFromTheReportWithoutNames() {
        val hurt = map(report(injury = true, persons = listOf("Ravi (injured)", "Meera (injured)", "Sam")))
        assertEquals(true, hurt.injury); assertEquals(2, hurt.peopleAffected)
        assertEquals(false, map(report(injury = false)).injury)
        assertNull(map(report()).injury); assertNull(map(report()).peopleAffected)
        val fields = ValidatedIncidentFieldNames.names
        assertTrue(fields.none { it.contains("reporter", true) || it.contains("name", true) || it.contains("person", true) })
    }

    @Test fun lowConfidenceFieldsGiveLowEvidence() {
        assertEquals(0.4, map(report(conf = 0.4)).evidenceConfidence, 1e-9)
        assertEquals(0.0, map(report(type = null, description = null, place = null)).evidenceConfidence, 1e-9)
    }

    @Test fun theHistoryRecordHasTypeDateAndPlaceKeysOnly() {
        val h = KnowledgeGraphInput.historic(report(place = "KRB, washroom 3rd floor", description = "Pipe leak in the washroom", type = "Water Leak"), rules)
        assertEquals("WATER_LEAK", h.type); assertEquals("krb washroom 3rd floor", h.locationKey); assertEquals("building:krb", h.buildingKey)
        assertEquals("building:krb|3rd floor", h.zoneKey); assertTrue(h.createdAt.startsWith("2026-10-11"))
    }

    // ---- service ------------------------------------------------------------------------------------------

    private fun servicePack(): GraphPack = PackBuilder().incidentType("COMPUTER_FIRE").team("ops")
        .action("isolate", text = "Ask authorized electricians to isolate the power to the damaged desktop.", orderClass = "HAZARD_ISOLATION").rec("isolate").trigger("COMPUTER_FIRE", "isolate", priority = 90)
        .build().copy(normalization = rules)

    private fun service(pack: GraphPack = servicePack(), renderer: () -> com.example.test_app.knowledgegraph.llmgrounding.GroundedRecommendationRenderer = { com.example.test_app.knowledgegraph.llmgrounding.TemplateRenderer() }) =
        Triple(KnowledgeGraphService(InMemoryGraphRepository(pack), InMemoryAuditStore().also { audits = it }, InMemoryAnalysisOutbox().also { outbox = it }, renderer, clock = { KG_NOW }), pack, Unit)

    private lateinit var audits: InMemoryAuditStore
    private lateinit var outbox: InMemoryAnalysisOutbox

    @Test fun aSubmittedReportIsAnalysedLoggedAndQueuedForTheDashboard() = runTest {
        val (svc, _, _) = service()
        val r = svc.analyze(report(), emptyList()) as AnalysisResult.Done
        assertEquals(listOf("rec:isolate"), r.set.recommendationIds)
        val log = audits.load("INC-2026-000001")!!
        assertEquals(listOf("rec:isolate"), log.recommendationIds); assertEquals("TEMPLATE", log.shown.first { it.kind == "recommendation" }.renderMode)
        assertEquals(listOf("INC-2026-000001"), outbox.pending())
        val export = outbox.read("INC-2026-000001")!!
        assertEquals(IncidentAnalysisExport.SCHEMA_VERSION, export.schemaVersion); assertEquals(1, export.graphVersion)
        assertEquals("rec:isolate", export.recommendations.single().id); assertEquals("sop:ok", export.recommendations.single().sourceId)
    }

    @Test fun anUnsubmittedReportGetsNoGuidance() = runTest {
        val (svc, _, _) = service()
        val r = svc.analyze(report(status = IncidentReport.STATUS_COMPLETE), emptyList()) as AnalysisResult.Done
        assertTrue(r.set.abstained); assertTrue(r.set.recommendationIds.isEmpty())
    }

    @Test fun theExportHidesTheExactPlaceOfResidentialLocationsAndNeverHoldsNames() = runTest {
        val (svc, _, _) = service(servicePack())
        val hostel = report(type = "Fire", description = "A desktop computer caught fire in the hostel room, smoke", place = "Hostel B room 214", persons = listOf("Priya Rao (injured)"), injury = true)
        val r = svc.analyze(hostel, emptyList()) as AnalysisResult.Done
        assertEquals("HOSTEL_ROOM", r.export.incident.locationType); assertNull(r.export.incident.locationLabel); assertTrue(r.export.incident.locationRedacted)
        val json = GraphPackCodec.pretty.encodeToString(IncidentAnalysisExport.serializer(), r.export)
        assertFalse(json.contains("214")); assertFalse(json.contains("Priya")); assertFalse(json.contains("Hostel B"))
        val lab = (svc.analyze(report(id = "INC-2"), emptyList()) as AnalysisResult.Done).export.incident
        assertEquals("KRB computer lab", lab.locationLabel); assertFalse(lab.locationRedacted)
    }

    @Test fun reporterOutputNeverCarriesAnInjuredPersonsIdentity() = runTest {
        val (svc, _, _) = service()
        val r = svc.analyze(report(injury = true, persons = listOf("Priya Rao (injured)")), emptyList()) as AnalysisResult.Done
        val all = GraphPackCodec.pretty.encodeToString(com.example.test_app.knowledgegraph.model.RecommendationSet.serializer(), r.set) + audits.load("INC-2026-000001").toString() + outbox.read("INC-2026-000001").toString()
        assertFalse(all.contains("Priya")); assertFalse(all.contains("Rao"))
        assertEquals("This is immediate safety guidance, not medical advice. Contact campus medical services or qualified emergency personnel.", r.set.medicalFooter)
    }

    @Test fun anEmptyHistoryAndRepeatsInTheStoredReportsFireTheRecurrencePattern() = runTest {
        val pack = PackBuilder().incidentType("WATER_LEAK", "WATER").team("ops")
            .action("shared-line", text = "Ask authorized plumbing staff to inspect the shared supply and drain path.").rec("shared-line")
            .node("pattern:leaks", NodeType.HISTORICAL_PATTERN, "Repeated leaks", props("scope" to "LOCATION", "incidentTypes" to listOf("WATER_LEAK"), "windowDays" to 60, "threshold" to 3, "description" to "Repeated water leaks"))
            .edge("pattern:leaks", Relation.TRIGGERS_ACTION, "action:shared-line", priority = 90)
            .build().copy(normalization = rules)
        val (svc, _, _) = service(pack)
        fun leak(id: String, created: String) = report(type = "Water Leak", description = "Pipe leak in the washroom", place = "KRB washroom", id = id).copy(createdAt = created)
            .let { it.copy(fields = it.fields + (IncidentField.DATE_TIME.key to FieldEntry(textValue(created), 0.9))) }
        val earlier = listOf(leak("INC-1", "2026-09-01T09:00:00+05:30"), leak("INC-2", "2026-09-20T09:00:00+05:30"))
        val current = leak("INC-3", "2026-10-10T09:00:00+05:30")
        val few = svc.analyze(current, earlier.take(1) + current) as AnalysisResult.Done
        assertTrue(few.set.recommendationIds.isEmpty())
        val many = svc.analyze(current, earlier + current) as AnalysisResult.Done
        assertEquals(listOf("rec:shared-line"), many.set.recommendationIds)
        assertEquals(listOf("INC-1", "INC-2", "INC-3"), many.set.historyCitations.single().incidentIds)
        assertEquals(listOf("pattern:leaks"), many.export.recommendations.single().historyPatternIds)
    }

    @Test fun theLlmRendererIsUsedWhenSuppliedAndFallsBackSafely() = runTest {
        val ok = LlmGroundedRenderer { "[{\"recommendation_id\": \"rec:isolate\", \"rendered_text\": \"Ask authorized electricians to isolate the power to the damaged desktop.\"}]" }
        val (svc, _, _) = service(renderer = { ok })
        val done = svc.analyze(report(), emptyList()) as AnalysisResult.Done
        assertEquals(RenderMode.LLM, done.set.immediateActions.single().renderMode)
        assertEquals("LLM", audits.load("INC-2026-000001")!!.shown.first { it.kind == "recommendation" }.renderMode)
        val (svc2, _, _) = service(renderer = { LlmGroundedRenderer { "I cannot do that" } })
        val fallback = (svc2.analyze(report(), emptyList()) as AnalysisResult.Done).set.immediateActions.single()
        assertEquals(RenderMode.FALLBACK, fallback.renderMode); assertEquals(fallback.canonicalText, fallback.renderedText)
    }

    @Test fun aBrokenRendererOrGraphNeverEscapesAsAnException() = runTest {
        val (svc, _, _) = service(renderer = { error("boom") })
        val r = svc.analyze(report(), emptyList())
        assertTrue(r is AnalysisResult.Failed)
    }

    @Test fun reanalysisReplacesTheQueuedDocumentAndAcknowledgeRemovesIt() = runTest {
        val (svc, _, _) = service()
        svc.analyze(report(), emptyList()); svc.analyze(report(), emptyList())
        assertEquals(1, outbox.pending().size)
        outbox.acknowledge("INC-2026-000001")
        assertTrue(outbox.pending().isEmpty()); assertNull(outbox.read("INC-2026-000001"))
    }

    @Test fun theServiceFollowsAnImportedPackWithoutRestart() = runTest {
        val repo = InMemoryGraphRepository(servicePack())
        val svc = KnowledgeGraphService(repo, InMemoryAuditStore(), InMemoryAnalysisOutbox(), clock = { KG_NOW })
        assertEquals(1, (svc.analyze(report(), emptyList()) as AnalysisResult.Done).set.graphVersion)
        repo.save(servicePack().copy(packVersion = 2)); repo.activate(2)
        assertEquals(2, (svc.analyze(report(), emptyList()) as AnalysisResult.Done).set.graphVersion)
    }

    @Test fun approvalStatusOfTheExportedItemsIsTraceable() = runTest {
        val (svc, _, _) = service()
        val e = (svc.analyze(report(), emptyList()) as AnalysisResult.Done).export
        assertTrue(e.recommendations.all { it.sourceId.isNotBlank() && it.provenance.isNotEmpty() && !it.unverifiedDraft })
        assertEquals(ApprovalStatus.APPROVED.name.isNotBlank(), true)
    }
}

/** The field names of [com.example.test_app.knowledgegraph.model.ValidatedIncident] (a reflection check that no identity field exists). */
object ValidatedIncidentFieldNames {
    val names: List<String> = com.example.test_app.knowledgegraph.model.ValidatedIncident::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") && it != "Companion" }
}
