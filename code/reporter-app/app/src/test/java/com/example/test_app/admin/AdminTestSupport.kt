package com.example.test_app.admin

import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.textValue
import com.example.test_app.knowledgegraph.KG_NOW
import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import com.example.test_app.knowledgegraph.engine.EngineConfig
import com.example.test_app.knowledgegraph.engine.KnowledgeGraphEngine
import com.example.test_app.knowledgegraph.kgAssetsDir
import com.example.test_app.knowledgegraph.kgResource
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.RecommendationLog
import com.example.test_app.knowledgegraph.model.ValidatedIncident
import com.example.test_app.knowledgegraph.repository.GraphIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant

/** Shared helpers of the admin dashboard tests: the shipped seed pack, its scenario incidents and small report builders. */
object AdminTestSupport {
    val seedPack: GraphPack by lazy { GraphPackCodec.decode(File(kgAssetsDir(), "seed_pack.json").readText(Charsets.UTF_8)) }
    val index: GraphIndex by lazy { GraphIndex(seedPack) }
    val now: Instant = Instant.parse("2026-10-11T06:00:00Z")

    private val scenarios by lazy { Json.parseToJsonElement(kgResource("scenarios.json").readText(Charsets.UTF_8)).jsonArray }

    /** The incident of required scenario [n] (1..12) of the seed fixtures. */
    fun scenarioIncident(n: Int): ValidatedIncident =
        Json.decodeFromJsonElement(ValidatedIncident.serializer(), scenarios[n - 1].jsonObject.getValue("incident"))

    fun engine(config: EngineConfig = EngineConfig()) = KnowledgeGraphEngine({ index }, null, config) { KG_NOW }

    fun auditOf(i: ValidatedIncident, audience: Audience = Audience.REPORTER): RecommendationLog = engine().analyze(i, audience).log

    fun report(
        id: String, type: String? = "Fire", severity: String? = "High", place: String = "KRB lab", createdAt: String = "2026-10-10T10:00:00Z",
        description: String = "A fire", transcript: String = "a fire in the lab", status: String = IncidentReport.STATUS_SUBMITTED,
    ): IncidentReport {
        val f = mutableMapOf<String, FieldEntry>()
        type?.let { f[IncidentField.INCIDENT_TYPE.key] = FieldEntry(textValue(it), 0.9) }
        severity?.let { f[IncidentField.SEVERITY_LEVEL.key] = FieldEntry(textValue(it), 0.9) }
        f[IncidentField.INCIDENT_LOCATION.key] = FieldEntry(textValue(place), 0.9)
        f[IncidentField.INCIDENT_DESCRIPTION.key] = FieldEntry(textValue(description), 0.9)
        return IncidentReport(reportId = id, status = status, createdAt = createdAt, input = ReportInput("voice", transcript), fields = f)
    }

    fun snapshot(
        reports: List<IncidentReport> = emptyList(), audits: List<RecommendationLog> = emptyList(), pack: GraphPack = seedPack,
        outbox: List<com.example.test_app.knowledgegraph.IncidentAnalysisExport> = emptyList(), dialogue: List<DialogueLogFile> = emptyList(), eval: List<EvalRunSummary> = emptyList(),
    ) = AdminSnapshot(reports, audits, outbox, dialogue, eval, pack, listOf(pack.packVersion), now)
}
