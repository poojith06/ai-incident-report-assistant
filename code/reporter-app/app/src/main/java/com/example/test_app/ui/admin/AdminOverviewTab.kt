package com.example.test_app.ui.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.test_app.admin.AdminUiState
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.admin.Overview
import com.example.test_app.admin.ReportCounts
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme

private val SEVERITY_ORDER = listOf("Critical", "High", "Medium", "Low", "Unknown")
private const val ID_PREVIEW = 5

/**
 * Overview tab of the admin dashboard (ADR-058): counters, severity and type breakdown, reports per day, reports per type and the health
 * of the knowledge graph. All numbers come from `state.overview`. See `doc/ADMIN_DASHBOARD.md`.
 */
@Composable
fun AdminOverviewTab(state: AdminUiState, vm: AdminViewModel) {
    val o = state.overview
    if (o == null) {
        AdminEmptyText("No overview yet. Tap reload.", Modifier.padding(top = AppTheme.spacing.lg))
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = AppTheme.spacing.sm),
    ) {
        item { CountersCard(o) }
        item { BreakdownCard(o.last30) }
        item {
            SurfaceCard {
                CardTitle("Reports per day (last 14 days)")
                BarChart(o.perDay.map { it.date.dayOfMonth.toString() to it.count }, description = perDayDescription(o))
            }
        }
        item {
            SurfaceCard {
                CardTitle("Reports per type (last 30 days)")
                HorizontalBars(o.perType, maxItems = 8)
            }
        }
        item { GraphHealthCard(o) }
    }
}

private fun perDayDescription(o: Overview): String =
    "Reports per day over the last ${o.perDay.size} days: " + o.perDay.joinToString(", ") { "${it.date}: ${it.count}" }

@Composable
private fun CardTitle(text: String) = Text(text, style = AppTheme.type.title, color = AppTheme.colors.onSurface)

@Composable
private fun CountersCard(o: Overview) {
    val cells = listOf(
        "Reports (all)" to o.allTime.total, "Reports (7 days)" to o.last7.total, "Reports (30 days)" to o.last30.total,
        "Analyses run" to o.analysesRun, "Abstained analyses" to o.abstainedAnalyses, "Suppressed recommendations" to o.suppressedTotal,
        "Outbox pending" to o.outboxPending, "Demo reports" to o.demoReports, "Active graph version" to o.graphVersion,
        "Nodes" to o.nodeCount, "Edges" to o.edgeCount, "Sources" to o.sourceCount,
        "Dialogue logs" to o.dialogueLogCount, "Eval runs" to o.evalRunCount,
    )
    SurfaceCard {
        CardTitle("Counters")
        cells.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                pair.forEach { (label, value) -> Counter(label, value, Modifier.weight(1f)) }
                if (pair.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
    }
}

@Composable
private fun Counter(label: String, value: Int, modifier: Modifier) {
    Column(modifier) {
        Text(value.toString(), style = AppTheme.type.headline, color = AppTheme.colors.primary)
        Text(label, style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
    }
}

@Composable
private fun BreakdownCard(counts: ReportCounts) {
    val severities = SEVERITY_ORDER.map { it to (counts.bySeverity[it] ?: 0) } +
        counts.bySeverity.filterKeys { it !in SEVERITY_ORDER }.map { it.key to it.value }
    SurfaceCard {
        CardTitle("Last 30 days by severity")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            severities.forEach { (name, n) -> AdminPill("$name $n", adminSeverityColor(name)) }
        }
        CardTitle("Last 30 days by type")
        if (counts.byType.isEmpty()) {
            AdminEmptyText("No reports in the last 30 days.")
        } else {
            counts.byType.entries.forEach { (type, n) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(type, style = AppTheme.type.body, color = AppTheme.colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(n.toString(), style = AppTheme.type.body, color = AppTheme.colors.onSurface, modifier = Modifier.padding(start = AppTheme.spacing.sm))
                }
            }
        }
    }
}

@Composable
private fun GraphHealthCard(o: Overview) {
    val c = AppTheme.colors
    SurfaceCard {
        CardTitle("Graph health")
        HealthLine("Sources expired", o.sourcesExpired.size, c.error, o.sourcesExpired)
        HealthLine("Sources expiring within 30 days", o.sourcesExpiringSoon.size, c.warning, o.sourcesExpiringSoon)
        HealthLine("Unverified campus / team nodes", o.unverifiedNodes, c.warning, emptyList())
        HealthLine("Non-approved recommendations", o.nonApprovedRecommendations, c.warning, emptyList())
    }
}

@Composable
private fun HealthLine(label: String, count: Int, badColor: androidx.compose.ui.graphics.Color, ids: List<String>) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            AdminPill(if (count == 0) "OK" else count.toString(), if (count == 0) c.success else badColor)
            Text(label, style = AppTheme.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
        }
        if (count > 0 && ids.isNotEmpty()) {
            val more = ids.size - ID_PREVIEW
            Text(
                ids.take(ID_PREVIEW).joinToString(", ") + if (more > 0) " and $more more" else "",
                style = AppTheme.type.caption, color = badColor, modifier = Modifier.padding(top = AppTheme.spacing.xs),
            )
        }
    }
}
