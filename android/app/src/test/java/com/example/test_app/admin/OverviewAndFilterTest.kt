package com.example.test_app.admin

import com.example.test_app.admin.AdminTestSupport.report
import com.example.test_app.admin.AdminTestSupport.snapshot
import com.example.test_app.knowledgegraph.PackBuilder
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.SourceStatus
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.knowledgegraph.props
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Overview aggregation, report filters (pure). "Now" is 2026-10-11T06:00Z. */
class OverviewAndFilterTest {
    private val now = AdminTestSupport.now

    private fun at(daysAgo: Int, hour: Int = 9) = LocalDate.of(2026, 10, 11).minusDays(daysAgo.toLong()).atTime(hour, 0).toInstant(java.time.ZoneOffset.UTC).toString()

    private val reports = listOf(
        report("R1", "Fire", "High", createdAt = at(0, 1)),
        report("R2", "Fire", "Critical", createdAt = at(1)),
        report("R3", "Water Leak", "Medium", createdAt = at(1, 15), place = "Pantry", description = "pipe leak"),
        report("R4", "Water Leak", "Low", createdAt = at(6)),
        report("R5", "Injury", "High", createdAt = at(20), transcript = "someone fell"),
        report("R6", "Fire", null, createdAt = at(40)),
        report("DEMO-0001", "Spill", "Low", createdAt = at(3)),
        report("R8", null, "Low", createdAt = "not a date"),
    )

    @Test fun reportsAreCountedInTheWindows() {
        val o = OverviewStats.compute(snapshot(reports), now)
        assertEquals(8, o.allTime.total)
        assertEquals(5, o.last7.total) // R1 R2 R3 R4 DEMO (0,1,1,6,3 days ago)
        assertEquals(6, o.last30.total) // plus R5 (20 days)
        assertEquals(1, o.demoReports)
    }

    @Test fun severityAndTypeCountsAreOrderedAndUnknownIsNamed() {
        val o = OverviewStats.compute(snapshot(reports), now)
        assertEquals(listOf("Critical", "High", "Medium", "Low", "Unknown"), o.allTime.bySeverity.keys.toList())
        assertEquals(3, o.allTime.bySeverity["Low"])
        assertEquals(1, o.allTime.bySeverity["Unknown"])
        assertEquals("Fire", o.allTime.byType.keys.first()); assertEquals(3, o.allTime.byType["Fire"]); assertEquals(1, o.allTime.byType["Unknown"])
        assertEquals("Fire", o.perType.first().first)
    }

    @Test fun theChartHasFourteenDaysOldestFirstWithZeroDays() {
        val o = OverviewStats.compute(snapshot(reports), now)
        assertEquals(14, o.perDay.size)
        assertEquals(LocalDate.of(2026, 10, 11), o.perDay.last().date); assertEquals(LocalDate.of(2026, 9, 28), o.perDay.first().date)
        assertEquals(1, o.perDay.last().count); assertEquals(2, o.perDay[12].count)
        assertEquals(0, o.perDay[11].count); assertEquals(1, o.perDay.first { it.date == LocalDate.of(2026, 10, 5) }.count)
        assertEquals(5, o.perDay.sumOf { it.count }) // R1 R2 R3 R4 DEMO
    }

    @Test fun anEmptyDashboardHasZeroEverywhere() {
        val o = OverviewStats.compute(snapshot(), now)
        assertEquals(0, o.allTime.total); assertTrue(o.perDay.all { it.count == 0 }); assertEquals(0, o.analysesRun); assertEquals(0, o.suppressedTotal); assertEquals(0, o.outboxPending)
        assertEquals(AdminTestSupport.seedPack.nodes.size, o.nodeCount); assertEquals(AdminTestSupport.seedPack.edges.size, o.edgeCount); assertEquals(1, o.graphVersion)
    }

    @Test fun analysesSuppressionsAndOutboxAreSummed() {
        val a1 = AdminTestSupport.auditOf(AdminTestSupport.scenarioIncident(2))
        val a2 = AdminTestSupport.auditOf(AdminTestSupport.scenarioIncident(2).copy(userConfirmed = false, incidentId = "X"))
        val o = OverviewStats.compute(snapshot(audits = listOf(a1, a2)), now)
        assertEquals(2, o.analysesRun); assertEquals(1, o.abstainedAnalyses); assertEquals(a1.suppressed.size + a2.suppressed.size, o.suppressedTotal)
    }

    @Test fun sourcesExpiredAndExpiringSoonAreListed() {
        val pack = PackBuilder.basic("a")
            .source(KgSource("sop:expired", "x", approvedBy = "r", approvedAt = "2026-01-01T00:00:00Z", expiresAt = "2026-10-01T00:00:00Z", status = SourceStatus.APPROVED))
            .source(KgSource("sop:soon", "x", approvedBy = "r", approvedAt = "2026-01-01T00:00:00Z", expiresAt = "2026-10-25T00:00:00Z", status = SourceStatus.APPROVED))
            .source(KgSource("sop:later", "x", approvedBy = "r", approvedAt = "2026-01-01T00:00:00Z", expiresAt = "2027-10-25T00:00:00Z", status = SourceStatus.APPROVED))
            .source(KgSource("sop:flagged", "x", status = SourceStatus.EXPIRED)).build()
        val o = OverviewStats.compute(snapshot(pack = pack), now)
        assertEquals(setOf("sop:expired", "sop:flagged"), o.sourcesExpired.toSet()); assertEquals(listOf("sop:soon"), o.sourcesExpiringSoon)
    }

    @Test fun unverifiedAndNonApprovedItemsAreCounted() {
        val pack = PackBuilder.basic("a").action("b").rec("b", status = ApprovalStatus.DRAFT)
            .node("building:x", NodeType.BUILDING, "X", verified = VerificationStatus.NEEDS_ADMIN_VERIFICATION)
            .node("building:y", NodeType.BUILDING, "Y", verified = VerificationStatus.VERIFIED).node("hazard:q", NodeType.HAZARD, "q", props("code" to "Q")).build()
        val o = OverviewStats.compute(snapshot(pack = pack), now)
        assertEquals(1, o.nonApprovedRecommendations)
        assertEquals(2, o.unverifiedNodes) // team:ops + building:x (hazards do not count)
    }

    // ---- filters --------------------------------------------------------------------------------------------

    @Test fun filtersByTypeSeverityAndDemo() {
        assertEquals(listOf("R1", "R2", "R6"), ReportFilters.apply(reports, ReportFilter(type = "fire")).map { it.reportId }.sorted())
        assertEquals(listOf("R1", "R5"), ReportFilters.apply(reports, ReportFilter(severity = "High")).map { it.reportId }.sorted())
        assertEquals(listOf("DEMO-0001"), ReportFilters.apply(reports, ReportFilter(demoOnly = true)).map { it.reportId })
        assertEquals(7, ReportFilters.apply(reports, ReportFilter(demoOnly = false)).size)
    }

    @Test fun filtersByInclusiveDateRangeAndDropsUnreadableDates() {
        val r = ReportFilters.apply(reports, ReportFilter(from = LocalDate.of(2026, 10, 10), to = LocalDate.of(2026, 10, 11)))
        assertEquals(setOf("R1", "R2", "R3"), r.map { it.reportId }.toSet())
        assertTrue(ReportFilters.apply(reports, ReportFilter(to = LocalDate.of(2026, 1, 1))).isEmpty())
        assertEquals(7, ReportFilters.apply(reports, ReportFilter(from = LocalDate.of(2026, 1, 1))).size) // R8 has no readable date
    }

    @Test fun searchMatchesIdTypePlaceDescriptionAndTranscript() {
        fun ids(q: String) = ReportFilters.apply(reports, ReportFilter(query = q)).map { it.reportId }.toSet()
        assertEquals(setOf("R3"), ids("pantry")); assertEquals(setOf("R3"), ids("PIPE LEAK")); assertEquals(setOf("R5"), ids("fell")); assertEquals(setOf("DEMO-0001"), ids("demo-0001"))
        assertEquals(setOf("R4", "R3"), ids("water leak")); assertEquals(8, ids("  ").size); assertTrue(ids("zzz").isEmpty())
    }

    @Test fun resultsAreNewestFirstAndFiltersCombine() {
        assertEquals(listOf("R1", "R3", "R2", "DEMO-0001", "R4", "R5", "R6", "R8"), ReportFilters.apply(reports, ReportFilter()).map { it.reportId })
        assertEquals(listOf("R2"), ReportFilters.apply(reports, ReportFilter(type = "Fire", severity = "Critical", query = "fire")).map { it.reportId })
        assertTrue(ReportFilter(type = "Fire").isActive); assertTrue(!ReportFilter().isActive)
    }

    @Test fun filterChipOptionsAreDistinctAndOrdered() {
        assertEquals(listOf("Fire", "Injury", "Spill", "Unknown", "Water Leak"), ReportFilters.types(reports))
        assertEquals(listOf("Critical", "High", "Medium", "Low", "Unknown"), ReportFilters.severities(reports))
        assertEquals(Instant.parse("2026-10-11T06:00:00Z"), now)
    }
}
