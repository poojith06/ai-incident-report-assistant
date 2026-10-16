package com.example.test_app.admin

import com.example.test_app.knowledgegraph.IncidentAnalysisExport
import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import com.example.test_app.knowledgegraph.model.RecommendationLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class LogKind(val label: String) { AUDIT("Analysis audit"), DIALOGUE("Dialogue"), EVAL("Eval runs"), OUTBOX("Outbox") }

enum class LogLevel { INFO, WARN, ERROR }

/**
 * One row of the Logs tab. [json] is the pretty JSON of the underlying record (the expandable viewer shows it; exports use it). [type] is a
 * short category for the filter (for example the dialogue state or the incident type); [reportId] links to the report when there is one.
 */
data class LogEntry(
    val id: String,
    val kind: LogKind,
    val title: String,
    val subtitle: String,
    val time: String,
    val level: LogLevel,
    val type: String,
    val reportId: String?,
    val json: String,
)

/** Filter of the Logs tab: [kind] picks the sub-tab; blank query/level/type puts no constraint. */
data class LogFilter(val kind: LogKind = LogKind.AUDIT, val query: String = "", val level: LogLevel? = null, val type: String? = null)

/** Parsing of the app's own logs into [LogEntry]s and filtering of them (pure). Unreadable lines become ERROR entries instead of being dropped. */
object LogParser {
    private val pretty = Json { prettyPrint = true }

    fun fromAudit(log: RecommendationLog): LogEntry = LogEntry(
        id = "audit:${log.id}", kind = LogKind.AUDIT,
        title = "${log.incidentId}: ${if (log.abstained) "abstained" else "${log.recommendationIds.size} recommendation(s)"}",
        subtitle = "graph v${log.graphVersion}, ${log.suppressed.size} suppressed, ${log.audience.name.lowercase()}",
        time = log.createdAt, level = if (log.abstained || log.warnings.isNotEmpty()) LogLevel.WARN else LogLevel.INFO,
        type = log.inputFacts.type ?: "unknown", reportId = log.incidentId, json = GraphPackCodec.pretty.encodeToString(RecommendationLog.serializer(), log),
    )

    fun fromOutbox(e: IncidentAnalysisExport): LogEntry = LogEntry(
        id = "outbox:${e.exportId}", kind = LogKind.OUTBOX, title = "${e.incident.incidentId}: queued export",
        subtitle = "graph v${e.graphVersion}, ${e.recommendations.size} recommendation(s)", time = e.createdAt,
        level = if (e.abstained) LogLevel.WARN else LogLevel.INFO, type = e.incident.type ?: "unknown", reportId = e.incident.incidentId,
        json = GraphPackCodec.pretty.encodeToString(IncidentAnalysisExport.serializer(), e),
    )

    fun fromEval(run: EvalRunSummary): LogEntry = LogEntry(
        id = "eval:${run.runId}", kind = LogKind.EVAL, title = "Eval ${run.runId}", subtitle = run.headline, time = run.startedAt, level = LogLevel.INFO,
        type = run.input, reportId = null, json = run.json,
    )

    /** One entry per non-blank line of a dialogue log (`state`, `field`, `validator`, `outcome` ...). */
    fun fromDialogue(file: DialogueLogFile): List<LogEntry> = file.lines.filter { it.isNotBlank() }.mapIndexed { i, line ->
        val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        if (obj == null) {
            LogEntry("dialogue:${file.reportId}:$i", LogKind.DIALOGUE, "${file.reportId}: unreadable line", line.take(120), "", LogLevel.ERROR, "unreadable", file.reportId, line)
        } else {
            fun s(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            val state = s("state") ?: "?"
            val validator = s("validator")
            val outcome = s("outcome")
            val level = when {
                validator?.startsWith("fail") == true || validator == "timeout" -> LogLevel.WARN
                outcome == "no_response" || outcome == "unverified" -> LogLevel.WARN
                else -> LogLevel.INFO
            }
            val what = listOfNotNull(s("field"), outcome?.let { "-> $it" }, s("spoken")?.take(60)).joinToString(" ")
            LogEntry(
                id = "dialogue:${file.reportId}:$i", kind = LogKind.DIALOGUE, title = "${file.reportId}: $state", subtitle = what.ifBlank { s("mode").orEmpty() },
                time = s("t").orEmpty(), level = level, type = state, reportId = file.reportId, json = pretty.encodeToString(JsonElement.serializer(), obj),
            )
        }
    }

    /** All entries of a snapshot for one [kind], newest first (dialogue keeps the turn order within a report). */
    fun entries(s: AdminSnapshot, kind: LogKind): List<LogEntry> = when (kind) {
        LogKind.AUDIT -> s.audits.map { fromAudit(it) }.sortedByDescending { it.time }
        LogKind.OUTBOX -> s.outbox.map { fromOutbox(it) }.sortedByDescending { it.time }
        LogKind.EVAL -> s.evalRuns.map { fromEval(it) }.sortedByDescending { it.time }
        LogKind.DIALOGUE -> s.dialogueLogs.sortedByDescending { it.modifiedAtMs }.flatMap { fromDialogue(it) }
    }

    fun filter(entries: List<LogEntry>, f: LogFilter): List<LogEntry> {
        val q = f.query.trim().lowercase()
        return entries.filter { e ->
            (f.level == null || e.level == f.level) && (f.type == null || e.type.equals(f.type, true)) &&
                (q.isEmpty() || e.title.lowercase().contains(q) || e.subtitle.lowercase().contains(q) || e.json.lowercase().contains(q))
        }
    }

    fun types(entries: List<LogEntry>): List<String> = entries.map { it.type }.distinct().sorted()

    /** One JSON document with the entries' records, for sharing (`{"kind", "count", "entries": [...]}`). */
    fun bundleJson(kinds: Map<LogKind, List<LogEntry>>, createdAt: String): String {
        val obj = JsonObject(
            mapOf(
                "createdAt" to JsonPrimitive(createdAt),
                "logs" to JsonObject(
                    kinds.entries.associate { (k, list) ->
                        k.name.lowercase() to JsonArray(list.map { e -> runCatching { Json.parseToJsonElement(e.json) }.getOrElse { JsonPrimitive(e.json) } })
                    },
                ),
            ),
        )
        return pretty.encodeToString(JsonElement.serializer(), obj)
    }
}
