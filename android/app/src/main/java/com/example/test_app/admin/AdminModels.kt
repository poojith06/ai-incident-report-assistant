package com.example.test_app.admin

import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.knowledgegraph.IncidentAnalysisExport
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.RecommendationLog
import java.time.Instant

/** One dialogue JSONL log: the lines of `files/dialogue_logs/<reportId>.jsonl` (raw text, one JSON object each). */
data class DialogueLogFile(val reportId: String, val lines: List<String>, val modifiedAtMs: Long)

/** One saved evaluation run (`files/eval_runs/<runId>/results.json`): the headline and the raw JSON for the viewer. */
data class EvalRunSummary(val runId: String, val startedAt: String, val input: String, val cases: Int, val headline: String, val json: String)

/**
 * Everything the admin dashboard shows, read once by [AdminBackend.loadSnapshot] (so every tab is consistent and the UI never touches
 * files). All lists are newest first. Plain data, JVM-testable; the aggregations in [OverviewStats], [ReportFilter] and [LogParser] work on it.
 */
data class AdminSnapshot(
    val reports: List<IncidentReport> = emptyList(),
    val audits: List<RecommendationLog> = emptyList(),
    val outbox: List<IncidentAnalysisExport> = emptyList(),
    val dialogueLogs: List<DialogueLogFile> = emptyList(),
    val evalRuns: List<EvalRunSummary> = emptyList(),
    val pack: GraphPack,
    val versions: List<Int> = emptyList(),
    val loadedAt: Instant = Instant.EPOCH,
) {
    fun auditFor(reportId: String): RecommendationLog? = audits.firstOrNull { it.incidentId == reportId }
    fun exportFor(reportId: String): IncidentAnalysisExport? = outbox.firstOrNull { it.incident.incidentId == reportId }
    fun dialogueFor(reportId: String): DialogueLogFile? = dialogueLogs.firstOrNull { it.reportId == reportId }
}
