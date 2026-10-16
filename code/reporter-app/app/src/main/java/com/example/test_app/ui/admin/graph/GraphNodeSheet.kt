package com.example.test_app.ui.admin.graph

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.test_app.knowledgegraph.admin.graphview.GraphKinds
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.KgEdge
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.ui.admin.AdminKeyValue
import com.example.test_app.ui.admin.AdminPill
import com.example.test_app.ui.admin.adminStatusColor
import com.example.test_app.ui.components.SecondaryButton
import com.example.test_app.ui.theme.AppTheme
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Id lookups over a pack (nodes, sources and the edges by endpoint), built once per pack for the node sheet and the search chips. */
internal class PackLookup(val pack: GraphPack) {
    private val nodes: Map<String, KgNode> = pack.nodes.associateBy { it.id }
    private val sources: Map<String, KgSource> = pack.sources.associateBy { GraphKinds.sourceNodeId(it.id) }
    private val outgoing: Map<String, List<KgEdge>> = pack.edges.groupBy { it.sourceId }
    private val incoming: Map<String, List<KgEdge>> = pack.edges.groupBy { it.targetId }

    fun node(id: String): KgNode? = nodes[id]
    fun source(id: String): KgSource? = sources[id]
    fun exists(id: String): Boolean = id in nodes || id in sources
    fun outgoing(id: String): List<KgEdge> = outgoing[id].orEmpty()
    fun incoming(id: String): List<KgEdge> = incoming[id].orEmpty()

    /** The label the graph shows for [id] (title, else name, else the id; the title of a source). */
    fun label(id: String): String {
        nodes[id]?.let { n -> return n.str("title")?.takeIf { it.isNotBlank() } ?: n.name.ifBlank { id } }
        return sources[id]?.title ?: id
    }

    fun kind(id: String): String = nodes[id]?.type?.name ?: if (id in sources) GraphKinds.SOURCE else "UNKNOWN"
}

/** Compact text of a JSON value: strings without quotes, everything else as compact JSON. */
private fun jsonText(value: JsonElement): String = if (value is JsonPrimitive && value.isString) value.content else value.toString()

/**
 * Details of the selected graph node: title, id, kind, verification, approval, active flag, version, source, every property and the
 * incoming and outgoing links as tappable rows (tap selects the neighbour). Dismissing it clears the selection ([onDismiss]).
 * [onLocal] opens the local graph around the node, [onFocus] centres and zooms the canvas on it. See doc/ADMIN_DASHBOARD.md.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GraphNodeSheet(
    lookup: PackLookup,
    nodeId: String,
    inView: (String) -> Boolean,
    onDismiss: () -> Unit,
    onOpenNeighbour: (String) -> Unit,
    onLocal: () -> Unit,
    onFocus: () -> Unit,
) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    val node = lookup.node(nodeId)
    val source = lookup.source(nodeId)
    val kind = lookup.kind(nodeId)
    val out = remember(lookup, nodeId) { lookup.outgoing(nodeId) }
    val inc = remember(lookup, nodeId) { lookup.incoming(nodeId) }
    val supportedBy = remember(lookup, nodeId) { out.filter { it.relation == Relation.SUPPORTED_BY }.map { it.targetId } }
    val properties = remember(node) { node?.properties?.entries?.sortedBy { it.key }.orEmpty() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, contentColor = c.onSurface) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = s.lg, vertical = s.sm),
            verticalArrangement = Arrangement.spacedBy(s.xs),
        ) {
            item {
                Column {
                    Text(lookup.label(nodeId), style = AppTheme.type.title, color = c.primary)
                    SelectionContainer { Text(nodeId, style = AppTheme.type.code, color = c.mutedText) }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(s.xs), verticalArrangement = Arrangement.spacedBy(s.xs)) {
                    AdminPill(humanize(kind), kindColor(c.graphPalette, kind))
                    if (node != null) AdminPill("Verification: ${humanize(node.verificationStatus.name)}", adminStatusColor(node.verificationStatus.name))
                    val approval = if (node != null && node.type == NodeType.RECOMMENDATION) node.str("approvalStatus") else source?.status?.name
                    if (approval != null) AdminPill("Approval: ${humanize(approval)}", adminStatusColor(approval))
                    val active = node?.active ?: true
                    AdminPill(if (active) "Active" else "Inactive", if (active) c.success else c.error)
                    AdminPill("Version ${node?.version?.toString() ?: source?.version ?: "-"}", c.mutedText)
                    if (!inView(nodeId)) AdminPill("Not in the current view", c.warning)
                }
            }
            if (supportedBy.isNotEmpty()) {
                item { AdminKeyValue("Source", supportedBy.joinToString("\n") { "${lookup.label(it)} ($it)" }) }
            }
            if (source != null) {
                item {
                    Column {
                        AdminKeyValue("Title", source.title)
                        AdminKeyValue("Status", humanize(source.status.name))
                        AdminKeyValue("Source version", source.version)
                        source.displayLabel?.let { AdminKeyValue("Shown as", it) }
                        source.uri?.let { AdminKeyValue("Link", it) }
                        source.approvedBy?.let { AdminKeyValue("Approved by", it) }
                        source.approvedAt?.let { AdminKeyValue("Approved at", it) }
                        source.expiresAt?.let { AdminKeyValue("Expires at", it) }
                    }
                }
            }
            if (node != null) {
                item { AdminKeyValue("Name", node.name.ifBlank { "-" }) }
                if (properties.isEmpty()) item { Text("No properties.", style = AppTheme.type.caption, color = c.mutedText) }
                items(properties, key = { "p:" + it.key }) { (key, value) -> AdminKeyValue(key, jsonText(value)) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(s.xs), modifier = Modifier.padding(vertical = s.sm)) {
                    SecondaryButton("Local graph around this node", onLocal, Modifier.fillMaxWidth())
                    SecondaryButton("Focus on this node", onFocus, Modifier.fillMaxWidth())
                }
            }
            item { Text("Outgoing links (${out.size})", style = AppTheme.type.label, color = c.primary) }
            if (out.isEmpty()) item { Text("None.", style = AppTheme.type.caption, color = c.mutedText) }
            items(out, key = { "o:" + it.id }) { e -> EdgeRowItem(e, e.targetId, true, lookup, inView, onOpenNeighbour) }
            item { Text("Incoming links (${inc.size})", style = AppTheme.type.label, color = c.primary, modifier = Modifier.padding(top = s.sm)) }
            if (inc.isEmpty()) item { Text("None.", style = AppTheme.type.caption, color = c.mutedText) }
            items(inc, key = { "i:" + it.id }) { e -> EdgeRowItem(e, e.sourceId, false, lookup, inView, onOpenNeighbour) }
        }
    }
}

/** One link of the selected node: relation pill, the other end, small status text; tap selects the other end. */
@Composable
private fun EdgeRowItem(edge: KgEdge, otherId: String, isOutgoing: Boolean, lookup: PackLookup, inView: (String) -> Boolean, onOpen: (String) -> Unit) {
    val c = AppTheme.colors
    val s = AppTheme.spacing
    val label = lookup.label(otherId)
    val details = buildList {
        add(if (isOutgoing) "to" else "from")
        add(humanize(lookup.kind(otherId)))
        if (edge.approvalStatus.name != "APPROVED") add(humanize(edge.approvalStatus.name))
        if (!edge.active) add("inactive")
        edge.sourceRef?.let { add("source $it") }
        if (!inView(otherId)) add("not in the current view")
    }.joinToString(", ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.minTouch)
            .semantics { contentDescription = "${if (isOutgoing) "Outgoing" else "Incoming"} ${humanize(edge.relation.name)} link ${if (isOutgoing) "to" else "from"} $label. Tap to select it." }
            .clickable(role = Role.Button) { onOpen(otherId) }
            .padding(vertical = s.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.sm),
    ) {
        AdminPill(humanize(edge.relation.name), relationColor(c.graphPalette, edge.relation))
        Column(Modifier.weight(1f)) {
            Text(label, style = AppTheme.type.body, color = c.onSurface)
            Text(details, style = AppTheme.type.caption, color = c.mutedText)
        }
    }
}
