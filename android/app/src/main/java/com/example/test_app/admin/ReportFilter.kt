package com.example.test_app.admin

import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.asTextOrNull
import java.time.LocalDate

/** Filters of the Reports tab. A null or blank part puts no constraint; dates are inclusive and compared on the UTC calendar day of `created_at`. */
data class ReportFilter(
    val type: String? = null,
    val severity: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val query: String = "",
    val demoOnly: Boolean? = null,
) {
    val isActive: Boolean get() = this != ReportFilter()
}

object ReportFilters {
    private fun text(r: IncidentReport, f: IncidentField) = r.fields[f.key]?.value?.asTextOrNull().orEmpty()

    /** Matching reports, newest first (unreadable dates last). */
    fun apply(reports: List<IncidentReport>, f: ReportFilter): List<IncidentReport> {
        val q = f.query.trim().lowercase()
        return reports.filter { r ->
            (f.type == null || OverviewStats.reportType(r).equals(f.type, true)) &&
                (f.severity == null || OverviewStats.reportSeverity(r).equals(f.severity, true)) &&
                dateOk(r, f) && (f.demoOnly == null || DemoData.isDemo(r) == f.demoOnly) && (q.isEmpty() || matches(r, q))
        }.sortedWith(compareByDescending<IncidentReport> { OverviewStats.reportInstant(it) ?: java.time.Instant.EPOCH }.thenBy { it.reportId })
    }

    private fun dateOk(r: IncidentReport, f: ReportFilter): Boolean {
        if (f.from == null && f.to == null) return true
        val day = OverviewStats.reportInstant(r)?.atOffset(OverviewStats.zone)?.toLocalDate() ?: return false
        return (f.from == null || !day.isBefore(f.from)) && (f.to == null || !day.isAfter(f.to))
    }

    private fun matches(r: IncidentReport, q: String): Boolean =
        r.reportId.lowercase().contains(q) || OverviewStats.reportType(r).lowercase().contains(q) || text(r, IncidentField.INCIDENT_LOCATION).lowercase().contains(q) ||
            text(r, IncidentField.INCIDENT_DESCRIPTION).lowercase().contains(q) || r.input.transcript.lowercase().contains(q)

    /** The distinct types and severities present, for the filter chips. */
    fun types(reports: List<IncidentReport>): List<String> = reports.map { OverviewStats.reportType(it) }.distinct().sorted()
    fun severities(reports: List<IncidentReport>): List<String> = reports.map { OverviewStats.reportSeverity(it) }.distinct().sortedWith(compareBy({ OverviewStats.severityOrder(it) }, { it }))
}
