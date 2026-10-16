package com.example.test_app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.example.test_app.admin.AdminUiState
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.admin.LogKind
import com.example.test_app.knowledgegraph.IncidentAnalysisExport
import com.example.test_app.knowledgegraph.admin.GraphPackCodec
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme

/**
 * Outbox tab (ADR-058): the analysis exports queued in `files/kg/outbox`, each expandable to its JSON and shareable, plus export-all and
 * clear. Nothing is sent anywhere: network sync is future work. See `doc/ADMIN_DASHBOARD.md`.
 */
@Composable
fun AdminSyncTab(state: AdminUiState, vm: AdminViewModel) {
    val snapshot = state.snapshot ?: return
    val context = LocalContext.current
    val c = AppTheme.colors
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val outbox = snapshot.outbox
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm), contentPadding = PaddingValues(vertical = AppTheme.spacing.sm)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                Text("Outbox (${outbox.size})", style = AppTheme.type.title, color = c.onBackground)
                Text("Queued exports are files in files/kg/outbox. Network sync is future work: nothing is sent.", style = AppTheme.type.caption, color = c.mutedText)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                SecondaryButton("Export all as bundle", { AdminShare.shareText(context, "Outbox bundle", vm.bundleJson(LogKind.OUTBOX)) }, enabled = outbox.isNotEmpty())
                SecondaryButton("Clear outbox", { confirmClear = true }, enabled = outbox.isNotEmpty() && state.busy == null)
            }
        }
        if (outbox.isEmpty()) item { AdminEmptyText("The outbox is empty. Analysing a report queues one export here.") }
        items(outbox) { e ->
            val open = expanded == e.exportId
            SurfaceCard {
                Column(
                    Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Hide the JSON" else "Show the JSON") { expanded = if (open) null else e.exportId },
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                        Text(e.incident.incidentId, style = AppTheme.type.label, color = c.onSurface, modifier = Modifier.weight(1f))
                        if (e.abstained) AdminPill("ABSTAINED", c.warning)
                        AdminPill("QUEUED", c.primary)
                    }
                    Text(e.incident.type ?: "Unknown type", style = AppTheme.type.body, color = c.onSurface)
                    Text(
                        "Graph v${e.graphVersion}, ${e.recommendations.size} recommendation(s), created ${e.createdAt}",
                        style = AppTheme.type.caption, color = c.mutedText,
                    )
                }
                if (open) OutboxJson(e)
                SecondaryButton("Share", { AdminShare.shareText(context, "Analysis export ${e.incident.incidentId}", prettyJson(e)) })
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the outbox?") },
            text = { Text("This deletes ${outbox.size} queued export file(s). Nothing was sent, so they cannot be recovered. Reports and audit logs stay.") },
            confirmButton = { TextButton({ confirmClear = false; vm.clearLogs(LogKind.OUTBOX) }) { Text("Clear", color = c.error) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel", color = c.mutedText) } },
            containerColor = c.surface, titleContentColor = c.onSurface, textContentColor = c.mutedText,
        )
    }
}

private fun prettyJson(e: IncidentAnalysisExport): String = GraphPackCodec.pretty.encodeToString(IncidentAnalysisExport.serializer(), e)

@Composable
private fun OutboxJson(e: IncidentAnalysisExport) {
    val json = remember(e) { prettyJson(e) }
    JsonBlock(json, Modifier.padding(top = AppTheme.spacing.xs))
}
