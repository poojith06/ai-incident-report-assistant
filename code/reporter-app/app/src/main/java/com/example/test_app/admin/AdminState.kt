package com.example.test_app.admin

import com.example.test_app.knowledgegraph.admin.ValidationReport
import com.example.test_app.knowledgegraph.admin.graphview.GraphViewOptions
import com.example.test_app.knowledgegraph.admin.graphview.IncidentPath
import com.example.test_app.knowledgegraph.admin.graphview.PathHighlight

enum class AdminTab(val label: String) { OVERVIEW("Overview"), REPORTS("Reports"), LOGS("Logs"), GRAPH("Graph"), DATA("Graph data"), SYNC("Outbox"), SETTINGS("Settings") }

/** What the graph highlights (ADR-058). */
enum class HighlightMode { NONE, INCIDENT_PATH, PREVIEW_REPORTER, PREVIEW_ADMIN, NEIGHBOURHOOD }

/** State of the Graph tab. [highlight] is derived (never stored in the pack) from an audit log or a preview. */
data class GraphUiState(
    val options: GraphViewOptions = GraphViewOptions(),
    val mode: HighlightMode = HighlightMode.NONE,
    val highlightLabel: String? = null,
    val highlight: PathHighlight = PathHighlight(),
    val selectedNodeId: String? = null,
    val showSuppressed: Boolean = true,
)

/** A pack the admin picked for import and what it would change; [errors] block the import. */
data class PendingImport(val json: String, val diff: PackDiff?, val errors: List<String>, val warnings: List<String>) {
    val canConfirm: Boolean get() = diff != null && errors.isEmpty()
}

/** State of the Graph data tab: the list section and search, the last validation, and the last preview. */
data class EditorUiState(
    val section: EditorSection = EditorSection.NODES,
    val query: String = "",
    val validation: ValidationReport? = null,
    val preview: PreviewResult? = null,
    val previewSpec: PreviewSpec = PreviewSpec(),
)

/**
 * Everything the admin screens show. Updated only through [AdminReducer] (pure, JVM-tested); the view model performs the I/O and feeds the
 * results in as actions. [snapshot] and [overview] are null until the first load.
 */
data class AdminUiState(
    val unlocked: Boolean = false,
    val tab: AdminTab = AdminTab.OVERVIEW,
    val loading: Boolean = false,
    val snapshot: AdminSnapshot? = null,
    val overview: Overview? = null,
    val reportFilter: ReportFilter = ReportFilter(),
    val selectedReportId: String? = null,
    val logFilter: LogFilter = LogFilter(),
    val graph: GraphUiState = GraphUiState(),
    val editor: EditorUiState = EditorUiState(),
    val pendingImport: PendingImport? = null,
    val analysisSettings: AnalysisSettings = AnalysisSettings(),
    val alsoDeleteKgFiles: Boolean = true,
    /** One-line message for a snackbar-style banner; the UI clears it with [AdminAction.DismissNotice]. */
    val notice: String? = null,
    /** Name of a running long operation (for example "Generating demo history"), or null. */
    val busy: String? = null,
)

sealed interface AdminAction {
    data object Unlock : AdminAction
    data object Lock : AdminAction
    data class SelectTab(val tab: AdminTab) : AdminAction
    data object Loading : AdminAction
    data class Loaded(val snapshot: AdminSnapshot, val overview: Overview) : AdminAction
    data class SetReportFilter(val filter: ReportFilter) : AdminAction
    data class OpenReport(val reportId: String?) : AdminAction
    data class SetLogFilter(val filter: LogFilter) : AdminAction
    data class UpdateGraph(val transform: (GraphUiState) -> GraphUiState) : AdminAction
    data class ShowIncidentInGraph(val reportId: String) : AdminAction
    data class ShowPreviewInGraph(val admin: Boolean) : AdminAction
    data object ClearHighlight : AdminAction
    data class SelectNode(val nodeId: String?) : AdminAction
    data class SetEditorSection(val section: EditorSection) : AdminAction
    data class SetEditorQuery(val query: String) : AdminAction
    data class SetPreviewSpec(val spec: PreviewSpec) : AdminAction
    data class PreviewDone(val result: PreviewResult) : AdminAction
    data class Validated(val report: ValidationReport) : AdminAction
    data class ImportPrepared(val pending: PendingImport?) : AdminAction
    data class SettingsChanged(val settings: AnalysisSettings) : AdminAction
    data class AlsoDeleteKg(val value: Boolean) : AdminAction
    data class Notice(val message: String?) : AdminAction
    data class Busy(val what: String?) : AdminAction
}

/** The pure state transitions of the admin dashboard (ADR-058). */
object AdminReducer {
    fun reduce(s: AdminUiState, a: AdminAction): AdminUiState = when (a) {
        AdminAction.Unlock -> s.copy(unlocked = true)
        AdminAction.Lock -> AdminUiState(analysisSettings = s.analysisSettings, alsoDeleteKgFiles = s.alsoDeleteKgFiles) // leaving the admin area forgets everything shown
        is AdminAction.SelectTab -> s.copy(tab = a.tab, selectedReportId = if (a.tab == AdminTab.REPORTS) s.selectedReportId else null)
        AdminAction.Loading -> s.copy(loading = true)
        is AdminAction.Loaded -> s.copy(loading = false, snapshot = a.snapshot, overview = a.overview, selectedReportId = s.selectedReportId?.takeIf { id -> a.snapshot.reports.any { it.reportId == id } })
        is AdminAction.SetReportFilter -> s.copy(reportFilter = a.filter)
        is AdminAction.OpenReport -> s.copy(selectedReportId = a.reportId, tab = if (a.reportId != null) AdminTab.REPORTS else s.tab)
        is AdminAction.SetLogFilter -> s.copy(logFilter = if (a.filter.kind != s.logFilter.kind) a.filter.copy(type = null, level = null) else a.filter)
        is AdminAction.UpdateGraph -> s.copy(graph = a.transform(s.graph))
        is AdminAction.ShowIncidentInGraph -> showIncident(s, a.reportId)
        is AdminAction.ShowPreviewInGraph -> {
            val p = s.editor.preview
            if (p == null) s.copy(notice = "Run a preview first.") else s.copy(
                tab = AdminTab.GRAPH,
                graph = s.graph.copy(
                    mode = if (a.admin) HighlightMode.PREVIEW_ADMIN else HighlightMode.PREVIEW_REPORTER, highlightLabel = "Preview (${if (a.admin) "admin" else "reporter"})",
                    highlight = if (a.admin) p.adminPath else p.reporterPath, selectedNodeId = null,
                    options = s.graph.options.copy(focusNodeId = (if (a.admin) p.adminPath else p.reporterPath).rootNodeId, local = false),
                ),
            )
        }
        AdminAction.ClearHighlight -> s.copy(graph = s.graph.copy(mode = HighlightMode.NONE, highlightLabel = null, highlight = PathHighlight()))
        is AdminAction.SelectNode -> s.copy(
            graph = s.graph.copy(selectedNodeId = a.nodeId, mode = if (a.nodeId != null && s.graph.mode == HighlightMode.NONE) HighlightMode.NEIGHBOURHOOD else if (a.nodeId == null && s.graph.mode == HighlightMode.NEIGHBOURHOOD) HighlightMode.NONE else s.graph.mode),
        )
        is AdminAction.SetEditorSection -> s.copy(editor = s.editor.copy(section = a.section, query = ""))
        is AdminAction.SetEditorQuery -> s.copy(editor = s.editor.copy(query = a.query))
        is AdminAction.SetPreviewSpec -> s.copy(editor = s.editor.copy(previewSpec = a.spec))
        is AdminAction.PreviewDone -> s.copy(editor = s.editor.copy(preview = a.result))
        is AdminAction.Validated -> s.copy(editor = s.editor.copy(validation = a.report))
        is AdminAction.ImportPrepared -> s.copy(pendingImport = a.pending)
        is AdminAction.SettingsChanged -> s.copy(analysisSettings = a.settings)
        is AdminAction.AlsoDeleteKg -> s.copy(alsoDeleteKgFiles = a.value)
        is AdminAction.Notice -> s.copy(notice = a.message)
        is AdminAction.Busy -> s.copy(busy = a.what)
    }

    private fun showIncident(s: AdminUiState, reportId: String): AdminUiState {
        val snap = s.snapshot ?: return s.copy(notice = "Nothing loaded yet.")
        val audit = snap.auditFor(reportId) ?: return s.copy(notice = "This report has no analysis yet. Open it and run the analysis first.")
        val path = IncidentPath.fromAudit(snap.pack, audit)
        return s.copy(
            tab = AdminTab.GRAPH,
            graph = s.graph.copy(
                mode = HighlightMode.INCIDENT_PATH, highlightLabel = "Incident $reportId", highlight = path, selectedNodeId = null,
                options = s.graph.options.copy(focusNodeId = path.rootNodeId, local = false),
            ),
        )
    }
}
