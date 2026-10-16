package com.example.test_app.admin

import com.example.test_app.admin.AdminTestSupport.auditOf
import com.example.test_app.admin.AdminTestSupport.report
import com.example.test_app.admin.AdminTestSupport.scenarioIncident
import com.example.test_app.admin.AdminTestSupport.snapshot
import com.example.test_app.knowledgegraph.admin.ValidationReport
import com.example.test_app.knowledgegraph.admin.graphview.GraphViewOptions
import com.example.test_app.knowledgegraph.admin.graphview.PathHighlight
import com.example.test_app.knowledgegraph.model.Audience
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure state transitions of the admin dashboard (the view model feeds results in as actions). */
class AdminReducerTest {
    private fun r(s: AdminUiState, a: AdminAction) = AdminReducer.reduce(s, a)
    private val log = auditOf(scenarioIncident(2))
    private val loaded: AdminUiState = r(AdminUiState(), AdminAction.Loaded(snapshot(listOf(report(log.incidentId)), listOf(log)), OverviewStats.compute(snapshot(), AdminTestSupport.now)))

    @Test fun theDashboardStartsLockedOnTheOverview() {
        val s = AdminUiState()
        assertFalse(s.unlocked); assertEquals(AdminTab.OVERVIEW, s.tab); assertNull(s.snapshot)
        assertTrue(r(s, AdminAction.Unlock).unlocked)
    }

    @Test fun lockingForgetsEverythingLoadedButKeepsTheSettings() {
        val settings = AnalysisSettings(immediate = 5)
        val s = r(r(AdminUiState(analysisSettings = settings, alsoDeleteKgFiles = false), AdminAction.Unlock), AdminAction.Lock)
        assertFalse(s.unlocked); assertNull(s.snapshot); assertEquals(settings, s.analysisSettings); assertFalse(s.alsoDeleteKgFiles)
        assertFalse(r(loaded, AdminAction.Lock).snapshot != null)
    }

    @Test fun loadingAndLoadedToggleTheSpinnerAndStoreTheData() {
        val s = r(AdminUiState(), AdminAction.Loading)
        assertTrue(s.loading)
        val done = r(s, AdminAction.Loaded(snapshot(), OverviewStats.compute(snapshot(), AdminTestSupport.now)))
        assertFalse(done.loading); assertTrue(done.snapshot != null && done.overview != null)
    }

    @Test fun aReloadDropsTheSelectedReportWhenItIsGone() {
        val open = r(loaded, AdminAction.OpenReport(log.incidentId))
        assertEquals(log.incidentId, open.selectedReportId)
        val reloaded = r(open, AdminAction.Loaded(snapshot(), loaded.overview!!))
        assertNull(reloaded.selectedReportId)
        assertEquals(log.incidentId, r(open, AdminAction.Loaded(loaded.snapshot!!, loaded.overview!!)).selectedReportId)
    }

    @Test fun selectingATabClosesAnOpenReportUnlessItIsTheReportsTab() {
        val open = r(loaded, AdminAction.OpenReport("X"))
        assertEquals(AdminTab.REPORTS, open.tab)
        assertEquals("X", r(open, AdminAction.SelectTab(AdminTab.REPORTS)).selectedReportId)
        assertNull(r(open, AdminAction.SelectTab(AdminTab.LOGS)).selectedReportId)
        assertEquals(AdminTab.REPORTS, r(AdminUiState(tab = AdminTab.LOGS), AdminAction.OpenReport("Y")).tab)
        assertEquals(AdminTab.LOGS, r(AdminUiState(tab = AdminTab.LOGS), AdminAction.OpenReport(null)).tab)
    }

    @Test fun filtersAreStored() {
        val f = ReportFilter(type = "Fire", query = "lab")
        assertEquals(f, r(AdminUiState(), AdminAction.SetReportFilter(f)).reportFilter)
        val lf = LogFilter(kind = LogKind.DIALOGUE, query = "x")
        assertEquals(lf, r(AdminUiState(), AdminAction.SetLogFilter(lf)).logFilter)
    }

    @Test fun switchingTheLogKindResetsTheLevelAndTypeFilters() {
        val s = r(AdminUiState(), AdminAction.SetLogFilter(LogFilter(LogKind.AUDIT, "q", LogLevel.WARN, "COMPUTER_FIRE")))
        val moved = r(s, AdminAction.SetLogFilter(s.logFilter.copy(kind = LogKind.DIALOGUE)))
        assertEquals(LogFilter(LogKind.DIALOGUE, "q", null, null), moved.logFilter)
        assertEquals(LogLevel.WARN, r(s, AdminAction.SetLogFilter(s.logFilter.copy(query = "z"))).logFilter.level)
    }

    @Test fun showInGraphOpensTheGraphTabWithTheIncidentPathAndFocus() {
        val s = r(loaded, AdminAction.ShowIncidentInGraph(log.incidentId))
        assertEquals(AdminTab.GRAPH, s.tab); assertEquals(HighlightMode.INCIDENT_PATH, s.graph.mode)
        assertTrue(s.graph.highlight.nodeIds.isNotEmpty()); assertEquals("incident-type:computer-fire", s.graph.options.focusNodeId)
        assertFalse(s.graph.options.local); assertTrue(s.graph.highlightLabel!!.contains(log.incidentId)); assertNull(s.graph.selectedNodeId)
    }

    @Test fun showInGraphWithoutAnAnalysisOnlyGivesANotice() {
        val s = r(loaded, AdminAction.ShowIncidentInGraph("NO-AUDIT"))
        assertEquals(loaded.tab, s.tab); assertEquals(HighlightMode.NONE, s.graph.mode); assertTrue(s.notice!!.contains("no analysis"))
        assertTrue(r(AdminUiState(), AdminAction.ShowIncidentInGraph("X")).notice != null)
    }

    @Test fun clearHighlightKeepsTheFilters() {
        val s = r(loaded, AdminAction.UpdateGraph { it.copy(options = GraphViewOptions(query = "fire")) })
        val shown = r(s, AdminAction.ShowIncidentInGraph(log.incidentId))
        val cleared = r(shown, AdminAction.ClearHighlight)
        assertEquals(HighlightMode.NONE, cleared.graph.mode); assertTrue(cleared.graph.highlight.isEmpty); assertEquals("fire", cleared.graph.options.query)
    }

    @Test fun selectingANodeStartsTheNeighbourhoodHighlightAndDeselectingEndsIt() {
        val sel = r(AdminUiState(), AdminAction.SelectNode("hazard:smoke"))
        assertEquals("hazard:smoke", sel.graph.selectedNodeId); assertEquals(HighlightMode.NEIGHBOURHOOD, sel.graph.mode)
        val none = r(sel, AdminAction.SelectNode(null))
        assertNull(none.graph.selectedNodeId); assertEquals(HighlightMode.NONE, none.graph.mode)
    }

    @Test fun selectingANodeKeepsAnIncidentHighlight() {
        val shown = r(loaded, AdminAction.ShowIncidentInGraph(log.incidentId))
        val sel = r(shown, AdminAction.SelectNode("hazard:smoke"))
        assertEquals(HighlightMode.INCIDENT_PATH, sel.graph.mode); assertEquals(HighlightMode.INCIDENT_PATH, r(sel, AdminAction.SelectNode(null)).graph.mode)
    }

    @Test fun graphOptionsAreUpdatedByATransform() {
        val s = r(AdminUiState(), AdminAction.UpdateGraph { it.copy(options = it.options.copy(localDepth = 3, hiddenKinds = setOf("ACTION")), showSuppressed = false) })
        assertEquals(3, s.graph.options.localDepth); assertEquals(setOf("ACTION"), s.graph.options.hiddenKinds); assertFalse(s.graph.showSuppressed)
    }

    @Test fun previewResultsFeedTheGraphHighlight() {
        val noPreview = r(loaded, AdminAction.ShowPreviewInGraph(false))
        assertTrue(noPreview.notice!!.contains("preview")); assertEquals(AdminTab.OVERVIEW, noPreview.tab)
        val i = scenarioIncident(2)
        val result = PreviewResult(
            i, AdminTestSupport.engine().recommend(i, Audience.REPORTER), AdminTestSupport.engine().recommend(i, Audience.ADMIN_PREVIEW),
            com.example.test_app.knowledgegraph.admin.graphview.IncidentPath.fromPreview(AdminTestSupport.seedPack, i, AdminTestSupport.engine().recommend(i, Audience.REPORTER)),
            PathHighlight(setOf("a")),
        )
        val withPreview = r(loaded, AdminAction.PreviewDone(result))
        assertEquals(result, withPreview.editor.preview)
        val rep = r(withPreview, AdminAction.ShowPreviewInGraph(false))
        assertEquals(AdminTab.GRAPH, rep.tab); assertEquals(HighlightMode.PREVIEW_REPORTER, rep.graph.mode); assertEquals(result.reporterPath, rep.graph.highlight)
        val adm = r(withPreview, AdminAction.ShowPreviewInGraph(true))
        assertEquals(HighlightMode.PREVIEW_ADMIN, adm.graph.mode); assertEquals(setOf("a"), adm.graph.highlight.nodeIds)
    }

    @Test fun editorStateTransitions() {
        val s = r(r(AdminUiState(), AdminAction.SetEditorQuery("fire")), AdminAction.SetEditorSection(EditorSection.SOURCES))
        assertEquals(EditorSection.SOURCES, s.editor.section); assertEquals("", s.editor.query) // a new section starts with a clean search
        assertEquals("x", r(s, AdminAction.SetEditorQuery("x")).editor.query)
        val spec = PreviewSpec(type = "FIRE")
        assertEquals(spec, r(s, AdminAction.SetPreviewSpec(spec)).editor.previewSpec)
        val v = ValidationReport(listOf("bad"), emptyList())
        assertEquals(v, r(s, AdminAction.Validated(v)).editor.validation)
    }

    @Test fun importNoticeBusyAndSettingsActions() {
        val pending = PendingImport("{}", null, listOf("bad"), emptyList())
        assertFalse(pending.canConfirm)
        assertEquals(pending, r(AdminUiState(), AdminAction.ImportPrepared(pending)).pendingImport)
        assertNull(r(r(AdminUiState(), AdminAction.ImportPrepared(pending)), AdminAction.ImportPrepared(null)).pendingImport)
        assertEquals("hi", r(AdminUiState(), AdminAction.Notice("hi")).notice); assertNull(r(r(AdminUiState(), AdminAction.Notice("hi")), AdminAction.Notice(null)).notice)
        assertEquals("work", r(AdminUiState(), AdminAction.Busy("work")).busy)
        assertEquals(AnalysisSettings(immediate = 4), r(AdminUiState(), AdminAction.SettingsChanged(AnalysisSettings(immediate = 4))).analysisSettings)
        assertFalse(r(AdminUiState(), AdminAction.AlsoDeleteKg(false)).alsoDeleteKgFiles)
        assertTrue(PendingImport("{}", ImportDiff.compute(AdminTestSupport.seedPack, AdminTestSupport.seedPack), emptyList(), emptyList()).canConfirm)
    }

    @Test fun everyTabHasALabel() {
        assertEquals(listOf("Overview", "Reports", "Logs", "Graph", "Graph data", "Outbox", "Settings"), AdminTab.entries.map { it.label })
    }
}
