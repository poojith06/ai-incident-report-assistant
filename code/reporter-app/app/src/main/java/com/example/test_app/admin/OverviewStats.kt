package com.example.test_app.admin

import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.SourceStatus
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.knowledgegraph.rules.Instants
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Report counts for a time window (or all time). */
data class ReportCounts(val total: Int, val bySeverity: Map<String, Int>, val byType: Map<String, Int>)

/** Reports of one calendar day (UTC offset of [Overview.zone]). */
data class DayCount(val date: LocalDate, val count: Int)

/** Everything the Overview tab shows. All numbers are derived from one [AdminSnapshot]. */
data class Overview(
    val allTime: ReportCounts,
    val last7: ReportCounts,
    val last30: ReportCounts,
    /** The last 14 days, oldest first, zero days included. */
    val perDay: List<DayCount>,
    /** Reports per incident type, last 30 days, most first. */
    val perType: List<Pair<String, Int>>,
    val analysesRun: Int,
    val abstainedAnalyses: Int,
    val suppressedTotal: Int,
    val outboxPending: Int,
    val demoReports: Int,
    val graphVersion: Int,
    val nodeCount: Int,
    val edgeCount: Int,
    val sourceCount: Int,
    val sourcesExpired: List<String>,
    val sourcesExpiringSoon: List<String>,
    val unverifiedNodes: Int,
    val nonApprovedRecommendations: Int,
    val dialogueLogCount: Int,
    val evalRunCount: Int,
)

/** Pure aggregation for the Overview tab (spec of the admin dashboard, ADR-058). Dates use the report's `created_at`; unreadable dates count only in the all-time numbers. */
object OverviewStats {
    const val DAYS_CHART = 14
    const val EXPIRY_SOON_DAYS = 30L
    val zone: ZoneOffset = ZoneOffset.UTC

    fun reportType(r: IncidentReport): String = r.fields[IncidentField.INCIDENT_TYPE.key]?.value?.asTextOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown"
    fun reportSeverity(r: IncidentReport): String = r.fields[IncidentField.SEVERITY_LEVEL.key]?.value?.asTextOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown"
    fun reportInstant(r: IncidentReport): Instant? = Instants.parse(r.createdAt)

    private fun counts(list: List<IncidentReport>) = ReportCounts(
        list.size,
        list.groupingBy { reportSeverity(it) }.eachCount().toSortedMap(compareBy<String>({ severityOrder(it) }, { it })),
        list.groupingBy { reportType(it) }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).associate { it.key to it.value },
    )

    fun severityOrder(s: String): Int = listOf("Critical", "High", "Medium", "Low").indexOf(s).let { if (it < 0) 9 else it }

    fun compute(s: AdminSnapshot, now: Instant): Overview {
        val dated = s.reports.mapNotNull { r -> reportInstant(r)?.let { r to it } }
        fun within(days: Long) = dated.filter { !it.second.isBefore(now.minusSeconds(days * 86_400)) && !it.second.isAfter(now.plusSeconds(86_400)) }.map { it.first }
        val today = now.atOffset(zone).toLocalDate()
        val perDay = (DAYS_CHART - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            DayCount(d, dated.count { it.second.atOffset(zone).toLocalDate() == d })
        }
        val last30 = within(30)
        val pack = s.pack
        val expired = pack.sources.filter { it.status == SourceStatus.EXPIRED || Instants.isExpired(it.expiresAt, now) }.map { it.id }
        val soonLimit = now.plusSeconds(EXPIRY_SOON_DAYS * 86_400)
        val soon = pack.sources.filter { src ->
            src.id !in expired && Instants.parse(src.expiresAt)?.let { it.isAfter(now) && !it.isAfter(soonLimit) } == true
        }.map { it.id }
        return Overview(
            allTime = counts(s.reports), last7 = counts(within(7)), last30 = counts(last30), perDay = perDay,
            perType = counts(last30).byType.entries.map { it.key to it.value },
            analysesRun = s.audits.size, abstainedAnalyses = s.audits.count { it.abstained }, suppressedTotal = s.audits.sumOf { it.suppressed.size },
            outboxPending = s.outbox.size, demoReports = s.reports.count { DemoData.isDemo(it) }, graphVersion = pack.packVersion,
            nodeCount = pack.nodes.size, edgeCount = pack.edges.size, sourceCount = pack.sources.size, sourcesExpired = expired, sourcesExpiringSoon = soon,
            unverifiedNodes = pack.nodes.count { it.verificationStatus != VerificationStatus.VERIFIED && it.type in UNVERIFIED_TYPES },
            nonApprovedRecommendations = pack.nodes.count { it.type == NodeType.RECOMMENDATION && it.str("approvalStatus") != ApprovalStatus.APPROVED.name },
            dialogueLogCount = s.dialogueLogs.size, evalRunCount = s.evalRuns.size,
        )
    }

    /** Node types whose verification matters to an admin (campus entities, teams, contacts). */
    private val UNVERIFIED_TYPES = setOf(NodeType.CAMPUS, NodeType.BUILDING, NodeType.FLOOR, NodeType.ZONE, NodeType.ROOM, NodeType.RESPONSE_TEAM, NodeType.EMERGENCY_CONTACT)
}
