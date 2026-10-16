package com.example.test_app.admin

import com.example.test_app.admin.AdminTestSupport.report
import com.example.test_app.knowledgegraph.KnowledgeGraphService
import com.example.test_app.knowledgegraph.admin.AdminGraphService
import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import com.example.test_app.knowledgegraph.repository.FileAnalysisOutbox
import com.example.test_app.knowledgegraph.repository.FileAuditStore
import com.example.test_app.knowledgegraph.repository.InMemoryGraphRepository
import com.example.test_app.platform.JsonFileReportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The admin view model over real temp stores (the main dispatcher is replaced by an unconfined test one). */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun before() = Dispatchers.setMain(UnconfinedTestDispatcher())

    // The view model's coroutines finish on IO threads and resume on Main AFTER the test body: a resetMain() here made them throw
    // "Dispatchers.Main was accessed when the platform dispatcher was absent" on a DefaultDispatcher worker, which failed whichever test of
    // another class ran next (UncaughtExceptionsBeforeTest). The unconfined test dispatcher therefore stays installed (harmless for other tests).

    private class Rig(root: File) {
        val reports = JsonFileReportRepository(File(root, "reports"))
        val audits = FileAuditStore(File(root, "kg/audit"))
        val outbox = FileAnalysisOutbox(File(root, "kg/outbox"))
        val repo = InMemoryGraphRepository(AdminTestSupport.seedPack)
        val service = KnowledgeGraphService(repo, audits, outbox, clock = { AdminTestSupport.now })
        val admin = AdminGraphService(repo) { AdminTestSupport.now }
        val backend = AdminBackend(reports, audits, File(root, "dlg"), File(root, "eval"), repo, admin, outbox, service) { AdminTestSupport.now }
        val prefs = InMemoryAdminPrefs()
        var saved = 0
        val vm = AdminViewModel(backend, prefs, { AdminTestSupport.now }, { saved++ })
    }

    private fun rig() = Rig(tmp.newFolder())

    /** The view model works on IO threads: wait (polling) until the state satisfies [pred]. */
    private fun AdminViewModel.await(what: String = "state", pred: (AdminUiState) -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!pred(state.value)) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what; notice=${state.value.notice}")
            Thread.sleep(15)
        }
    }

    @Test fun theWrongPinStaysLockedTheRightOneUnlocksAndLoads() {
        val r = rig()
        assertFalse(r.vm.unlock("1234")); assertFalse(r.vm.state.value.unlocked)
        assertTrue(r.vm.unlock("0000")); assertTrue(r.vm.state.value.unlocked)
        r.vm.await("load") { it.snapshot != null && !it.loading }
        assertNotNull(r.vm.state.value.snapshot); assertNotNull(r.vm.state.value.overview); assertFalse(r.vm.state.value.loading)
    }

    @Test fun changingThePinPersistsAndTheOldOneStopsWorking() {
        val r = rig()
        assertTrue(r.vm.changePin("0000", "4321", "4321") is AdminPin.Change.Ok)
        assertEquals("4321", r.prefs.pin); assertFalse(r.vm.unlock("0000")); assertTrue(r.vm.unlock("4321"))
        assertTrue(r.vm.changePin("0000", "1111", "1111") is AdminPin.Change.Rejected); assertEquals("4321", r.prefs.pin)
    }

    @Test fun lockingDropsTheLoadedData() {
        val r = rig(); r.vm.unlock("0000"); r.vm.await("load") { it.snapshot != null }; r.vm.lock()
        assertFalse(r.vm.state.value.unlocked); assertNull(r.vm.state.value.snapshot)
    }

    @Test fun settingsStartFromTheStoredValuesAndSaveOnlyWhenValid() {
        val r = rig()
        r.prefs.analysisSettingsJson = AnalysisSettings.encode(AnalysisSettings(immediate = 5)); r.prefs.alsoDeleteKgFiles = false
        val vm = AdminViewModel(r.backend, r.prefs, { AdminTestSupport.now }, { r.saved++ })
        assertEquals(5, vm.state.value.analysisSettings.immediate); assertFalse(vm.state.value.alsoDeleteKgFiles)
        assertTrue(vm.saveAnalysisSettings(AnalysisSettings(abstainBelowEvidence = 2.0)).isNotEmpty())
        assertEquals(5, vm.state.value.analysisSettings.immediate); assertEquals(0, r.saved)
        assertTrue(vm.saveAnalysisSettings(AnalysisSettings(immediate = 2)).isEmpty())
        assertEquals(2, AnalysisSettings.decode(r.prefs.analysisSettingsJson).immediate); assertEquals(1, r.saved)
        vm.resetAnalysisSettings(); assertNull(r.prefs.analysisSettingsJson); assertEquals(AnalysisSettings(), vm.state.value.analysisSettings); assertEquals(2, r.saved)
        vm.setAlsoDeleteKg(true); assertTrue(r.prefs.alsoDeleteKgFiles)
    }

    @Test fun demoGenerationAndRemovalRefreshTheDashboard() {
        val r = rig(); r.vm.unlock("0000"); r.vm.await("load") { it.snapshot != null && !it.loading }
        r.vm.generateDemo()
        r.vm.await("demo") { it.snapshot?.reports?.size == 30 && it.busy == null && !it.loading }
        assertEquals(30, r.vm.state.value.snapshot!!.reports.size); assertEquals(30, r.vm.state.value.overview!!.demoReports); assertTrue(r.vm.state.value.notice!!.contains("30")); assertNull(r.vm.state.value.busy)
        r.vm.removeDemo()
        r.vm.await("removed") { it.snapshot?.reports?.isEmpty() == true && it.busy == null }
        assertEquals(0, r.vm.state.value.snapshot!!.reports.size); assertTrue(r.vm.state.value.notice!!.contains("30"))
    }

    @Test fun analyzingAReportAddsAnAuditLogAndShowInGraphWorks() {
        val r = rig()
        kotlinx.coroutines.runBlocking { r.reports.save(report("INC-9", "Fire", "High", place = "KRB computer lab", description = "A desktop computer caught fire in the computer lab")) }
        r.vm.unlock("0000"); r.vm.await("load") { it.snapshot != null && !it.loading }
        r.vm.showIncidentInGraph("INC-9"); assertTrue(r.vm.state.value.notice!!.contains("no analysis"))
        r.vm.dismissNotice()
        r.vm.analyzeReport("INC-9")
        r.vm.await("analysis") { it.snapshot?.auditFor("INC-9") != null }
        assertNotNull(r.vm.state.value.snapshot!!.auditFor("INC-9"))
        r.vm.showIncidentInGraph("INC-9")
        assertEquals(AdminTab.GRAPH, r.vm.state.value.tab); assertEquals(HighlightMode.INCIDENT_PATH, r.vm.state.value.graph.mode); assertTrue(r.vm.state.value.graph.highlight.nodeIds.isNotEmpty())
    }

    @Test fun logBundlesAndClearing() {
        val r = rig()
        kotlinx.coroutines.runBlocking { r.reports.save(report("INC-9", description = "A desktop computer caught fire in the computer lab", place = "KRB computer lab")) }
        r.vm.unlock("0000"); r.vm.await("load") { it.snapshot != null && !it.loading }; r.vm.analyzeReport("INC-9")
        r.vm.await("analysis") { it.snapshot?.auditFor("INC-9") != null }
        assertTrue(r.vm.bundleJson(LogKind.AUDIT).contains("INC-9")); assertTrue(r.vm.bundleJson(null).contains("\"outbox\""))
        r.vm.clearLogs(LogKind.AUDIT)
        r.vm.await("cleared") { it.snapshot?.audits?.isEmpty() == true }
        assertTrue(r.vm.state.value.snapshot!!.audits.isEmpty()); assertTrue(r.vm.state.value.notice!!.contains("Removed 1"))
        assertEquals("{}", rig().vm.bundleJson()) // nothing loaded yet
    }

    @Test fun importPreviewConfirmAndRollback() {
        val r = rig(); r.vm.unlock("0000"); r.vm.await("load") { it.snapshot != null && !it.loading }
        val edited = AdminTestSupport.seedPack.copy(nodes = AdminTestSupport.seedPack.nodes.drop(1))
        r.vm.prepareImport(GraphPackCodec.encode(edited))
        val pending = r.vm.state.value.pendingImport!!
        assertEquals(1, pending.diff!!.nodesRemoved)
        r.vm.cancelImport(); assertNull(r.vm.state.value.pendingImport)
        r.vm.prepareImport("not json"); assertFalse(r.vm.state.value.pendingImport!!.canConfirm); assertNull(r.vm.state.value.pendingImport!!.diff)
        r.vm.confirmImport(); assertEquals(1, r.vm.state.value.snapshot!!.pack.packVersion) // an unconfirmable import does nothing
        r.vm.prepareImport(GraphPackCodec.encode(AdminTestSupport.seedPack.copy(sources = AdminTestSupport.seedPack.sources + com.example.test_app.knowledgegraph.model.KgSource("sop:extra", "Extra"))))
        r.vm.confirmImport()
        r.vm.await("import") { it.snapshot?.pack?.packVersion == 2 }
        assertEquals(2, r.vm.state.value.snapshot!!.pack.packVersion); assertNull(r.vm.state.value.pendingImport); assertEquals(listOf(1, 2), r.vm.state.value.snapshot!!.versions)
        r.vm.rollback(1); r.vm.await("rollback") { it.snapshot?.pack?.packVersion == 1 }; assertEquals(1, r.vm.state.value.snapshot!!.pack.packVersion)
        assertTrue(r.vm.exportPackJson().contains("sop:demo-seed:v1"))
    }

    @Test fun anInvalidImportShowsItsErrorsAndCannotBeConfirmed() {
        val r = rig(); r.vm.unlock("0000"); r.vm.await("load") { it.snapshot != null && !it.loading }
        val bad = AdminTestSupport.seedPack.copy(edges = AdminTestSupport.seedPack.edges + com.example.test_app.knowledgegraph.model.KgEdge("edge:dangling", "action:ghost", com.example.test_app.knowledgegraph.model.Relation.OWNED_BY, "team:ghost"))
        r.vm.prepareImport(GraphPackCodec.encode(bad))
        val p = r.vm.state.value.pendingImport!!
        assertTrue(p.errors.any { it.contains("ghost") }); assertFalse(p.canConfirm); assertNotNull(p.diff)
    }

    @Test fun editsApproveAndPreviewGoThroughTheService() {
        val r = rig(); r.vm.unlock("0000"); r.vm.await("load") { it.snapshot != null && !it.loading }
        r.vm.disable("rec:report-fire")
        r.vm.await("disable") { it.snapshot?.pack?.packVersion == 2 }
        assertEquals(2, r.vm.state.value.snapshot!!.pack.packVersion); assertTrue(r.vm.state.value.notice!!.contains("Disabled"))
        r.vm.dismissNotice()
        r.vm.approve("rec:ghost", "R", null)
        r.vm.await("refused") { it.notice != null }
        assertTrue(r.vm.state.value.notice!!.isNotBlank()); assertEquals(2, r.vm.state.value.snapshot!!.pack.packVersion)
        r.vm.setPreviewSpec(PreviewSpec(type = "COMPUTER_FIRE", locationType = "COMPUTER_LAB", assets = listOf("DESKTOP")))
        r.vm.runPreview()
        r.vm.await("preview") { it.editor.preview != null }
        val preview = r.vm.state.value.editor.preview!!
        assertTrue(preview.reporter.ids().isNotEmpty() && "rec:report-fire" !in preview.reporter.ids())
        r.vm.showPreviewInGraph(true); assertEquals(HighlightMode.PREVIEW_ADMIN, r.vm.state.value.graph.mode)
        r.vm.validate(); r.vm.await("validation") { it.editor.validation != null }; assertTrue(r.vm.state.value.editor.validation!!.ok)
    }

    private fun com.example.test_app.knowledgegraph.model.RecommendationSet.ids() = allRecommendations.map { it.id }
}
