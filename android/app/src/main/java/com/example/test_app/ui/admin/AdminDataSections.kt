package com.example.test_app.ui.admin

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.test_app.admin.AdminUiState
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.admin.PreviewChoices
import com.example.test_app.knowledgegraph.model.IncidentStatus
import com.example.test_app.knowledgegraph.model.KgSeverity
import com.example.test_app.knowledgegraph.model.RecommendationItem
import com.example.test_app.knowledgegraph.model.RecommendationSet
import com.example.test_app.ui.components.LinkButton
import com.example.test_app.ui.components.PrimaryButton
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Preview, Import / export and Versions modes of the Graph data tab, as items of its LazyColumn. See `doc/ADMIN_DASHBOARD.md`. */

// ---- preview -------------------------------------------------------------------------------------------------------

internal fun LazyListScope.previewItems(state: AdminUiState, vm: AdminViewModel) {
    item(key = "preview-form") { PreviewForm(state, vm) }
    val result = state.editor.preview
    if (result != null) item(key = "preview-result") { PreviewResultView(state, vm) }
}

@Composable
private fun PreviewForm(state: AdminUiState, vm: AdminViewModel) {
    val pack = state.snapshot?.pack ?: return
    val choices = remember(pack) { PreviewChoices.from(pack) }
    val spec = state.editor.previewSpec
    SurfaceCard {
        Text("Sample incident", style = AppTheme.type.title, color = AppTheme.colors.onSurface)
        Text("Pick the facts of an incident and run the engine for ordinary users and for the admin preview.", style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        Text("Incident type (required)", style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        AdminChipRow(choices.incidentTypes, spec.type, { vm.setPreviewSpec(spec.copy(type = it)) }, allLabel = "None")
        Text("Location type", style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        AdminChipRow(choices.locationTypes, spec.locationType, { vm.setPreviewSpec(spec.copy(locationType = it)) }, allLabel = "Unknown")
        Text("Severity", style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        AdminChipRow(KgSeverity.entries.map { it.name }, spec.severity?.name, { n -> vm.setPreviewSpec(spec.copy(severity = KgSeverity.entries.firstOrNull { it.name == n })) }, allLabel = "Unknown")
        Text("Status", style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        AdminSegmentRow(IncidentStatus.entries, spec.status, { it.name }) { vm.setPreviewSpec(spec.copy(status = it)) }
        Text("Affected assets", style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        AdminMultiChipRow(choices.assets, spec.assets) { vm.setPreviewSpec(spec.copy(assets = it)) }
        Text("Active hazards", style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        AdminMultiChipRow(choices.hazards, spec.hazards) { vm.setPreviewSpec(spec.copy(hazards = it)) }
        Text("Injury", style = AppTheme.type.caption, color = AppTheme.colors.mutedText)
        AdminChipRow(listOf("Yes", "No"), when (spec.injury) { true -> "Yes"; false -> "No"; null -> null }, { n -> vm.setPreviewSpec(spec.copy(injury = when (n) { "Yes" -> true; "No" -> false; else -> null })) }, allLabel = "Unknown")
        PrimaryButton("Run preview", vm::runPreview, enabled = spec.type != null && state.busy == null)
    }
}

@Composable
private fun PreviewResultView(state: AdminUiState, vm: AdminViewModel) {
    val result = state.editor.preview ?: return
    var admin by rememberSaveable { mutableStateOf(false) }
    val set = if (admin) result.admin else result.reporter
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        AdminSegmentRow(listOf(false, true), admin, { if (it) "Admin preview" else "Reporter view" }) { admin = it }
        SecondaryButton("Show reporter path in graph", { vm.showPreviewInGraph(false) })
        SecondaryButton("Show admin path in graph", { vm.showPreviewInGraph(true) })
        RecommendationSetView(set)
    }
}

@Composable
private fun RecommendationSetView(set: RecommendationSet) {
    val c = AppTheme.colors
    SurfaceCard {
        Text("${set.audience.name} - graph v${set.graphVersion}${if (set.abstained) " - abstained" else ""}", style = AppTheme.type.label, color = c.onSurface)
        set.guidanceLabel?.let { Text(it, style = AppTheme.type.caption, color = c.warning) }
        if (set.isEmpty) Text("Nothing to show for this incident.", style = AppTheme.type.body, color = c.mutedText)
        phase("Immediate", set.immediateActions)
        phase("Operational", set.operationalSteps)
        phase("Recovery", set.recoverySteps)
        phase("Prevention", set.preventiveActions)
        if (set.consequences.isNotEmpty()) {
            Text("Consequences", style = AppTheme.type.title, color = c.onSurface)
            set.consequences.forEach { Text("${it.text} (${it.likelihood.name.lowercase()})", style = AppTheme.type.body, color = c.onSurface) }
        }
        if (set.temporaryArrangements.isNotEmpty()) {
            Text("Temporary arrangements", style = AppTheme.type.title, color = c.onSurface)
            set.temporaryArrangements.forEach { a ->
                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm), verticalAlignment = Alignment.Top) {
                    Text(a.text.ifBlank { a.title }, style = AppTheme.type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                    if (a.unverifiedDraft) AdminPill("unverified draft", c.warning)
                }
            }
        }
        if (set.responsibleTeams.isNotEmpty()) {
            Text("Teams", style = AppTheme.type.title, color = c.onSurface)
            set.responsibleTeams.forEach { t ->
                Text("${t.name} (${t.role})${if (t.verified) "" else " - unverified"}${t.contactNumber?.let { " - $it" }.orEmpty()}", style = AppTheme.type.body, color = c.onSurface)
            }
        }
        set.unresolvedQuestions.firstOrNull()?.let {
            Text("Open question", style = AppTheme.type.title, color = c.onSurface)
            Text(it.text, style = AppTheme.type.body, color = c.onSurface)
        }
        if (set.warnings.isNotEmpty()) {
            Text("Warnings", style = AppTheme.type.title, color = c.warning)
            set.warnings.forEach { Text(it, style = AppTheme.type.caption, color = c.warning) }
        }
        var showSuppressed by remember { mutableStateOf(false) }
        Text("${set.suppressed.size} suppressed", style = AppTheme.type.label, color = c.mutedText)
        if (set.suppressed.isNotEmpty()) LinkButton(if (showSuppressed) "Hide reasons" else "Show reasons", { showSuppressed = !showSuppressed })
        if (showSuppressed) set.suppressed.forEach { Text("${it.candidateId} (${it.kind}): ${it.reason}", style = AppTheme.type.caption, color = c.mutedText) }
    }
}

@Composable
private fun phase(title: String, items: List<RecommendationItem>) {
    if (items.isEmpty()) return
    val c = AppTheme.colors
    Text(title, style = AppTheme.type.title, color = c.onSurface)
    items.forEach { r ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            Text("${r.rank}. ${r.renderedText}", style = AppTheme.type.body, color = c.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                if (r.unverifiedDraft) AdminPill("unverified draft", c.warning)
                AdminPill("score ${"%.2f".format(r.score)}", c.mutedText)
            }
            Text("Owner: ${r.ownerTeamName ?: r.ownerTeamId ?: "none"}; source: ${r.sourceLabel ?: r.sourceId}", style = AppTheme.type.caption, color = c.mutedText)
        }
    }
}

// ---- import / export ---------------------------------------------------------------------------------------------------

private const val MAX_IMPORT_CHARS = 5_000_000

private fun readCapped(context: Context, uri: android.net.Uri): String? =
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
        val sb = StringBuilder()
        val buf = CharArray(16_384)
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            sb.append(buf, 0, n)
            if (sb.length > MAX_IMPORT_CHARS) return@use null
        }
        sb.toString()
    }

internal fun LazyListScope.importExportItems(state: AdminUiState, vm: AdminViewModel) {
    item(key = "import-export") { ImportExport(state, vm) }
}

@Composable
private fun ImportExport(state: AdminUiState, vm: AdminViewModel) {
    val snapshot = state.snapshot ?: return
    val context = LocalContext.current
    val c = AppTheme.colors
    val scope = rememberCoroutineScope()
    var localError by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            localError = null
            val text = try { withContext(Dispatchers.IO) { readCapped(context, uri) } } catch (e: Exception) { localError = "Could not read the file: ${e.message}"; return@launch }
            if (text == null) localError = "The file is empty, unreadable or larger than 5 MB." else vm.prepareImport(text)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
        SurfaceCard {
            Text("Export", style = AppTheme.type.title, color = c.onSurface)
            SecondaryButton("Export active pack", { AdminShare.shareText(context, "Graph pack v${snapshot.pack.packVersion}", vm.exportPackJson()) })
            Text("Import", style = AppTheme.type.title, color = c.onSurface)
            Text("Pick a graph pack JSON file. It is checked and compared with the active pack first; nothing changes until you confirm.", style = AppTheme.type.caption, color = c.mutedText)
            SecondaryButton("Import pack", { picker.launch(arrayOf("application/json", "text/plain", "*/*")) })
            localError?.let { Text(it, style = AppTheme.type.caption, color = c.error) }
        }
        state.pendingImport?.let { p ->
            SurfaceCard(borderColor = if (p.errors.isEmpty()) c.border else c.error) {
                Text("Import preview", style = AppTheme.type.title, color = c.onSurface)
                p.diff?.let { d ->
                    Text(d.summary(), style = AppTheme.type.label, color = c.onSurface)
                    if (d.isEmpty) Text("No differences from the active pack.", style = AppTheme.type.caption, color = c.mutedText)
                    if (d.addedSample.isNotEmpty()) Text("Added: ${d.addedSample.joinToString(", ")}", style = AppTheme.type.code, color = c.success)
                    if (d.changedSample.isNotEmpty()) Text("Changed: ${d.changedSample.joinToString(", ")}", style = AppTheme.type.code, color = c.warning)
                    if (d.removedSample.isNotEmpty()) Text("Removed: ${d.removedSample.joinToString(", ")}", style = AppTheme.type.code, color = c.error)
                }
                if (p.errors.isNotEmpty()) {
                    Text("${p.errors.size} error(s) block the import", style = AppTheme.type.label, color = c.error)
                    p.errors.forEach { Text(it, style = AppTheme.type.caption, color = c.error) }
                }
                if (p.warnings.isNotEmpty()) {
                    Text("${p.warnings.size} warning(s)", style = AppTheme.type.label, color = c.warning)
                    p.warnings.forEach { Text(it, style = AppTheme.type.caption, color = c.warning) }
                }
                PrimaryButton("Confirm import", { confirm = true }, enabled = p.canConfirm)
                SecondaryButton("Cancel", vm::cancelImport)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, title = { Text("Import this pack?") },
            text = { Text("Imports as the next graph version and makes it active. You can roll back.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.confirmImport() }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            containerColor = c.surfaceElevated, titleContentColor = c.onSurface, textContentColor = c.mutedText,
        )
    }
}

// ---- versions --------------------------------------------------------------------------------------------------------------

internal fun LazyListScope.versionItems(state: AdminUiState, vm: AdminViewModel) {
    val snapshot = state.snapshot ?: return
    val active = snapshot.pack.packVersion
    val versions = snapshot.versions.sortedDescending()
    item(key = "versions-head") { Text("${versions.size} graph version(s)", style = AppTheme.type.label, color = AppTheme.colors.mutedText) }
    items(versions.size, key = { "version-${versions[it]}" }) { i ->
        VersionRow(versions[i], versions[i] == active, vm)
    }
}

@Composable
private fun VersionRow(version: Int, isActive: Boolean, vm: AdminViewModel) {
    val c = AppTheme.colors
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    SurfaceCard(borderColor = if (isActive) c.success else c.border) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text("Version $version", style = AppTheme.type.label, color = c.onSurface)
            if (isActive) AdminPill("ACTIVE", adminStatusColor("ACTIVE"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
            LinkButton("Export", { AdminShare.shareText(context, "Graph pack v$version", vm.exportPackJson(version)) })
            if (!isActive) LinkButton("Roll back to this version", { confirm = true })
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, title = { Text("Roll back to version $version?") },
            text = { Text("Version $version becomes the active graph. You can roll forward again from this list.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.rollback(version) }) { Text("Roll back") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            containerColor = c.surfaceElevated, titleContentColor = c.onSurface, textContentColor = c.mutedText,
        )
    }
}
