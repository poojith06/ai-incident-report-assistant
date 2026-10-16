package com.example.test_app.knowledgegraph

import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.knowledgegraph.repository.FileAnalysisOutbox
import com.example.test_app.knowledgegraph.repository.FileAuditStore
import com.example.test_app.knowledgegraph.repository.FileGraphRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End to end over the 12 seed scenarios with the real file stores: report -> service -> audit log + outbox export. Also writes the
 * generated documents to `build/kg-samples/` (copy them to `doc/samples/kg/` when the seed pack or the engine changes).
 */
class SampleOutputTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scenarios = Json.parseToJsonElement(kgResource("scenarios.json").readText(Charsets.UTF_8)).jsonArray

    private fun reportOf(s: kotlinx.serialization.json.JsonObject): IncidentReport {
        val r = s.getValue("report").jsonObject
        fun str(k: String) = (r[k] as? JsonPrimitive)?.contentOrNull
        val f = mutableMapOf<String, FieldEntry>()
        str("appType")?.let { f[IncidentField.INCIDENT_TYPE.key] = FieldEntry(textValue(it), 0.9) }
        str("description")?.let { f[IncidentField.INCIDENT_DESCRIPTION.key] = FieldEntry(textValue(it), 0.9) }
        str("location")?.let { f[IncidentField.INCIDENT_LOCATION.key] = FieldEntry(textValue(it), 0.9) }
        str("severity")?.let { f[IncidentField.SEVERITY_LEVEL.key] = FieldEntry(textValue(it), 0.9) }
        str("immediateActions")?.let { f[IncidentField.IMMEDIATE_ACTIONS_TAKEN.key] = FieldEntry(textValue(it), 0.9) }
        (r["hazardStillActive"] as? JsonPrimitive)?.booleanOrNull?.let { f[IncidentField.HAZARD_STILL_ACTIVE.key] = FieldEntry(boolValue(it), 0.9) }
        (r["injury"] as? JsonPrimitive)?.booleanOrNull?.let { f[IncidentField.INJURY.key] = FieldEntry(boolValue(it), 0.9) }
        return IncidentReport(
            reportId = (s.getValue("incident").jsonObject["incidentId"] as JsonPrimitive).content, status = IncidentReport.STATUS_SUBMITTED, createdAt = "2026-10-11T10:00:00+05:30",
            input = ReportInput("voice", "t"), fields = f,
        )
    }

    @Test fun theSeedPackIsInstalledFromItsJsonOnFirstUseAndAllScenariosRunEndToEnd() = runTest {
        val seedJson = File(kgAssetsDir(), "seed_pack.json").readText(Charsets.UTF_8)
        val kg = tmp.newFolder("kg")
        val repo = FileGraphRepository(kg) { seedJson }
        val outbox = FileAnalysisOutbox(File(kg, "outbox"))
        val audits = FileAuditStore(File(kg, "audit"))
        val svc = KnowledgeGraphService(repo, audits, outbox, clock = { KG_NOW })
        assertEquals(1, repo.activePack().packVersion)
        val out = File("build/kg-samples").apply { deleteRecursively(); mkdirs() }
        for (s in scenarios.map { it.jsonObject }) {
            val report = reportOf(s)
            val r = svc.analyze(report, listOf(report)) as AnalysisResult.Done
            assertTrue(report.reportId, r.set.allRecommendations.isNotEmpty())
            val log = audits.load(report.reportId)!!
            assertEquals(r.set.recommendationIds, log.recommendationIds)
            assertEquals(1, log.graphVersion)
            val export = outbox.read(report.reportId)!!
            assertEquals(r.export, export)
            assertTrue(export.recommendations.all { it.sourceId == "sop:demo-seed:v1" })
            File(out, "${report.reportId}.analysis-export.json").writeText(File(kg, "outbox/${report.reportId}.json").readText(Charsets.UTF_8))
            File(out, "${report.reportId}.audit-log.json").writeText(File(kg, "audit/${report.reportId}.json").readText(Charsets.UTF_8))
        }
        assertEquals(12, outbox.pending().size)
        assertEquals(12, File(out, "").listFiles { f -> f.name.endsWith(".analysis-export.json") }!!.size)
    }
}
