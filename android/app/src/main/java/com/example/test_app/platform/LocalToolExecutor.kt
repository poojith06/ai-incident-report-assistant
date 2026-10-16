package com.example.test_app.platform

import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ToolCall
import com.example.test_app.domain.service.ReportRepository
import com.example.test_app.domain.service.ToolExecutor
import com.example.test_app.domain.service.PlannerHint
import com.example.test_app.domain.service.ToolResult
import kotlinx.coroutines.delay

/**
 * Real local [ToolExecutor]: tool "calls" are plain local operations; [delayMs] only makes the step visible in the UI.
 * See `doc/SERVICES_AND_MOCKS.md`.
 */
class LocalToolExecutor(
    private val repository: ReportRepository,
    private val delayMs: () -> Long,
) : ToolExecutor {
    override suspend fun fillIncidentForm(report: IncidentReport): IncidentReport {
        delay(delayMs())
        return report.copy(toolCall = ToolCall("fill_incident_form", "fields"))
    }

    /** Called by the dialogue after every extraction: no artificial delay (the delay only paces the visible form step). */
    override suspend fun fillIncidentFormWithHint(report: IncidentReport, hint: PlannerHint): ToolResult =
        ToolResult(ok = true, report = report.copy(toolCall = ToolCall("fill_incident_form", "fields")), hint = hint)

    override suspend fun submitIncidentReport(report: IncidentReport): IncidentReport {
        delay(delayMs())
        val submitted = report.copy(
            status = IncidentReport.STATUS_SUBMITTED,
            device = report.device?.let { if (it.anonymous) it.copy(reporter = null) else it },
            toolCall = ToolCall("submit_incident_report", "fields"),
        )
        repository.save(submitted)
        return submitted
    }
}
