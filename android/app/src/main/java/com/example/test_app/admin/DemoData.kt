package com.example.test_app.admin

import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.Validation
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.textValue
import java.time.Instant
import java.time.ZoneOffset

/**
 * Synthetic past reports for demonstrating the dashboard, the charts, the recurrence rules and the graph highlights ("Generate demo
 * history", ADR-058). The report model has no spare field, so a demo report is recognised by its id prefix [PREFIX] (`DEMO-0001`, ...);
 * [isDemo] is the only test the app uses, and removal deletes exactly those ids: a real report (`INC-...`) is never touched. Output is
 * deterministic for a given [now]. The wording is chosen so the seed pack's normalization rules map it (water leaks repeat in one
 * washroom within 60 days so the recurrence pattern fires).
 */
object DemoData {
    const val PREFIX = "DEMO-"
    const val DEFAULT_COUNT = 30

    fun isDemo(r: IncidentReport): Boolean = isDemoId(r.reportId)
    fun isDemoId(id: String): Boolean = id.startsWith(PREFIX)

    private class Template(val type: String, val description: String, val place: String, val severity: String, val injury: Boolean?, val active: Boolean?, val actions: String? = null)

    private val templates = listOf(
        Template("Water Leak", "A pipe is leaking in the washroom and water is spreading on the floor.", "KRB washroom, third floor", "Medium", false, true),
        Template("Fire", "A desktop computer caught fire in the computer lab. The fire is now isolated, a little smoke remains.", "KRB computer lab", "High", false, false, "Isolated the power"),
        Template("Gas Leak", "Strong LPG smell in the mess kitchen near the cooking area.", "Main mess kitchen", "High", false, true),
        Template("Injury", "A player fell during football and hurt his leg on the ground.", "Football ground", "Medium", true, null),
        Template("Equipment Damage", "Several classroom chairs and a table are broken.", "Himalaya classroom", "Low", false, null),
        Template("Equipment Failure", "The network and storage service is down, nobody can reach the shared drive.", "Nilgiri server room", "Medium", false, true),
        Template("Spill", "A chemical bottle broke and the liquid spilled on the lab table.", "Chemistry lab", "High", false, true),
        Template("Security", "The fire exit door is blocked by stacked boxes.", "Nilgiri corridor", "Medium", false, null),
        Template("Fire", "Smoke is coming out of a hostel room and the alarm is ringing.", "Hostel block A room", "High", false, true),
        Template("Electrical Hazard", "Water is dripping near a power strip in the lecture hall.", "Himalaya lecture hall", "High", false, true),
    )

    /** [count] reports spread over the last 55 days; the first four are leaks in the same washroom (recurrence demo). Ids `DEMO-0001`... */
    fun generate(now: Instant, count: Int = DEFAULT_COUNT): List<IncidentReport> {
        val leakDays = listOf(48, 31, 12, 2)
        return (0 until count.coerceIn(0, 200)).map { i ->
            val t = if (i < leakDays.size) templates[0] else templates[i % templates.size]
            val daysAgo = if (i < leakDays.size) leakDays[i] else (3 + (i * 7) % 52)
            val hour = (8 + (i * 5) % 11)
            val at = now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(daysAgo.toLong()).atTime(hour, (i * 13) % 60).toInstant(ZoneOffset.UTC)
            report(String.format("%s%04d", PREFIX, i + 1), t, at.toString())
        }
    }

    private fun report(id: String, t: Template, at: String): IncidentReport {
        val f = mutableMapOf<String, FieldEntry>()
        f[IncidentField.INCIDENT_TYPE.key] = FieldEntry(textValue(t.type), 0.9)
        f[IncidentField.INCIDENT_DESCRIPTION.key] = FieldEntry(textValue(t.description), 0.9)
        f[IncidentField.INCIDENT_LOCATION.key] = FieldEntry(textValue(t.place), 0.9)
        f[IncidentField.SEVERITY_LEVEL.key] = FieldEntry(textValue(t.severity), 0.9)
        f[IncidentField.DATE_TIME.key] = FieldEntry(textValue(at), 0.9)
        t.actions?.let { f[IncidentField.IMMEDIATE_ACTIONS_TAKEN.key] = FieldEntry(textValue(it), 0.9) }
        t.injury?.let { f[IncidentField.INJURY.key] = FieldEntry(boolValue(it), 0.9, FieldSource.AI) }
        t.active?.let { f[IncidentField.HAZARD_STILL_ACTIVE.key] = FieldEntry(boolValue(it), 0.9) }
        return IncidentReport(
            reportId = id, status = IncidentReport.STATUS_SUBMITTED, createdAt = at,
            input = ReportInput("voice", "${t.description} (demo data)"), fields = f,
            validation = Validation(isComplete = true),
        )
    }
}
