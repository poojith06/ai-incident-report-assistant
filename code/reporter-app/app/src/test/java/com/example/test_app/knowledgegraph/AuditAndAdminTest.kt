package com.example.test_app.knowledgegraph

import com.example.test_app.knowledgegraph.admin.AdminException
import com.example.test_app.knowledgegraph.admin.AdminGraphService
import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import com.example.test_app.knowledgegraph.admin.GraphValidator
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.KgEdge
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.SourceStatus
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.knowledgegraph.repository.FileAuditStore
import com.example.test_app.knowledgegraph.repository.FileGraphRepository
import com.example.test_app.knowledgegraph.repository.InMemoryAuditStore
import com.example.test_app.knowledgegraph.repository.InMemoryGraphRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** Spec sections 12, 18 and 20: audit-log completeness, import validation, approval rules, versions and rollback. */
class AuditAndAdminTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pack(configure: PackBuilder.() -> Unit = {}): GraphPack = PackBuilder.basic("isolate").apply(configure).build()
    private fun admin(p: GraphPack = pack()) = AdminGraphService(InMemoryGraphRepository(p)) { KG_NOW }

    // ---- audit ----------------------------------------------------------------------------------

    @Test fun theAuditLogRecordsEverythingShownAndEverySuppressionWithAReason() {
        val audits = InMemoryAuditStore()
        val b = PackBuilder.basic("good").action("bad", text = "Extinguish the fire.", userCan = true, auth = false).rec("bad").trigger("FIRE", "bad")
            .action("draft").rec("draft", status = ApprovalStatus.DRAFT).trigger("FIRE", "draft")
        val set = engineFor(b.build(), audits).recommend(incident(id = "INC-AUDIT"))
        val log = audits.load("INC-AUDIT")!!
        assertEquals(1, log.graphVersion); assertEquals(Audience.REPORTER, log.audience); assertTrue(log.userConfirmed); assertFalse(log.abstained)
        assertEquals(set.recommendationIds, log.recommendationIds); assertEquals(listOf("rec:good"), log.recommendationIds)
        assertEquals(listOf("rec:good"), log.shown.filter { it.kind == "recommendation" }.map { it.id })
        assertTrue(log.shown.single { it.kind == "recommendation" }.provenance.isNotEmpty())
        assertEquals(2, log.suppressed.size)
        assertTrue(log.suppressed.all { it.reason.isNotBlank() && it.candidateId.isNotBlank() && it.kind.isNotBlank() })
        assertTrue(log.suppressed.any { it.candidateId == "rec:bad" && it.reason.contains("safety rule") })
        assertTrue(log.suppressed.any { it.candidateId == "rec:draft" && it.reason.contains("not approved") })
        assertEquals("FIRE", log.inputFacts.type); assertEquals("HIGH", log.inputFacts.severity); assertEquals("ACTIVE", log.inputFacts.status)
        assertTrue(log.id.startsWith("log:INC-AUDIT:g1:"))
    }

    @Test fun anAbstainedRunIsLoggedToo() {
        val audits = InMemoryAuditStore()
        engineFor(pack(), audits).recommend(incident(id = "INC-A2", confirmed = false))
        val log = audits.load("INC-A2")!!
        assertTrue(log.abstained); assertFalse(log.userConfirmed); assertTrue(log.suppressed.single().reason.contains("not confirmed"))
    }

    @Test fun everySuppressionOfAMixedRunHasAReasonAndEverythingShownHasASource() {
        val b = PackBuilder.basic("a", "b", "c").action("d").rec("d", status = ApprovalStatus.DRAFT).trigger("FIRE", "d")
        val set = b.run()
        assertTrue(set.suppressed.all { it.reason.isNotBlank() })
        assertTrue(set.allRecommendations.all { it.sourceId.isNotBlank() && it.provenance.isNotEmpty() })
    }

    @Test fun theFileAuditStoreRoundTripsAndReplacesAnEarlierRun() {
        val store = FileAuditStore(tmp.newFolder("audit"))
        val engine = engineFor(pack(), null)
        val first = engine.analyze(incident(id = "INC-9")).log
        store.save(first)
        assertEquals(first, store.load("INC-9"))
        val second = engine.analyze(incident(id = "INC-9", severity = null)).log.copy(id = "log:other")
        store.save(second)
        assertEquals("log:other", store.load("INC-9")!!.id)
        assertNull(store.load("INC-404"))
    }

    // ---- validation and import --------------------------------------------------------------------

    @Test fun aGoodPackValidatesAndRoundTripsThroughJson() {
        val p = pack()
        assertTrue(GraphValidator.validate(p).ok)
        assertEquals(p, GraphPackCodec.decode(GraphPackCodec.encode(p)))
    }

    @Test fun importRejectsADanglingEdgeAndStoresNothing() {
        val a = admin()
        val bad = pack { edge("action:isolate", Relation.OWNED_BY, "team:ghost") }
        try { a.importPack(GraphPackCodec.encode(bad)); throw AssertionError("expected AdminException") } catch (e: AdminException) {
            assertTrue(e.report!!.errors.any { it.contains("team:ghost") })
        }
        assertEquals(listOf(1), a.versions()); assertEquals(1, a.activeVersion())
    }

    @Test fun importRejectsDanglingSourcesRecommendationsAndRefs() {
        fun errors(p: GraphPack) = GraphValidator.validate(p).errors
        assertTrue(errors(pack { rec("x", source = "sop:ghost").action("x") }).any { it.contains("sop:ghost") })
        assertTrue(errors(pack { edge("incident-type:FIRE", Relation.TRIGGERS_ACTION, "action:nope") }).any { it.contains("action:nope") })
        assertTrue(errors(pack { edge("action:isolate", Relation.OWNED_BY, "team:ops", sourceRef = "sop:ghost") }).any { it.contains("sourceRef") })
        assertTrue(errors(pack { node("rec:orphan", NodeType.RECOMMENDATION, "orphan", props("actionId" to "action:ghost", "approvalStatus" to "APPROVED")) }).any { it.contains("actionId") })
    }

    @Test fun importRejectsPrerequisiteCycles() {
        val cyc = pack { action("a").action("b").edge("action:a", Relation.DEPENDS_ON, "action:b").edge("action:b", Relation.DEPENDS_ON, "action:a") }
        assertTrue(GraphValidator.validate(cyc).errors.any { it.contains("prerequisite cycle") })
        val self = pack { action("s").edge("action:s", Relation.DEPENDS_ON, "action:s") }
        assertNotNull(GraphValidator.findCycle(self))
        assertNull(GraphValidator.findCycle(pack { action("a").action("b").edge("action:a", Relation.DEPENDS_ON, "action:b") }))
    }

    @Test fun importRejectsNonsenseAndUnknownKeys() {
        val a = admin()
        for (json in listOf("not json", "{}", GraphPackCodec.encode(pack()).replace("\"name\"", "\"nom\""))) {
            try { a.importPack(json); throw AssertionError("expected AdminException for ${json.take(20)}") } catch (_: AdminException) { }
        }
    }

    @Test fun importRejectsUnsafeOrMedicalOrPhoneTextAndUnverifiedEnabledContacts() {
        assertTrue(GraphValidator.validate(pack { action("m", text = "Take paracetamol 500 mg.").rec("m") }).errors.any { it.contains("medical") })
        assertTrue(GraphValidator.validate(pack { action("u", text = "Extinguish the fire.", userCan = true, auth = false).rec("u") }).errors.any { it.contains("fight a fire") })
        assertTrue(GraphValidator.validate(pack { action("p", text = "Ask security on 040 2345 6789 to attend.").rec("p") }).errors.any { it.contains("phone number") })
        val contact = pack { node("contact:1", NodeType.EMERGENCY_CONTACT, "c", props("teamId" to "team:ops", "number" to "040 2345 6789", "enabled" to true)) }
        assertTrue(GraphValidator.validate(contact).errors.any { it.contains("enabled without verification") })
    }

    @Test fun theProductionRuleNoApprovedWithoutSourceReviewerAndTime() {
        assertTrue(GraphValidator.validate(pack { action("n").rec("n", source = null) }).errors.any { it.contains("without a source") })
        assertTrue(GraphValidator.validate(pack { action("n").rec("n", approvedBy = null) }).errors.any { it.contains("without a reviewer") })
        assertTrue(GraphValidator.validate(pack { action("n").rec("n", approvedAt = null) }).errors.any { it.contains("without an approval time") })
        assertTrue(GraphValidator.validate(pack { action("n").rec("n", expiresAt = "someday") }).errors.any { it.contains("expiresAt") })
        assertTrue(GraphValidator.validate(pack { action("n").rec("n", status = ApprovalStatus.DRAFT, source = null, approvedBy = null, approvedAt = null) }).ok)
    }

    @Test fun importInstallsTheNextVersionAndActivatesIt() {
        val a = admin()
        val v2 = PackBuilder.basic("isolate", "other").also { it.version = 99 }.build()
        val result = a.importPack(GraphPackCodec.encode(v2))
        assertEquals(2, result.version); assertEquals(2, a.activeVersion()); assertEquals(listOf(1, 2), a.versions())
        assertEquals(2, engineFor(GraphPackCodec.decode(a.exportPack())).recommend(incident()).graphVersion)
    }

    // ---- admin edits, approval, rollback ------------------------------------------------------------

    @Test fun approvingNeedsAReviewerAnApprovedSourceAndSetsTheApprovalTime() {
        val draftPack = PackBuilder().incidentType("FIRE").team("ops").action("a").rec("a", status = ApprovalStatus.DRAFT, approvedBy = null, approvedAt = null).trigger("FIRE", "a").build()
        val a = admin(draftPack)
        try { a.approveRecommendation("rec:a", " "); throw AssertionError("needs reviewer") } catch (_: AdminException) { }
        val v = a.approveRecommendation("rec:a", "Dr Reviewer", expiresAt = "2027-01-01T00:00:00Z")
        assertEquals(2, v)
        val rec = GraphPackCodec.decode(a.exportPack()).nodes.first { it.id == "rec:a" }
        assertEquals("APPROVED", rec.str("approvalStatus")); assertEquals("Dr Reviewer", rec.str("approvedBy")); assertEquals(KG_NOW.toString(), rec.str("approvedAt")); assertEquals("2027-01-01T00:00:00Z", rec.str("expiresAt"))
        assertEquals(listOf("rec:a"), a.preview(incident(), Audience.REPORTER).ids())
    }

    @Test fun approvingWithoutASourceOrWithAnUnapprovedOrExpiredSourceIsRefused() {
        val noSource = PackBuilder().incidentType("FIRE").action("a").rec("a", status = ApprovalStatus.DRAFT, source = null, approvedBy = null, approvedAt = null).build()
        try { admin(noSource).approveRecommendation("rec:a", "R"); throw AssertionError("no source") } catch (e: AdminException) { assertTrue(e.message!!.contains("without a source")) }
        val unverified = PackBuilder().incidentType("FIRE").source(KgSource("sop:u", "u")).action("a").rec("a", status = ApprovalStatus.DRAFT, source = "sop:u", approvedBy = null, approvedAt = null).build()
        try { admin(unverified).approveRecommendation("rec:a", "R"); throw AssertionError("unverified source") } catch (e: AdminException) { assertTrue(e.message!!.contains("NEEDS_ADMIN_VERIFICATION")) }
        val expired = PackBuilder().incidentType("FIRE").source(KgSource("sop:e", "e", approvedBy = "R", approvedAt = OK_AT, expiresAt = "2026-01-01T00:00:00Z", status = SourceStatus.APPROVED)).action("a")
            .rec("a", status = ApprovalStatus.DRAFT, source = "sop:e", approvedBy = null, approvedAt = null).build()
        try { admin(expired).approveRecommendation("rec:a", "R"); throw AssertionError("expired source") } catch (e: AdminException) { assertTrue(e.message!!.contains("expired")) }
        try { admin().approveRecommendation("action:isolate", "R"); throw AssertionError("not a rec") } catch (_: AdminException) { }
    }

    @Test fun attachingAnApprovedSourceThenApprovingMakesGuidanceVisibleToReporters() {
        val p = PackBuilder().incidentType("FIRE").team("ops").action("a").rec("a", status = ApprovalStatus.DRAFT, source = null, approvedBy = null, approvedAt = null).trigger("FIRE", "a").build()
        val a = admin(p)
        assertTrue(a.preview(incident(), Audience.REPORTER).ids().isEmpty())
        a.attachSource(KgSource("sop:new", "Fire SOP v2", approvedBy = "Chief Warden", approvedAt = OK_AT, expiresAt = "2027-06-01T00:00:00Z", status = SourceStatus.APPROVED, displayLabel = "Fire SOP v2"), "rec:a")
        assertEquals(listOf("rec:a"), a.preview(incident(), Audience.ADMIN_PREVIEW).ids())
        assertTrue(a.preview(incident(), Audience.REPORTER).ids().isEmpty())
        a.approveRecommendation("rec:a", "Chief Warden")
        val set = a.preview(incident(), Audience.REPORTER)
        assertEquals(listOf("rec:a"), set.ids()); assertEquals("Fire SOP v2", set.guidanceLabel)
        assertTrue(a.validate().ok)
    }

    @Test fun anExpiredSourceAttachedByTheAdminHidesTheGuidanceAgain() {
        val a = admin()
        a.attachSource(KgSource("sop:ok", "Approved SOP", approvedBy = "R", approvedAt = OK_AT, expiresAt = "2026-10-11T05:00:00Z", status = SourceStatus.APPROVED))
        assertTrue(a.preview(incident(), Audience.REPORTER).ids().isEmpty())
        assertTrue(a.preview(incident(), Audience.ADMIN_PREVIEW).ids().isEmpty())
    }

    @Test fun editsCreateNewVersionsAndRollbackRestoresTheOldBehaviour() {
        val a = admin()
        assertEquals(listOf("rec:isolate"), a.preview(incident(), Audience.REPORTER).ids())
        val v2 = a.disable("rec:isolate")
        assertEquals(2, v2)
        assertTrue(a.preview(incident(), Audience.REPORTER).ids().isEmpty())
        assertEquals(2, a.preview(incident(), Audience.REPORTER).graphVersion)
        a.rollback(1)
        assertEquals(1, a.activeVersion()); assertEquals(listOf("rec:isolate"), a.preview(incident(), Audience.REPORTER).ids())
        assertEquals(listOf(1, 2), a.versions())
        val v3 = a.upsertNode(com.example.test_app.knowledgegraph.model.KgNode("team:extra", NodeType.RESPONSE_TEAM, "extra", props("role" to "EXTRA")))
        assertEquals(3, v3)
        try { a.rollback(42); throw AssertionError("unknown version") } catch (_: AdminException) { }
    }

    @Test fun disablingAnEdgeOrNodeAndUnknownIdsBehave() {
        val a = admin()
        val edgeId = a.exportPack().let { GraphPackCodec.decode(it).edges.first { e -> e.relation == Relation.TRIGGERS_ACTION }.id }
        a.disable(edgeId)
        assertTrue(a.preview(incident(), Audience.REPORTER).ids().isEmpty())
        try { a.disable("nothing"); throw AssertionError("unknown id") } catch (_: AdminException) { }
    }

    @Test fun anEditThatBreaksThePackIsRefusedAndTheActiveVersionStays() {
        val a = admin()
        try { a.upsertEdge(KgEdge("edge:bad", "action:isolate", Relation.OWNED_BY, "team:ghost")); throw AssertionError("dangling") } catch (e: AdminException) { assertTrue(e.report!!.errors.isNotEmpty()) }
        assertEquals(1, a.activeVersion()); assertEquals(listOf(1), a.versions())
    }

    @Test fun verifyingTeamsAndLocationsRecordsWhoAndWhen() {
        val p = PackBuilder.basic("a").node("building:krb", NodeType.BUILDING, "KRB").build()
        val a = admin(p)
        a.verifyTeam("team:ops", "Admin A")
        a.verifyLocation("building:krb", "Admin A")
        val out = GraphPackCodec.decode(a.exportPack())
        val team = out.nodes.first { it.id == "team:ops" }
        assertEquals(VerificationStatus.VERIFIED, team.verificationStatus); assertEquals("Admin A", team.str("verifiedBy")); assertEquals(KG_NOW.toString(), team.str("verifiedAt"))
        assertEquals(VerificationStatus.VERIFIED, out.nodes.first { it.id == "building:krb" }.verificationStatus)
        try { a.verifyLocation("team:ops", "x"); throw AssertionError("wrong type") } catch (_: AdminException) { }
        try { a.verifyTeam("team:ops", ""); throw AssertionError("needs name") } catch (_: AdminException) { }
        assertTrue(a.preview(incident(), Audience.REPORTER).responsibleTeams.single().verified)
    }

    @Test fun thePreviewShowsDraftsForAdminsAndWritesNoAuditLog() {
        val a = AdminGraphService(InMemoryGraphRepository(PackBuilder.basic("a").action("d").rec("d", status = ApprovalStatus.DRAFT).trigger("FIRE", "d").build())) { KG_NOW }
        val preview = a.preview(incident())
        assertEquals(Audience.ADMIN_PREVIEW, preview.audience)
        assertEquals(setOf("rec:a", "rec:d"), preview.ids().toSet())
        assertEquals(setOf(false, true), preview.allRecommendations.map { it.unverifiedDraft }.toSet())
    }

    // ---- file repository -----------------------------------------------------------------------------

    @Test fun theFileRepositoryInstallsTheSeedOnceAndSurvivesRestarts() {
        val dir = tmp.newFolder("kg")
        val seed = GraphPackCodec.encode(pack())
        val repo = FileGraphRepository(dir) { seed }
        assertEquals(1, repo.activePack().packVersion); assertEquals(listOf(1), repo.versions())
        val again = FileGraphRepository(dir) { error("the seed must not be read again") }
        assertEquals(1, again.activePack().packVersion)
    }

    @Test fun rollbackSurvivesARestartAndADamagedPointerFallsBackToTheNewestPack() {
        val dir = tmp.newFolder("kg2")
        val repo = FileGraphRepository(dir) { GraphPackCodec.encode(pack()) }
        repo.activePack()
        repo.save(PackBuilder.basic("isolate", "x").also { it.version = 2 }.build())
        repo.activate(2)
        repo.activate(1)
        assertEquals(1, FileGraphRepository(dir) { error("no seed") }.activePack().packVersion)
        java.io.File(dir, "active.txt").writeText("garbage")
        assertEquals(2, FileGraphRepository(dir) { error("no seed") }.activePack().packVersion)
        try { repo.save(pack()); throw AssertionError("version 1 exists") } catch (_: IllegalStateException) { }
        try { repo.activate(77); throw AssertionError("unknown") } catch (_: IllegalArgumentException) { }
    }

    @Test fun aDamagedPackFileIsSkipped() {
        val dir = tmp.newFolder("kg3")
        val repo = FileGraphRepository(dir) { GraphPackCodec.encode(pack()) }
        repo.activePack()
        java.io.File(dir, "packs/2.json").writeText("{ broken")
        assertNull(repo.load(2))
        assertEquals(1, FileGraphRepository(dir) { error("no seed") }.activePack().packVersion)
    }

    @Test fun theEngineSeesAnActivationAtOnce() {
        val repo = InMemoryGraphRepository(pack())
        repo.save(PackBuilder.basic("isolate", "second").also { it.version = 2 }.build())
        val service = AdminGraphService(repo) { KG_NOW }
        assertEquals(1, service.preview(incident()).ids().size)
        service.rollback(2)
        assertEquals(2, service.preview(incident()).ids().size)
        assertEquals(Instant.parse("2026-10-11T06:00:00Z"), KG_NOW)
    }
}
