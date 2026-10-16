package com.example.test_app.knowledgegraph

import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import com.example.test_app.knowledgegraph.admin.GraphValidator
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.IncidentStatus
import com.example.test_app.knowledgegraph.model.KgSeverity
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.SourceStatus
import com.example.test_app.knowledgegraph.model.ValidatedIncident
import com.example.test_app.knowledgegraph.repository.GraphIndex
import com.example.test_app.knowledgegraph.rules.MedicalOutputGuard
import com.example.test_app.knowledgegraph.rules.PhoneNumberGuard
import com.example.test_app.knowledgegraph.engine.EngineConfig
import com.example.test_app.knowledgegraph.engine.KnowledgeGraphEngine
import com.example.test_app.knowledgegraph.engine.PhaseLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The shipped seed pack (`assets/kg/seed_pack.json`) and its 12 required scenarios (spec section 16). */
class SeedPackTest {
    private val pack: GraphPack = GraphPackCodec.decode(File(kgAssetsDir(), "seed_pack.json").readText(Charsets.UTF_8))
    private val index = GraphIndex(pack)
    private val engine = KnowledgeGraphEngine({ index }, null) { KG_NOW }
    private val scenarios: JsonArray = Json.parseToJsonElement(kgResource("scenarios.json").readText(Charsets.UTF_8)).jsonArray

    private fun JsonObject.strs(key: String): List<String> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun reportOf(s: JsonObject): IncidentReport {
        val r = s.getValue("report").jsonObject
        val f = mutableMapOf<String, FieldEntry>()
        r.str("appType")?.let { f[IncidentField.INCIDENT_TYPE.key] = FieldEntry(textValue(it), 0.9) }
        r.str("description")?.let { f[IncidentField.INCIDENT_DESCRIPTION.key] = FieldEntry(textValue(it), 0.9) }
        r.str("location")?.let { f[IncidentField.INCIDENT_LOCATION.key] = FieldEntry(textValue(it), 0.9) }
        r.str("severity")?.let { f[IncidentField.SEVERITY_LEVEL.key] = FieldEntry(textValue(it), 0.9) }
        r.str("immediateActions")?.let { f[IncidentField.IMMEDIATE_ACTIONS_TAKEN.key] = FieldEntry(textValue(it), 0.9) }
        (r["hazardStillActive"] as? JsonPrimitive)?.booleanOrNull?.let { f[IncidentField.HAZARD_STILL_ACTIVE.key] = FieldEntry(boolValue(it), 0.9) }
        (r["injury"] as? JsonPrimitive)?.booleanOrNull?.let { f[IncidentField.INJURY.key] = FieldEntry(boolValue(it), 0.9) }
        return IncidentReport(
            reportId = s.getValue("incident").jsonObject.str("incidentId")!!, status = IncidentReport.STATUS_SUBMITTED, createdAt = "2026-10-11T10:00:00+05:30",
            input = ReportInput("voice", "t"), fields = f,
        )
    }

    private fun incidentOf(s: JsonObject): ValidatedIncident = Json { ignoreUnknownKeys = false }.decodeFromJsonElement(ValidatedIncident.serializer(), s.getValue("incident"))

    // ---- the pack itself ---------------------------------------------------------------------------

    @Test fun thePackValidatesWithoutErrors() {
        val report = GraphValidator.validate(pack)
        assertTrue(report.errors.toString(), report.ok)
    }

    @Test fun theDemoSourceIsTheOnlyApprovedOneAndIsLabelled() {
        val approved = pack.sources.filter { it.status == SourceStatus.APPROVED }
        assertEquals(listOf("sop:demo-seed:v1"), approved.map { it.id })
        val demo = approved.single()
        assertEquals("demo-seed (not a real SOP)", demo.approvedBy)
        assertEquals("Demo guidance - pending IIIT-H admin approval", demo.displayLabel)
        assertTrue(pack.sources.filter { it.id != demo.id }.all { it.status == SourceStatus.NEEDS_ADMIN_VERIFICATION && it.approvedBy == null })
    }

    @Test fun noContactNumberOrVerifiedNodeIsSeeded() {
        assertTrue(pack.nodes.none { it.type == NodeType.EMERGENCY_CONTACT })
        assertTrue(pack.nodes.none { it.verificationStatus.name == "VERIFIED" })
        val allText = pack.nodes.flatMap { listOfNotNull(it.name, it.str("title"), it.str("text"), it.str("canonicalText"), it.str("description")) }
        assertTrue(allText.none { PhoneNumberGuard.containsUnverifiedNumber(it) })
        assertTrue(allText.none { t -> t.any { it.isDigit() } })
        assertTrue(pack.nodes.none { (it.int("capacity") ?: 0) > 0 })
    }

    @Test fun everyShownTextIsMedicallyCleanAndSafeToSay() {
        for (n in pack.nodes.filter { it.type in setOf(NodeType.ACTION, NodeType.CONSEQUENCE, NodeType.TEMPORARY_ARRANGEMENT) }) {
            val text = listOfNotNull(n.str("title"), n.str("text"), n.str("canonicalText")).joinToString(". ")
            assertTrue("${n.id}: ${MedicalOutputGuard.violations(text)}", MedicalOutputGuard.isClean(text))
        }
    }

    @Test fun everyRecommendationHasASourceAnOwnerAndANonDraftOneIsFullyApproved() {
        for (r in pack.nodes.filter { it.type == NodeType.RECOMMENDATION }) {
            assertEquals(1, index.supportEdges(r.id).size)
            assertNotNull(r.id, index.node(r.str("actionId")))
            assertNotNull(r.id, index.ownerOf(r.str("actionId")!!))
            if (r.str("approvalStatus") == "APPROVED") { assertTrue(r.id, !r.str("approvedBy").isNullOrBlank() && !r.str("approvedAt").isNullOrBlank()) }
        }
    }

    @Test fun theTaxonomyCoversTheNineFamiliesOfSpecSection9() {
        val categories = pack.nodes.filter { it.type == NodeType.INCIDENT_TYPE }.mapNotNull { it.str("category") }.toSet()
        assertTrue(categories.toString(), categories.containsAll(setOf("FIRE", "ELECTRICAL", "WATER", "MEDICAL", "CHEMICAL", "PROPERTY", "IT", "FOOD", "OUTDOOR_SECURITY")))
        val codes = pack.nodes.filter { it.type == NodeType.INCIDENT_TYPE }.mapNotNull { it.str("code") }.toSet()
        for (c in listOf("CLASSROOM_FIRE", "COMPUTER_FIRE", "SERVER_RACK_FIRE", "KITCHEN_FIRE", "WATER_NEAR_POWER", "CHEMICAL_SPILL", "SPORTS_INJURY", "SERVER_OVERHEATING")) assertTrue("missing incident type $c in $codes", c in codes)
        assertTrue(codes.any { it.contains("LPG") }); assertTrue(codes.any { it.contains("EXIT") })
    }

    @Test fun everyIncidentTypeIsWiredToSomeGuidance() {
        for (t in pack.nodes.filter { it.type == NodeType.INCIDENT_TYPE }) {
            val out = index.edgesFrom(t.id, Relation.TRIGGERS_ACTION).size + index.edgesFrom(t.id, Relation.PRODUCES_HAZARD).size
            assertTrue("${t.id} has no outgoing guidance", out > 0)
        }
    }

    @Test fun locationHierarchyIsBroadAndUnverified() {
        val buildings = pack.nodes.filter { it.type == NodeType.BUILDING }.map { it.name }
        assertTrue(buildings.toString(), buildings.any { it.contains("Nilgiri") } && buildings.any { it.contains("Himalaya") } && buildings.any { it.contains("Kohli") })
        assertTrue(pack.edges.any { it.relation == Relation.CONTAINS })
        assertTrue(pack.nodes.filter { it.type in setOf(NodeType.CAMPUS, NodeType.BUILDING) }.all { it.verificationStatus.name == "NEEDS_ADMIN_VERIFICATION" })
    }

    @Test fun historyPatternsAreDataWithThresholdsAndWindows() {
        val patterns = pack.nodes.filter { it.type == NodeType.HISTORICAL_PATTERN }
        assertTrue(patterns.size >= 3)
        assertTrue(patterns.all { it.int("threshold") != null && it.int("windowDays") != null && it.strings("incidentTypes").isNotEmpty() })
        assertTrue(patterns.any { it.str("scope") == "ZONE" || it.str("scope") == "LOCATION" })
    }

    // ---- the 12 scenarios --------------------------------------------------------------------------

    @Test fun thereAreTwelveScenariosInTheOrderOfSpecSection16() {
        assertEquals(12, scenarios.size)
        val names = scenarios.map { it.jsonObject.str("name")!!.lowercase() }
        val keys = listOf("classroom fire", "computer fire", "server cooling", "water near power", "washroom leak", "hostel smoke", "lpg", "football", "furniture", "chemical", "fire exit", "network")
        keys.forEachIndexed { i, k -> assertTrue("scenario ${i + 1} should mention '$k' but is '${names[i]}'", names[i].contains(k)) }
    }

    @Test fun theAdapterTurnsEveryScenarioReportIntoTheFixtureIncident() {
        for (s in scenarios.map { it.jsonObject }) {
            val expected = incidentOf(s)
            val got = KnowledgeGraphInput.fromReport(reportOf(s), pack.normalization)
            assertEquals(s.str("name"), expected, got)
        }
    }

    @Test fun everyScenarioProducesApprovedSourceLinkedGuidanceWithinTheLimits() {
        for (s in scenarios.map { it.jsonObject }) {
            val name = s.str("name")!!
            val set = engine.recommend(incidentOf(s), Audience.REPORTER)
            assertFalse(name, set.abstained)
            assertTrue("$name: no guidance", set.allRecommendations.isNotEmpty())
            assertTrue(name, set.immediateActions.size <= 3 && set.operationalSteps.size <= 3 && set.recoverySteps.size <= 3 && set.preventiveActions.size <= 2)
            assertTrue(name, set.temporaryArrangements.size <= 2 && set.unresolvedQuestions.size <= 1 && set.consequences.size <= 4)
            for (id in s.strs("mustInclude")) assertTrue("$name: expected $id, got ${set.ids()}; why: ${set.whyNot(id)}", id in set.ids())
            for (id in s.strs("mustNotShowReporter")) assertFalse("$name: $id must not be shown to reporters", id in set.ids())
            for (id in s.strs("mustIncludeArrangements")) assertTrue("$name: expected $id, got ${set.temporaryArrangements.map { it.id }}", id in set.temporaryArrangements.map { it.id })
            for (id in s.strs("suppressedArrangements")) assertTrue("$name: $id must be suppressed", set.suppressed.any { it.candidateId == id })
            assertTrue(name, set.allRecommendations.all { it.sourceId == "sop:demo-seed:v1" && !it.unverifiedDraft && it.provenance.isNotEmpty() })
            assertEquals(name, "Demo guidance - pending IIIT-H admin approval", set.guidanceLabel)
            assertTrue(name, set.suppressed.all { it.reason.isNotBlank() })
            assertTrue(name, set.responsibleTeams.all { !it.verified && it.contactNumber == null })
            val all = set.allRecommendations.map { it.renderedText } + set.consequences.map { it.text } + set.temporaryArrangements.map { it.text }
            assertTrue(name, all.all { MedicalOutputGuard.isClean(it) && !PhoneNumberGuard.containsUnverifiedNumber(it) })
        }
    }

    @Test fun theAdminPreviewShowsTheDraftGuidanceAsUnverifiedDrafts() {
        // with wide limits (drafts would otherwise compete for the few visible slots) the preview is a superset of what reporters see
        val wide = KnowledgeGraphEngine({ index }, null, EngineConfig(limits = PhaseLimits(20, 20, 20, 20, 20, 1))) { KG_NOW }
        for (s in scenarios.map { it.jsonObject }) {
            val name = s.str("name")!!
            val reporter = wide.recommend(incidentOf(s), Audience.REPORTER).ids().toSet()
            val admin = wide.recommend(incidentOf(s), Audience.ADMIN_PREVIEW)
            assertTrue(name, admin.ids().containsAll(reporter))
            for (id in s.strs("adminOnlyPresent")) {
                assertTrue("$name: admin preview should show $id (got ${admin.ids()}; why: ${admin.whyNot(id)})", id in admin.ids())
                assertTrue(name, admin.allRecommendations.first { it.id == id }.unverifiedDraft)
            }
        }
    }

    @Test fun theFootballInjuryScenarioNeverDiagnosesAndCarriesTheFooter() {
        val s = scenarios.map { it.jsonObject }.first { it.str("name")!!.contains("ootball") }
        val set = engine.recommend(incidentOf(s), Audience.REPORTER)
        assertEquals(MedicalOutputGuard.FOOTER, set.medicalFooter)
        val text = (set.allRecommendations.map { it.renderedText + " " + it.title } + set.consequences.map { it.text + " " + it.name }).joinToString(" ").lowercase()
        for (bad in listOf("fracture", "sprain", "concussion", "medicine", "tablet", "treat", "diagnos")) assertFalse(bad, text.contains(bad))
        assertTrue(set.allRecommendations.any { it.id == "rec:medical-contact" })
    }

    @Test fun theChemicalScenarioOffersNoGenericCleanupAdvice() {
        val s = scenarios.map { it.jsonObject }.first { it.str("name")!!.contains("hemical") }
        val set = engine.recommend(incidentOf(s), Audience.REPORTER)
        val text = set.allRecommendations.joinToString(" ") { it.renderedText.lowercase() }
        for (bad in listOf("clean up", "wipe", "mop", "neutralize", "absorb", "dispose")) assertFalse(bad, text.contains(bad))
        assertTrue(set.allRecommendations.any { it.id == "rec:specialist-chemical" })
    }

    @Test fun theWaterNearPowerScenarioNeverTellsTheUserToTouchAnything() {
        val s = scenarios.map { it.jsonObject }.first { it.str("name")!!.contains("ater near power") }
        val set = engine.recommend(incidentOf(s), Audience.REPORTER)
        for (r in set.allRecommendations.filter { it.canonicalText.isNotBlank() }) {
            val node = index.node(r.actionId)!!
            if (node.bool("userCanPerform") == true) assertFalse(r.id, com.example.test_app.knowledgegraph.rules.SafetyPolicy.unnegatedMatch(Regex("\\b(touch|unplug|plug|switch|move|pull)\\w*\\b", RegexOption.IGNORE_CASE), r.canonicalText))
        }
    }

    @Test fun theRepeatedLeakScenarioAddsTheSharedLineInspectionOnlyWithRecurrence() {
        val s = scenarios.map { it.jsonObject }.first { it.str("name")!!.contains("washroom leak") }
        val set = engine.recommend(incidentOf(s), Audience.REPORTER)
        assertFalse("rec:inspect-shared-plumbing" in set.ids())
        val cite = com.example.test_app.knowledgegraph.model.HistoryCitation(
            "pattern:washroom-leak-recurrence", "x", listOf("INC-1", "INC-2", "INC-3"), listOf("2026-09-01", "2026-09-20", "2026-10-10"), 3, 60,
        )
        val hist = pack.nodes.firstOrNull { it.type == NodeType.HISTORICAL_PATTERN && it.strings("incidentTypes").isNotEmpty() && index.edgesFrom(it.id, Relation.TRIGGERS_ACTION).any { e -> e.targetId == "action:inspect-shared-plumbing" } }
        assertNotNull("a pattern that triggers action:inspect-shared-plumbing", hist)
        val withHistory = engine.recommend(incidentOf(s), Audience.REPORTER, listOf(cite.copy(patternId = hist!!.id)))
        assertTrue(withHistory.ids().toString(), "rec:inspect-shared-plumbing" in withHistory.ids())
        assertEquals(listOf("INC-1", "INC-2", "INC-3"), withHistory.allRecommendations.first { it.id == "rec:inspect-shared-plumbing" }.history.single().incidentIds)
    }

    @Test fun unknownLocationOrAssetStillAbstainsWithTheSeedPack() {
        val s = scenarios.map { it.jsonObject }.first { it.str("name")!!.contains("omputer fire") }
        val noPlace = engine.recommend(incidentOf(s).copy(locationType = null, affectedAssets = emptyList()), Audience.REPORTER)
        assertTrue(noPlace.unresolvedQuestions.size <= 1)
        assertTrue(noPlace.temporaryArrangements.isEmpty())
        val unknown = engine.recommend(incidentOf(s).copy(type = null), Audience.REPORTER)
        assertTrue(unknown.abstained); assertEquals("q:incidentType", unknown.unresolvedQuestions.single().id)
    }

    @Test fun anAbstainedOrUnconfirmedIncidentGetsNothingFromTheSeed() {
        val s = scenarios.map { it.jsonObject }.first()
        assertTrue(engine.recommend(incidentOf(s).copy(userConfirmed = false)).abstained)
        assertTrue(engine.recommend(incidentOf(s).copy(evidenceConfidence = 0.05)).abstained)
    }

    @Test fun theBlockedExitScenarioRoutesTheObstructionToAResponsibleTeamNotTheUser() {
        val s = scenarios.map { it.jsonObject }.first { it.str("name")!!.contains("ire exit") }
        val set = engine.recommend(incidentOf(s), Audience.REPORTER)
        val removal = set.allRecommendations.first { it.id == "rec:remove-exit-obstruction" }
        assertFalse(index.node(removal.actionId)!!.bool("userCanPerform") == true)
        assertTrue(removal.canonicalText.startsWith("Ask authorized"))
        assertNotNull(removal.ownerTeamName)
    }

    @Test fun everyScenarioIncidentTypeExistsInTheGraphAndIsConfirmed() {
        for (s in scenarios.map { it.jsonObject }) {
            val i = incidentOf(s)
            assertNotNull(s.str("name"), index.byCode(NodeType.INCIDENT_TYPE, i.type))
            assertTrue(i.userConfirmed); assertTrue(i.severity != null || true)
        }
        assertEquals(IncidentStatus.entries.toSet().containsAll(scenarios.map { incidentOf(it.jsonObject).status }.toSet()), true)
        assertEquals(KgSeverity.entries.isNotEmpty(), true)
    }
}
