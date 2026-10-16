package com.example.test_app.ui.admin

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.example.test_app.admin.AdminUiState
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.admin.EdgeDraft
import com.example.test_app.admin.EditorForms
import com.example.test_app.admin.EditorRow
import com.example.test_app.admin.EditorRows
import com.example.test_app.admin.EditorSection
import com.example.test_app.admin.NodeDraft
import com.example.test_app.admin.SourceDraft
import com.example.test_app.knowledgegraph.model.KgEdge
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.ui.components.LinkButton
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.components.SurfaceCard
import com.example.test_app.ui.theme.AppTheme
import java.time.Instant

/** What the form dialog edits; null [node], [edge] or [source] means "new". */
private sealed interface DataDialog {
    data class Node(val node: KgNode?, val preset: NodeType? = null, val section: EditorSection = EditorSection.NODES) : DataDialog
    data class Edge(val edge: KgEdge?) : DataDialog
    data class Source(val source: KgSource?, val recommendationId: String? = null) : DataDialog
    data class Recommendation(val node: KgNode) : DataDialog
}

private enum class DataMode(val label: String) { LISTS(""), PREVIEW("Preview"), IMPORT("Import / export"), VERSIONS("Versions") }

private val LOCATION_TYPES = setOf(NodeType.CAMPUS, NodeType.BUILDING, NodeType.FLOOR, NodeType.ZONE, NodeType.ROOM)

/**
 * The Graph data tab: a knowledge editor for the active graph pack. Lists of nodes, edges, recommendations, sources, teams and contacts
 * with edit forms; a sample-incident preview; import and export; and the graph versions with rollback. Every change goes through the
 * view model and creates a new graph version. See `doc/ADMIN_DASHBOARD.md`.
 */
@Composable
fun AdminDataTab(state: AdminUiState, vm: AdminViewModel) {
    val snapshot = state.snapshot ?: return
    val pack = snapshot.pack
    val s = AppTheme.spacing
    val c = AppTheme.colors
    var modeName by rememberSaveable { mutableStateOf(DataMode.LISTS.name) }
    val mode = DataMode.valueOf(modeName)
    var dialog by remember { mutableStateOf<DataDialog?>(null) }
    var confirmDisable by remember { mutableStateOf<String?>(null) }
    val section = state.editor.section
    val rows = remember(pack, section, state.editor.query) { EditorRows.of(pack, section, state.editor.query) }
    // a saved change produces a new graph version: close the form then
    LaunchedEffect(pack.packVersion) { dialog = null }

    val tabs: List<String> = EditorSection.entries.map { it.label } + DataMode.entries.drop(1).map { it.label }
    val selectedTab = if (mode == DataMode.LISTS) section.label else mode.label

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(s.xs)) {
        AdminSegmentRow(tabs, selectedTab, { it }) { label ->
            val sec = EditorSection.entries.firstOrNull { it.label == label }
            if (sec != null) { modeName = DataMode.LISTS.name; if (sec != section) vm.setEditorSection(sec) }
            else modeName = DataMode.entries.first { it.label == label }.name
        }
        if (mode == DataMode.LISTS) AdminSearchField(state.editor.query, vm::setEditorQuery, "Search ${section.label.lowercase()}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s.sm)) {
            SecondaryButton("Validate graph", vm::validate, Modifier.weight(1f))
            if (mode == DataMode.LISTS) SecondaryButton("New ${singular(section)}", { dialog = newDialog(section) }, Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(s.sm)) {
            state.editor.validation?.let { v -> item(key = "validation") { ValidationCard(v) } }
            when (mode) {
                DataMode.LISTS -> {
                    item(key = "count") { Text("${rows.size} ${section.label.lowercase()}", style = AppTheme.type.label, color = c.mutedText) }
                    if (section == EditorSection.CONTACTS) item(key = "contact-rule") {
                        Text("A contact is only shown to users when VERIFIED, enabled and not expired.", style = AppTheme.type.caption, color = c.mutedText)
                    }
                    if (rows.isEmpty()) item(key = "empty") {
                        Text(if (section == EditorSection.CONTACTS && state.editor.query.isBlank()) "No contacts yet. The demo seed has none." else "Nothing matches.", style = AppTheme.type.body, color = c.mutedText)
                    }
                    items(rows, key = { "${section.name}:${it.id}" }) { row ->
                        EditorRowCard(row) { dialog = openDialog(pack, section, row.id) }
                    }
                }
                DataMode.PREVIEW -> previewItems(state, vm)
                DataMode.IMPORT -> importExportItems(state, vm)
                DataMode.VERSIONS -> versionItems(state, vm)
            }
        }
    }

    dialog?.let { d ->
        val close = { dialog = null }
        when (d) {
            is DataDialog.Node -> AdminFormDialog(if (d.node == null) "New ${singular(d.section)}" else "Edit ${d.node.id}", close) {
                val initial = remember(d) {
                    d.node?.let { EditorForms.draftOf(it) } ?: NodeDraft(type = (d.preset ?: NodeType.ACTION).name, propertiesJson = "{}")
                }
                NodeForm(initial, d.node != null, pack, state.editor.validation, vm::upsertNode, typeLocked = d.preset != null && d.node == null)
                d.node?.let { n ->
                    if (n.type == NodeType.RESPONSE_TEAM) VerifyForm("Verify team") { by -> vm.verifyTeam(n.id, by) }
                    if (n.type in LOCATION_TYPES) VerifyForm("Verify location") { by -> vm.verifyLocation(n.id, by) }
                    if (n.active) SecondaryButton("Disable", { confirmDisable = n.id })
                }
            }
            is DataDialog.Edge -> AdminFormDialog(if (d.edge == null) "New edge" else "Edit ${d.edge.id}", close) {
                EdgeForm(remember(d) { d.edge?.let { EditorForms.draftOf(it) } ?: EdgeDraft() }, d.edge != null, pack, state.editor.validation, vm::upsertEdge)
                d.edge?.let { e -> if (e.active) SecondaryButton("Disable", { confirmDisable = e.id }) }
            }
            is DataDialog.Source -> AdminFormDialog(
                if (d.recommendationId != null) "Attach source to ${d.recommendationId}" else if (d.source == null) "New source" else "Edit ${d.source.id}", close,
            ) {
                SourceForm(remember(d) { d.source?.let { EditorForms.draftOf(it) } ?: SourceDraft() }, d.source != null, state.editor.validation, if (d.recommendationId != null) "Attach" else "Save") { src ->
                    vm.attachSource(src, d.recommendationId)
                }
            }
            is DataDialog.Recommendation -> AdminFormDialog(d.node.id, close) {
                RecommendationDetail(d.node, state, vm, onEdit = { dialog = DataDialog.Node(d.node, section = EditorSection.RECOMMENDATIONS) }, onAttach = { dialog = DataDialog.Source(null, d.node.id) }, onDisable = { confirmDisable = d.node.id })
            }
        }
    }
    confirmDisable?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDisable = null }, title = { Text("Disable $id?") },
            text = { Text("It stops being used by the engine. This creates a new graph version; roll back in Versions to undo it.") },
            confirmButton = { TextButton(onClick = { confirmDisable = null; vm.disable(id) }) { Text("Disable", color = c.error) } },
            dismissButton = { TextButton(onClick = { confirmDisable = null }) { Text("Cancel") } },
            containerColor = c.surfaceElevated, titleContentColor = c.onSurface, textContentColor = c.mutedText,
        )
    }
}

private fun singular(s: EditorSection) = when (s) {
    EditorSection.NODES -> "node"; EditorSection.EDGES -> "edge"; EditorSection.RECOMMENDATIONS -> "recommendation"
    EditorSection.SOURCES -> "source"; EditorSection.TEAMS -> "team"; EditorSection.CONTACTS -> "contact"
}

private fun newDialog(s: EditorSection): DataDialog = when (s) {
    EditorSection.NODES -> DataDialog.Node(null)
    EditorSection.EDGES -> DataDialog.Edge(null)
    EditorSection.RECOMMENDATIONS -> DataDialog.Node(null, NodeType.RECOMMENDATION, s)
    EditorSection.SOURCES -> DataDialog.Source(null)
    EditorSection.TEAMS -> DataDialog.Node(null, NodeType.RESPONSE_TEAM, s)
    EditorSection.CONTACTS -> DataDialog.Node(null, NodeType.EMERGENCY_CONTACT, s)
}

private fun openDialog(pack: com.example.test_app.knowledgegraph.model.GraphPack, s: EditorSection, id: String): DataDialog? = when (s) {
    EditorSection.EDGES -> pack.edges.firstOrNull { it.id == id }?.let { DataDialog.Edge(it) }
    EditorSection.SOURCES -> pack.sources.firstOrNull { it.id == id }?.let { DataDialog.Source(it) }
    EditorSection.RECOMMENDATIONS -> pack.nodes.firstOrNull { it.id == id }?.let { DataDialog.Recommendation(it) }
    else -> pack.nodes.firstOrNull { it.id == id }?.let { DataDialog.Node(it, section = s) }
}

@Composable
private fun EditorRowCard(row: EditorRow, onClick: () -> Unit) {
    val c = AppTheme.colors
    SurfaceCard(Modifier.clickable(role = Role.Button, onClickLabel = "Open ${row.id}", onClick = onClick).heightIn(min = AppTheme.sizes.minTouch)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            row.status?.let { AdminPill(it, adminStatusColor(it)) }
            if (!row.active) AdminPill("inactive", c.error)
        }
        Text(row.title, style = AppTheme.type.label, color = c.onSurface)
        Text(row.id, style = AppTheme.type.code, color = c.mutedText)
        if (row.subtitle.isNotBlank()) Text(row.subtitle, style = AppTheme.type.caption, color = c.mutedText)
    }
}

@Composable
private fun RecommendationDetail(node: KgNode, state: AdminUiState, vm: AdminViewModel, onEdit: () -> Unit, onAttach: () -> Unit, onDisable: () -> Unit) {
    val c = AppTheme.colors
    AdminKeyValue("Name", node.name)
    AdminKeyValue("Verification", node.verificationStatus.name)
    AdminKeyValue("Active", if (node.active) "yes" else "no (disabled)")
    node.properties.forEach { (k, v) -> AdminKeyValue(k, v.toString().trim('"')) }
    if (node.verificationStatus != VerificationStatus.VERIFIED) Text("Not verified: only the admin preview shows it.", style = AppTheme.type.caption, color = c.warning)
    Text("Approve", style = AppTheme.type.title, color = c.onSurface)
    ApprovalForm(Instant.now().toString(), state.editor.validation) { reviewer, expires -> vm.approve(node.id, reviewer, expires) }
    SecondaryButton("Attach source", onAttach)
    SecondaryButton("Edit as node", onEdit)
    if (node.active) SecondaryButton("Disable", onDisable)
}
