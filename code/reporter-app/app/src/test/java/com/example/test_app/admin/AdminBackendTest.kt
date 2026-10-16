package com.example.test_app.admin

import com.example.test_app.admin.AdminTestSupport.report
import com.example.test_app.knowledgegraph.KnowledgeGraphService
import com.example.test_app.knowledgegraph.admin.AdminGraphService
import com.example.test_app.knowledgegraph.repository.FileAnalysisOutbox
import com.example.test_app.knowledgegraph.repository.FileAuditStore
import com.example.test_app.knowledgegraph.repository.InMemoryGraphRepository
import com.example.test_app.platform.JsonFileReportRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The admin backend over REAL file stores in a temp folder: snapshot, clearing, demo data (which must never touch real reports). */
class AdminBackendTest {
    @get:Rule val tmp = TemporaryFolder()

    private inner class Fixture {
        val root: File = tmp.newFolder()
        val reports = JsonFileReportRepository(File(root, "reports"))
        val audits = FileAuditStore(File(root, "kg/audit"))
        val outbox = FileAnalysisOutbox(File(root, "kg/outbox"))
        val dialogueDir = File(root, "dialogue_logs")
        val evalDir = File(root, "eval_runs")
        val repo = InMemoryGraphRepository(AdminTestSupport.seedPack)
        val service = KnowledgeGraphService(repo, audits, outbox, clock = { AdminTestSupport.now })
        val backend = AdminBackend(reports, audits, dialogueDir, evalDir, repo, AdminGraphService(repo) { AdminTestSupport.now }, outbox, service) { AdminTestSupport.now }
    }

    @Test fun anEmptyInstallLoadsAnEmptySnapshotWithTheActiveGraph() = runTest {
        val s = Fixture().backend.loadSnapshot()
        assertTrue(s.reports.isEmpty() && s.audits.isEmpty() && s.outbox.isEmpty() && s.dialogueLogs.isEmpty() && s.evalRuns.isEmpty())
        assertEquals(AdminTestSupport.seedPack.packVersion, s.pack.packVersion); assertEquals(listOf(1), s.versions); assertEquals(AdminTestSupport.now, s.loadedAt)
    }

    @Test fun theSnapshotReadsReportsAuditOutboxAndDialogueLogs() = runTest {
        val f = Fixture()
        val r = report("INC-1", "Fire", "High", place = "KRB computer lab", description = "A desktop computer caught fire in the computer lab")
        f.reports.save(r)
        f.backend.analyzeReport(r)
        f.dialogueDir.mkdirs(); File(f.dialogueDir, "INC-1.jsonl").writeText("{\"state\":\"GREETING\"}\n{\"state\":\"READBACK\"}\n")
        val s = f.backend.loadSnapshot()
        assertEquals(listOf("INC-1"), s.reports.map { it.reportId }); assertNotNull(s.auditFor("INC-1")); assertNotNull(s.exportFor("INC-1"))
        assertEquals(2, s.dialogueFor("INC-1")!!.lines.size); assertEquals(null, s.dialogueFor("none"))
    }

    @Test fun evalRunsAreReadFromTheirResultsFileAndDamagedOnesAreSkipped() = runTest {
        val f = Fixture()
        File(f.evalDir, "broken").mkdirs(); File(f.evalDir, "broken/results.json").writeText("{ not json")
        File(f.evalDir, "empty").mkdirs()
        assertTrue(f.backend.loadSnapshot().evalRuns.isEmpty())
    }

    @Test fun clearingEachKindDeletesOnlyThatKindAndReportsTheCount() = runTest {
        val f = Fixture()
        val r = report("INC-1", description = "A desktop computer caught fire in the computer lab", place = "KRB computer lab"); f.reports.save(r); f.backend.analyzeReport(r)
        f.dialogueDir.mkdirs(); File(f.dialogueDir, "INC-1.jsonl").writeText("{}\n"); File(f.dialogueDir, "INC-2.jsonl").writeText("{}\n")
        assertEquals(2, f.backend.clear(LogKind.DIALOGUE))
        assertEquals(1, f.backend.clear(LogKind.OUTBOX)); assertTrue(f.outbox.pending().isEmpty()); assertEquals(1, f.audits.all().size)
        assertEquals(1, f.backend.clear(LogKind.AUDIT)); assertTrue(f.audits.all().isEmpty())
        assertEquals(0, f.backend.clear(LogKind.EVAL))
        assertEquals(listOf("INC-1"), f.reports.list().map { it.reportId }) // reports are never touched by clearing logs
    }

    @Test fun deletingKgFilesOfReportsRemovesAuditAndOutboxOfThoseIdsOnly() = runTest {
        val f = Fixture()
        for (id in listOf("INC-1", "INC-2")) { val r = report(id, description = "A desktop computer caught fire in the computer lab", place = "KRB computer lab"); f.reports.save(r); f.backend.analyzeReport(r) }
        assertEquals(2, f.backend.deleteKgFilesFor(listOf("INC-1")))
        assertEquals(listOf("INC-2"), f.audits.all().map { it.incidentId }); assertEquals(listOf("INC-2"), f.outbox.pending())
        assertEquals(0, f.backend.deleteKgFilesFor(listOf("INC-404")))
    }

    // ---- demo data ----

    @Test fun demoGenerationIsDeterministicDatedAndPrefixed() {
        val a = DemoData.generate(AdminTestSupport.now); val b = DemoData.generate(AdminTestSupport.now)
        assertEquals(a, b); assertEquals(30, a.size)
        assertTrue(a.all { it.reportId.startsWith("DEMO-") && DemoData.isDemo(it) && it.status == "submitted" })
        assertEquals((1..30).map { String.format("DEMO-%04d", it) }, a.map { it.reportId })
        assertEquals(30, a.map { it.reportId }.toSet().size)
        val times = a.mapNotNull { OverviewStats.reportInstant(it) }
        assertEquals(30, times.size); assertTrue(times.all { it.isBefore(AdminTestSupport.now) && it.isAfter(AdminTestSupport.now.minusSeconds(60L * 86_400)) })
        assertTrue(OverviewStats.compute(AdminTestSupport.snapshot(a), AdminTestSupport.now).perDay.sumOf { it.count } > 0)
    }

    @Test fun demoReportsAreVariedAndSomeRepeatInOnePlace() {
        val a = DemoData.generate(AdminTestSupport.now)
        assertTrue(a.map { OverviewStats.reportType(it) }.toSet().size >= 6); assertTrue(a.map { OverviewStats.reportSeverity(it) }.toSet().containsAll(setOf("High", "Medium", "Low")))
        val leaks = a.take(4)
        assertTrue(leaks.all { OverviewStats.reportType(it) == "Water Leak" && it.fields["incident_location"]!!.value.toString().contains("KRB washroom") })
        assertEquals(0, DemoData.generate(AdminTestSupport.now, 0).size); assertEquals(5, DemoData.generate(AdminTestSupport.now, 5).size); assertEquals(200, DemoData.generate(AdminTestSupport.now, 5000).size)
    }

    @Test fun onlyDemoIdsCountAsDemo() {
        assertTrue(DemoData.isDemoId("DEMO-0001")); assertFalse(DemoData.isDemoId("INC-2026-000001")); assertFalse(DemoData.isDemoId("demo-1")); assertFalse(DemoData.isDemo(report("INC-DEMO-1")))
    }

    @Test fun generatingDemoHistoryWritesReportsAnalysesAndTheRecurrenceFires() = runTest {
        val f = Fixture()
        assertEquals(30, f.backend.generateDemo())
        assertEquals(30, f.reports.list().size); assertEquals(30, f.audits.all().size); assertEquals(30, f.outbox.pending().size)
        val s = f.backend.loadSnapshot()
        assertTrue("recurrence history is cited by some analysis", s.audits.any { it.historyCitations.isNotEmpty() })
        assertTrue(s.audits.any { it.recommendationIds.contains("rec:inspect-shared-plumbing") })
        assertTrue(s.audits.all { !it.abstained || it.suppressed.isNotEmpty() })
        assertEquals(30, OverviewStats.compute(s, AdminTestSupport.now).demoReports)
    }

    @Test fun removingDemoDataNeverTouchesRealReportsOrTheirFiles() = runTest {
        val f = Fixture()
        val real = report("INC-2026-000777", "Fire", "High", description = "A desktop computer caught fire in the computer lab", place = "KRB computer lab")
        f.reports.save(real); f.backend.analyzeReport(real)
        f.dialogueDir.mkdirs(); File(f.dialogueDir, "INC-2026-000777.jsonl").writeText("{}\n"); File(f.dialogueDir, "DEMO-0001.jsonl").writeText("{}\n")
        f.backend.generateDemo()
        assertEquals(31, f.reports.list().size)
        assertEquals(30, f.backend.removeDemo())
        assertEquals(listOf("INC-2026-000777"), f.reports.list().map { it.reportId })
        assertEquals(listOf("INC-2026-000777"), f.audits.all().map { it.incidentId }); assertEquals(listOf("INC-2026-000777"), f.outbox.pending())
        assertTrue(File(f.dialogueDir, "INC-2026-000777.jsonl").isFile); assertFalse(File(f.dialogueDir, "DEMO-0001.jsonl").exists())
        assertEquals(0, f.backend.removeDemo()) // nothing left to remove
    }

    @Test fun demoGenerationAddsToExistingRealReportsAndHistory() = runTest {
        val f = Fixture()
        f.reports.save(report("INC-REAL", "Water Leak", "Low", place = "KRB washroom, third floor", description = "pipe leak in the washroom", createdAt = "2026-10-09T08:00:00Z"))
        f.backend.generateDemo(10)
        assertEquals(11, f.reports.list().size); assertTrue(f.reports.list().any { it.reportId == "INC-REAL" })
    }

    @Test fun deletingOneReportOnlyRemovesThatFile() = runTest {
        val f = Fixture()
        f.reports.save(report("A")); f.reports.save(report("B"))
        assertTrue(f.reports.delete("A")); assertFalse(f.reports.delete("A")); assertFalse(f.reports.delete("")); assertFalse(f.reports.delete("../x"))
        assertEquals(listOf("B"), f.reports.list().map { it.reportId })
    }

    @Test fun aPreviewRunsForBothAudiencesAndWritesNoAuditLog() = runTest {
        val f = Fixture()
        val p = f.backend.preview(PreviewSpec(type = "COMPUTER_FIRE", locationType = "COMPUTER_LAB", assets = listOf("DESKTOP"), status = com.example.test_app.knowledgegraph.model.IncidentStatus.CONTAINED))
        assertTrue(p.reporter.allRecommendations.isNotEmpty()); assertTrue(p.admin.allRecommendations.size > p.reporter.allRecommendations.size)
        assertTrue(p.admin.allRecommendations.any { it.unverifiedDraft }); assertTrue(p.reporter.allRecommendations.none { it.unverifiedDraft })
        assertTrue(p.adminPath.nodeIds.containsAll(p.reporterPath.nodeIds)); assertEquals("incident-type:computer-fire", p.reporterPath.rootNodeId)
        assertTrue(f.audits.all().isEmpty() && f.outbox.pending().isEmpty())
    }
}
