package com.example.test_app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.test_app.knowledgegraph.AnalysisResult
import com.example.test_app.knowledgegraph.admin.AdminException
import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import com.example.test_app.knowledgegraph.admin.GraphValidator
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.KgEdge
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.KgSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The one view model of the admin dashboard (ADR-058). It holds [AdminUiState], changes it only through [AdminReducer], and does the work
 * through [AdminBackend] and [AdminPrefs]. Screens call these methods; nothing else touches files. Operations report back with
 * `AdminAction.Notice` (a one-line message) and reload the snapshot.
 */
class AdminViewModel(
    private val backend: AdminBackend,
    private val prefs: AdminPrefs,
    private val clock: () -> Instant = { Instant.now() },
    /** Called after settings change so the engine picks them up (the app passes nothing: the service reads [AdminPrefs] per analysis). */
    private val onSettingsSaved: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(AdminUiState(analysisSettings = AnalysisSettings.decode(prefs.analysisSettingsJson), alsoDeleteKgFiles = prefs.alsoDeleteKgFiles))
    val state: StateFlow<AdminUiState> = _state

    private fun act(a: AdminAction) = _state.update { AdminReducer.reduce(it, a) }

    private fun <T> launchOp(busy: String? = null, block: suspend () -> T) {
        viewModelScope.launch {
            if (busy != null) act(AdminAction.Busy(busy))
            try { block() } catch (e: CancellationException) { throw e } catch (e: AdminException) { act(AdminAction.Notice(e.message ?: "Refused.")); e.report?.let { act(AdminAction.Validated(it)) } }
            catch (e: Exception) { act(AdminAction.Notice("Failed: ${e.message ?: e::class.java.simpleName}")) }
            finally { if (busy != null) act(AdminAction.Busy(null)) }
        }
    }

    // ---- gate ------------------------------------------------------------------------------------------

    /** Checks [pin]; on success opens the dashboard and loads the data. Returns whether it was right. */
    fun unlock(pin: String): Boolean {
        val ok = AdminPin.check(pin, prefs.pin)
        if (ok) { act(AdminAction.Unlock); refresh() }
        return ok
    }

    /** Leaves the admin area: the state (and everything loaded) is dropped. */
    fun lock() = act(AdminAction.Lock)

    // ---- navigation and data -----------------------------------------------------------------------------

    fun selectTab(tab: AdminTab) = act(AdminAction.SelectTab(tab))

    fun refresh() {
        act(AdminAction.Loading)
        viewModelScope.launch {
            try {
                val snap = backend.loadSnapshot()
                act(AdminAction.Loaded(snap, OverviewStats.compute(snap, clock())))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                act(AdminAction.Notice("Could not load the admin data: ${e.message}")); _state.update { it.copy(loading = false) }
            }
        }
    }

    fun dismissNotice() = act(AdminAction.Notice(null))

    fun setReportFilter(f: ReportFilter) = act(AdminAction.SetReportFilter(f))
    fun openReport(id: String?) = act(AdminAction.OpenReport(id))
    fun setLogFilter(f: LogFilter) = act(AdminAction.SetLogFilter(f))
    fun updateGraph(transform: (GraphUiState) -> GraphUiState) = act(AdminAction.UpdateGraph(transform))
    fun showIncidentInGraph(reportId: String) = act(AdminAction.ShowIncidentInGraph(reportId))
    fun clearHighlight() = act(AdminAction.ClearHighlight)
    fun selectNode(id: String?) = act(AdminAction.SelectNode(id))
    fun setEditorSection(s: EditorSection) = act(AdminAction.SetEditorSection(s))
    fun setEditorQuery(q: String) = act(AdminAction.SetEditorQuery(q))
    fun setPreviewSpec(spec: PreviewSpec) = act(AdminAction.SetPreviewSpec(spec))

    // ---- reports -------------------------------------------------------------------------------------------

    /** Runs (or re-runs) the analysis of a saved report so it has an audit log and an outbox export. */
    fun analyzeReport(reportId: String) {
        val report = _state.value.snapshot?.reports?.firstOrNull { it.reportId == reportId } ?: return
        launchOp("Analysing $reportId") {
            when (val r = backend.analyzeReport(report)) {
                is AnalysisResult.Done -> act(AdminAction.Notice("Analysis done: ${r.set.recommendationIds.size} recommendation(s)."))
                is AnalysisResult.Failed -> act(AdminAction.Notice("Analysis failed: ${r.message}"))
            }
            refresh()
        }
    }

    // ---- logs ---------------------------------------------------------------------------------------------------

    /** The JSON text to share for the entries of [kind] (all, ignoring the search), or for every kind when [kind] is null. */
    fun bundleJson(kind: LogKind? = null): String {
        val snap = _state.value.snapshot ?: return "{}"
        val kinds = (kind?.let { listOf(it) } ?: LogKind.entries).associateWith { LogParser.entries(snap, it) }
        return LogParser.bundleJson(kinds, clock().toString())
    }

    fun clearLogs(kind: LogKind) = launchOp("Clearing ${kind.label}") {
        val n = backend.clear(kind)
        act(AdminAction.Notice("Removed $n ${kind.label.lowercase()} file(s)."))
        refresh()
    }

    // ---- knowledge editor --------------------------------------------------------------------------------------

    private fun pack(): GraphPack? = _state.value.snapshot?.pack

    /** Runs the graph validator on the active pack and shows the result. */
    fun validate() = launchOp { act(AdminAction.Validated(backend.graphAdmin.validate())) }

    fun upsertNode(node: KgNode) = launchOp { val v = backend.graphAdmin.upsertNode(node); act(AdminAction.Notice("Saved ${node.id} as graph version $v.")); refresh() }
    fun upsertEdge(edge: KgEdge) = launchOp { val v = backend.graphAdmin.upsertEdge(edge); act(AdminAction.Notice("Saved ${edge.id} as graph version $v.")); refresh() }
    fun disable(id: String) = launchOp { val v = backend.graphAdmin.disable(id); act(AdminAction.Notice("Disabled $id (graph version $v).")); refresh() }
    fun attachSource(source: KgSource, recommendationId: String?) = launchOp {
        val v = backend.graphAdmin.attachSource(source, recommendationId?.ifBlank { null }); act(AdminAction.Notice("Source ${source.id} attached (graph version $v).")); refresh()
    }
    fun approve(recommendationId: String, reviewer: String, expiresAt: String?) = launchOp {
        val v = backend.graphAdmin.approveRecommendation(recommendationId, reviewer, expiresAt); act(AdminAction.Notice("$recommendationId approved (graph version $v).")); refresh()
    }
    fun verifyLocation(id: String, by: String) = launchOp { val v = backend.graphAdmin.verifyLocation(id, by); act(AdminAction.Notice("$id verified (graph version $v).")); refresh() }
    fun verifyTeam(id: String, by: String) = launchOp { val v = backend.graphAdmin.verifyTeam(id, by); act(AdminAction.Notice("$id verified (graph version $v).")); refresh() }
    fun rollback(version: Int) = launchOp { backend.graphAdmin.rollback(version); act(AdminAction.Notice("Graph version $version is active again.")); refresh() }

    /** Runs the engine for the sample incident of the preview form for both audiences. */
    fun runPreview() = launchOp("Running preview") { act(AdminAction.PreviewDone(backend.preview(_state.value.editor.previewSpec))) }
    fun showPreviewInGraph(admin: Boolean) = act(AdminAction.ShowPreviewInGraph(admin))

    // ---- import / export -----------------------------------------------------------------------------------------

    /** Validates a pack JSON the admin picked and shows what it would change; nothing is stored until [confirmImport]. */
    fun prepareImport(json: String) {
        val current = pack() ?: return
        val pending = try {
            val p = GraphPackCodec.decode(json)
            val report = GraphValidator.validate(p)
            PendingImport(json, ImportDiff.compute(current, p), report.errors, report.warnings)
        } catch (e: IllegalArgumentException) {
            PendingImport(json, null, listOf(e.message ?: "not a valid pack"), emptyList())
        }
        act(AdminAction.ImportPrepared(pending))
    }

    fun cancelImport() = act(AdminAction.ImportPrepared(null))

    fun confirmImport() {
        val pending = _state.value.pendingImport ?: return
        if (!pending.canConfirm) return
        launchOp("Importing") {
            val r = backend.graphAdmin.importPack(pending.json)
            act(AdminAction.ImportPrepared(null)); act(AdminAction.Notice("Imported as graph version ${r.version}.")); refresh()
        }
    }

    /** The JSON of the active pack (or of [version]) for sharing. */
    fun exportPackJson(version: Int? = null): String = backend.graphAdmin.exportPack(version)

    // ---- admin settings --------------------------------------------------------------------------------------------

    fun changePin(current: String, newPin: String, confirm: String): AdminPin.Change {
        val r = AdminPin.change(current, newPin, confirm, prefs.pin)
        if (r is AdminPin.Change.Ok) { prefs.pin = r.newPin; act(AdminAction.Notice("The admin PIN was changed.")) }
        return r
    }

    /** Saves [settings] if valid (returns the problems otherwise) and makes the engine use them from the next analysis. */
    fun saveAnalysisSettings(settings: AnalysisSettings): List<String> {
        val problems = settings.problems()
        if (problems.isEmpty()) {
            prefs.analysisSettingsJson = AnalysisSettings.encode(settings); act(AdminAction.SettingsChanged(settings)); onSettingsSaved(); act(AdminAction.Notice("Analysis settings saved."))
        }
        return problems
    }

    fun resetAnalysisSettings() {
        prefs.analysisSettingsJson = null; act(AdminAction.SettingsChanged(AnalysisSettings())); onSettingsSaved(); act(AdminAction.Notice("Analysis settings reset to the defaults."))
    }

    fun setAlsoDeleteKg(value: Boolean) { prefs.alsoDeleteKgFiles = value; act(AdminAction.AlsoDeleteKg(value)) }

    fun generateDemo() = launchOp("Generating demo history") { val n = backend.generateDemo(); act(AdminAction.Notice("Created $n demo reports (ids start with DEMO-).")); refresh() }
    fun removeDemo() = launchOp("Removing demo data") { val n = backend.removeDemo(); act(AdminAction.Notice("Removed $n demo reports.")); refresh() }
}
