package com.example.test_app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.example.test_app.admin.AdminUiState
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.admin.LogEntry
import com.example.test_app.admin.LogKind
import com.example.test_app.admin.LogLevel
import com.example.test_app.admin.LogParser
import com.example.test_app.ui.components.LinkButton
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme

/**
 * The Logs tab of the admin dashboard: analysis audit, dialogue, eval runs and outbox entries with search, level and type filters,
 * an expandable JSON viewer per entry, sharing and clearing. See `doc/ADMIN_DASHBOARD.md`.
 */
@Composable
fun AdminLogsTab(state: AdminUiState, vm: AdminViewModel) {
    val snapshot = state.snapshot ?: return
    val s = AppTheme.spacing
    val c = AppTheme.colors
    val context = LocalContext.current
    val filter = state.logFilter
    val kind = filter.kind
    val all = remember(snapshot, kind) { LogParser.entries(snapshot, kind) }
    val shown = remember(all, filter) { LogParser.filter(all, filter) }
    val types = remember(all) { LogParser.types(all) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(s.xs)) {
        AdminSegmentRow(LogKind.entries, kind, { it.label }) { vm.setLogFilter(filter.copy(kind = it)) }
        AdminSearchField(filter.query, { vm.setLogFilter(filter.copy(query = it)) }, "Search ${kind.label.lowercase()}")
        AdminChipRow(
            options = LogLevel.entries.map { it.name }, selected = filter.level?.name, allLabel = "All levels",
            onSelect = { n -> vm.setLogFilter(filter.copy(level = LogLevel.entries.firstOrNull { it.name == n })) },
        )
        if (types.size > 1) AdminChipRow(options = types, selected = filter.type, allLabel = "All types", onSelect = { vm.setLogFilter(filter.copy(type = it)) })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(s.sm)) {
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(s.xs)) {
                    Text("${shown.size} of ${all.size} entries", style = AppTheme.type.label, color = c.mutedText, modifier = Modifier.padding(top = s.xs))
                    SecondaryButton("Share all (${kind.label})", { AdminShare.shareText(context, "KG logs", vm.bundleJson(kind)) }, enabled = all.isNotEmpty())
                    SecondaryButton("Share every log bundle", { AdminShare.shareText(context, "KG logs", vm.bundleJson(null)) })
                    SecondaryButton("Clear ${kind.label} logs", { confirmClear = true }, enabled = all.isNotEmpty())
                }
            }
            if (shown.isEmpty()) item(key = "empty") {
                Text(emptyText(kind, all.isEmpty()), style = AppTheme.type.body, color = c.mutedText, modifier = Modifier.padding(vertical = s.md))
            }
            items(shown, key = { it.id }) { e ->
                LogRow(
                    e, e.id in expanded, onToggle = { expanded = if (e.id in expanded) expanded - e.id else expanded + e.id },
                    onShare = { AdminShare.shareText(context, e.title, e.json) },
                    onOpenReport = e.reportId?.let { id -> { vm.openReport(id) } },
                    onShowInGraph = if (e.kind == LogKind.AUDIT) e.reportId?.let { id -> { vm.showIncidentInGraph(id) } } else null,
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear ${kind.label} logs?") },
            text = { Text("This removes ${clearCount(kind, snapshot.audits.size, snapshot.dialogueLogs.size, snapshot.evalRuns.size, snapshot.outbox.size)} from this device. It cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearLogs(kind) }) { Text("Clear", color = c.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            containerColor = c.surfaceElevated, titleContentColor = c.onSurface, textContentColor = c.mutedText,
        )
    }
}

private fun clearCount(kind: LogKind, audits: Int, dialogues: Int, evals: Int, outbox: Int): String = when (kind) {
    LogKind.AUDIT -> "$audits audit file(s)"
    LogKind.DIALOGUE -> "$dialogues dialogue log file(s)"
    LogKind.EVAL -> "$evals eval run(s)"
    LogKind.OUTBOX -> "$outbox queued export file(s)"
}

private fun emptyText(kind: LogKind, noneAtAll: Boolean): String = if (!noneAtAll) "No entries match the filters." else when (kind) {
    LogKind.AUDIT -> "No analysis audit logs yet. Open a report and run the analysis."
    LogKind.DIALOGUE -> "No dialogue logs yet. They are written while a report is being collected."
    LogKind.EVAL -> "No eval runs yet. Run the eval from the developer menu."
    LogKind.OUTBOX -> "The outbox is empty."
}

@Composable
private fun LogRow(
    e: LogEntry, open: Boolean, onToggle: () -> Unit, onShare: () -> Unit, onOpenReport: (() -> Unit)?, onShowInGraph: (() -> Unit)?,
) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    val levelColor = when (e.level) { LogLevel.INFO -> c.success; LogLevel.WARN -> c.warning; LogLevel.ERROR -> c.error }
    SurfaceCard(Modifier.clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle).heightIn(min = AppTheme.sizes.minTouch)) {
        Row(horizontalArrangement = Arrangement.spacedBy(s.sm), verticalAlignment = Alignment.CenterVertically) {
            AdminPill(e.level.name, levelColor)
            AdminPill(e.type, c.mutedText)
        }
        Text(e.title, style = AppTheme.type.label, color = c.onSurface, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        if (e.subtitle.isNotBlank()) Text(e.subtitle, style = AppTheme.type.caption, color = c.mutedText, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        if (e.time.isNotBlank()) Text(e.time, style = AppTheme.type.caption, color = c.mutedText)
        if (open) {
            JsonBlock(e.json)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(s.xs)) {
                LinkButton("Share", onShare)
                if (onOpenReport != null) LinkButton("Open report", onOpenReport)
                if (onShowInGraph != null) LinkButton("Show in graph", onShowInGraph)
            }
        }
    }
}
