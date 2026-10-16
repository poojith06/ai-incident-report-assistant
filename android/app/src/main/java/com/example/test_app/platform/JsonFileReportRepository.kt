package com.example.test_app.platform

import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.service.IncidentJsonCodec
import com.example.test_app.domain.service.ReportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** [ReportRepository] storing one pretty-printed JSON file per report in [dir]. */
class JsonFileReportRepository(private val dir: File) : ReportRepository {
    override suspend fun save(report: IncidentReport) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        File(dir, "${report.reportId}.json").writeText(IncidentJsonCodec.encode(report))
    }

    override suspend fun list(): List<IncidentReport> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { IncidentJsonCodec.decode(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    override suspend fun delete(reportId: String): Boolean = withContext(Dispatchers.IO) {
        reportId.isNotBlank() && File(dir, "$reportId.json").let { it.isFile && it.delete() }
    }

    override suspend fun deleteAll(): Int = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).count { it.delete() }
    }
}
