package com.example.test_app.admin

import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.service.ReportRepository
import com.example.test_app.eval.EvalReport
import com.example.test_app.knowledgegraph.KnowledgeGraphService
import com.example.test_app.knowledgegraph.admin.AdminGraphService
import com.example.test_app.knowledgegraph.admin.graphview.IncidentPath
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.repository.AnalysisOutbox
import com.example.test_app.knowledgegraph.repository.FileAuditStore
import com.example.test_app.knowledgegraph.repository.GraphRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * All file and store access of the admin dashboard in one place, so the view model and the screens never touch files (ADR-058). It reads the
 * app's own data: saved reports, `files/kg/audit`, `files/kg/outbox`, the active graph pack, `files/dialogue_logs/<id>.jsonl` and
 * `files/eval_runs/<run>/results.json`. Everything is plain Kotlin and offline; every method runs on `Dispatchers.IO`.
 */
class AdminBackend(
    private val reports: ReportRepository,
    private val auditStore: FileAuditStore,
    private val dialogueLogDir: File,
    private val evalRunsDir: File,
    private val graphRepository: GraphRepository,
    val graphAdmin: AdminGraphService,
    private val outbox: AnalysisOutbox,
    private val knowledgeGraph: KnowledgeGraphService,
    private val clock: () -> Instant = { Instant.now() },
) {
    suspend fun loadSnapshot(): AdminSnapshot = withContext(Dispatchers.IO) {
        AdminSnapshot(
            reports = reports.list(), audits = auditStore.all(),
            outbox = outbox.pending().mapNotNull { outbox.read(it) }.sortedByDescending { it.createdAt },
            dialogueLogs = readDialogueLogs(), evalRuns = readEvalRuns(), pack = graphRepository.activePack(), versions = graphRepository.versions(), loadedAt = clock(),
        )
    }

    private fun readDialogueLogs(): List<DialogueLogFile> =
        (dialogueLogDir.listFiles { f -> f.isFile && f.extension == "jsonl" } ?: emptyArray()).map {
            DialogueLogFile(it.nameWithoutExtension, runCatching { it.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()), it.lastModified())
        }.sortedByDescending { it.modifiedAtMs }

    private fun readEvalRuns(): List<EvalRunSummary> =
        (evalRunsDir.listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull { dir ->
            val file = File(dir, "results.json").takeIf { it.isFile } ?: return@mapNotNull null
            runCatching {
                val text = file.readText(Charsets.UTF_8)
                val run = EvalReport.fromJson(text)
                EvalRunSummary(run.runId, run.startedAt, run.input, run.cases.size, EvalReport.headline(run).joinToString(" | "), text)
            }.getOrNull()
        }.sortedByDescending { it.startedAt }

    /** Deletes the files of one log kind; returns how many were removed. */
    suspend fun clear(kind: LogKind): Int = withContext(Dispatchers.IO) {
        when (kind) {
            LogKind.AUDIT -> auditStore.clear()
            LogKind.OUTBOX -> outbox.pending().let { ids -> ids.forEach { outbox.acknowledge(it) }; ids.size }
            LogKind.DIALOGUE -> (dialogueLogDir.listFiles { f -> f.isFile && f.extension == "jsonl" } ?: emptyArray()).count { it.delete() }
            LogKind.EVAL -> (evalRunsDir.listFiles { f -> f.isDirectory } ?: emptyArray()).count { it.deleteRecursively() }
        }
    }

    /** Deletes the audit log and the queued export of each report id ("also delete KG files when deleting reports"); returns the number of files removed. */
    suspend fun deleteKgFilesFor(ids: Collection<String>): Int = withContext(Dispatchers.IO) {
        val audits = auditStore.delete(ids)
        val queued = ids.count { id -> (outbox.read(id) != null).also { if (it) outbox.acknowledge(id) } }
        audits + queued
    }

    /** Writes [DemoData.generate] reports and analyses them in date order (so the recurrence rules see the earlier ones). Returns how many were created. */
    suspend fun generateDemo(count: Int = DemoData.DEFAULT_COUNT): Int {
        val demo = DemoData.generate(clock(), count).sortedBy { it.createdAt }
        demo.forEach { reports.save(it) }
        for (r in demo) knowledgeGraph.analyze(r, reports.list())
        return demo.size
    }

    /** Removes every demo report and its audit log, queued export and dialogue log. Real reports are never touched. Returns the number of reports removed. */
    suspend fun removeDemo(): Int {
        val ids = reports.list().filter { DemoData.isDemo(it) }.map { it.reportId }
        ids.forEach { reports.delete(it) }
        withContext(Dispatchers.IO) { ids.forEach { File(dialogueLogDir, "$it.jsonl").delete() } }
        deleteKgFilesFor(ids)
        return ids.size
    }

    /** Runs (or re-runs) the analysis of a saved report. */
    suspend fun analyzeReport(report: IncidentReport) = knowledgeGraph.analyze(report, reports.list())

    /** Runs the engine for a sample incident for both audiences (no audit log is written) and derives the graph highlight of each. */
    suspend fun preview(spec: PreviewSpec): PreviewResult = withContext(Dispatchers.Default) {
        val incident = spec.toIncident(clock().toString())
        val pack = graphRepository.activePack()
        val reporter = graphAdmin.preview(incident, Audience.REPORTER)
        val admin = graphAdmin.preview(incident, Audience.ADMIN_PREVIEW)
        PreviewResult(incident, reporter, admin, IncidentPath.fromPreview(pack, incident, reporter), IncidentPath.fromPreview(pack, incident, admin))
    }
}
