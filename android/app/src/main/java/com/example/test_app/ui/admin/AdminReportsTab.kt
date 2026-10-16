package com.example.test_app.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.test_app.admin.AdminUiState
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.admin.DemoData
import com.example.test_app.admin.OverviewStats
import com.example.test_app.admin.ReportFilter
import com.example.test_app.admin.ReportFilters
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.ui.components.LinkButton
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme
import java.time.Instant

private const val DEMO_ONLY = "Demo only"
private const val REAL_ONLY = "Real only"

private enum class DateEdge { FROM, TO }

/**
 * Reports tab (ADR-058): sticky filters (search, type, severity, dates, demo/real) above a list of all saved reports; tapping a row opens
 * [AdminReportDetail] full-tab, and back (arrow or system back) returns to the list. See `doc/ADMIN_DASHBOARD.md`.
 */
@Composable
fun AdminReportsTab(state: AdminUiState, vm: AdminViewModel) {
    val snapshot = state.snapshot ?: return
    val selected = state.selectedReportId?.let { id -> snapshot.reports.firstOrNull { it.reportId == id } }
    BackHandler(enabled = state.selectedReportId != null) { vm.openReport(null) }
    if (selected != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.openReport(null) }, modifier = Modifier.size(AppTheme.sizes.minTouch)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the report list", tint = AppTheme.colors.onBackground)
                }
                Text("Report ${selected.reportId}", style = AppTheme.type.title, color = AppTheme.colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AdminReportDetail(selected, snapshot, vm, Modifier.weight(1f))
        }
        return
    }
    ReportList(state, vm)
}

@Composable
private fun ReportList(state: AdminUiState, vm: AdminViewModel) {
    val snapshot = state.snapshot ?: return
    val filter = state.reportFilter
    val types = remember(snapshot.reports) { ReportFilters.types(snapshot.reports) }
    val severities = remember(snapshot.reports) { ReportFilters.severities(snapshot.reports) }
    val rows = remember(snapshot.reports, filter) { ReportFilters.apply(snapshot.reports, filter) }
    Column(Modifier.fillMaxSize()) {
        FilterArea(filter, types, severities, vm::setReportFilter)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${rows.size} of ${snapshot.reports.size} reports", style = AppTheme.type.caption, color = AppTheme.colors.mutedText,
                modifier = Modifier.weight(1f),
            )
            if (filter.isActive) LinkButton("Clear filters", { vm.setReportFilter(ReportFilter()) })
        }
        if (rows.isEmpty()) {
            AdminEmptyText(if (snapshot.reports.isEmpty()) ADMIN_CHART_EMPTY_TEXT else "No report matches the filters.", Modifier.padding(top = AppTheme.spacing.md))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm), contentPadding = PaddingValues(bottom = AppTheme.spacing.md)) {
            items(rows, key = { it.reportId }) { r -> ReportRow(r, snapshot.auditFor(r.reportId) != null) { vm.openReport(r.reportId) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterArea(filter: ReportFilter, types: List<String>, severities: List<String>, onChange: (ReportFilter) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<DateEdge?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = AppTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        AdminSearchField(filter.query, { onChange(filter.copy(query = it)) }, "Search id, type, place, description")
        AdminChipRow(types, filter.type, { onChange(filter.copy(type = it)) }, allLabel = "All types")
        AdminChipRow(severities, filter.severity, { onChange(filter.copy(severity = it)) }, allLabel = "All severities")
        AdminChipRow(
            listOf(DEMO_ONLY, REAL_ONLY), when (filter.demoOnly) { true -> DEMO_ONLY; false -> REAL_ONLY; null -> null },
            { onChange(filter.copy(demoOnly = when (it) { DEMO_ONLY -> true; REAL_ONLY -> false; else -> null })) }, allLabel = "All reports",
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { editing = DateEdge.FROM }, modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch)) { Text("From: ${filter.from ?: "any"}", style = AppTheme.type.label) }
            OutlinedButton(onClick = { editing = DateEdge.TO }, modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch)) { Text("To: ${filter.to ?: "any"}", style = AppTheme.type.label) }
            if (filter.from != null || filter.to != null) LinkButton("Clear dates", { onChange(filter.copy(from = null, to = null)) })
        }
    }
    editing?.let { edge ->
        val current = if (edge == DateEdge.FROM) filter.from else filter.to
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = current?.atStartOfDay(OverviewStats.zone)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                TextButton(onClick = {
                    val d = pickerState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(OverviewStats.zone).toLocalDate() }
                    onChange(if (edge == DateEdge.FROM) filter.copy(from = d) else filter.copy(to = d))
                    editing = null
                }, modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch)) { Text("OK", color = AppTheme.colors.primary) }
            },
            dismissButton = { TextButton(onClick = { editing = null }, modifier = Modifier.heightIn(min = AppTheme.sizes.minTouch)) { Text("Cancel", color = AppTheme.colors.mutedText) } },
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun ReportRow(r: IncidentReport, analysed: Boolean, onOpen: () -> Unit) {
    val c = AppTheme.colors
    val severity = OverviewStats.reportSeverity(r)
    val place = r.fields[IncidentField.INCIDENT_LOCATION.key]?.value?.asTextOrNull().orEmpty().ifBlank { "place unknown" }
    SurfaceCard(Modifier.clickable(onClickLabel = "Open report ${r.reportId}", onClick = onOpen)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            Text(r.reportId, style = AppTheme.type.label, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (DemoData.isDemo(r)) AdminPill("DEMO", c.warning)
            AdminPill(severity, adminSeverityColor(severity))
        }
        Text(OverviewStats.reportType(r), style = AppTheme.type.body, color = c.onSurface)
        Text(place, style = AppTheme.type.caption, color = c.mutedText)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(r.createdAt, style = AppTheme.type.caption, color = c.mutedText, modifier = Modifier.weight(1f))
            if (analysed) AdminPill("analysed", c.success)
        }
    }
}
