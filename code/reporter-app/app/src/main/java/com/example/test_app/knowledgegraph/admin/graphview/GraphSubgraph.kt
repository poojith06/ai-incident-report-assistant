package com.example.test_app.knowledgegraph.admin.graphview

import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation

/** The kind of a drawn node: a [NodeType] name, or [SOURCE] for an SOP source (sources are table rows, not nodes, but the graph shows them). */
object GraphKinds {
    const val SOURCE = "SOURCE"

    /** Legend order: kinds first that matter most to an admin. */
    val ALL: List<String> = listOf(
        "INCIDENT_TYPE", "HAZARD", "CONSEQUENCE", "ACTION", "RECOMMENDATION", "RESPONSE_TEAM", "TEMPORARY_ARRANGEMENT", "LOCATION_TYPE", "ASSET_TYPE",
        "HISTORICAL_PATTERN", SOURCE, "CAMPUS", "BUILDING", "FLOOR", "ZONE", "ROOM", "ASSET", "UTILITY", "EMERGENCY_CONTACT", "EVIDENCE",
    )

    /** The graph id of an SOP source: its own id (the SUPPORTED_BY edges already end there). */
    fun sourceNodeId(sourceId: String) = sourceId
}

/** One drawn node. [degree] counts the visible edges (the UI sizes the node by it). */
data class ViewNode(
    val id: String,
    val label: String,
    val kind: String,
    val degree: Int,
    /** `APPROVED`, `DRAFT`, ... for recommendations and sources; null for the rest. */
    val approval: String?,
    val verification: String?,
    val active: Boolean,
)

/** One drawn edge (an edge of the pack). */
data class ViewEdge(val id: String, val sourceId: String, val targetId: String, val relation: Relation, val approval: ApprovalStatus, val active: Boolean)

/** The subgraph to lay out and draw. [truncated] is true when more nodes matched than [GraphViewOptions.maxNodes]. */
data class VisibleGraph(val nodes: List<ViewNode>, val edges: List<ViewEdge>, val matchedIds: Set<String>, val truncated: Boolean) {
    private val byId = nodes.associateBy { it.id }
    val nodeIds: List<String> get() = nodes.map { it.id }
    fun node(id: String): ViewNode? = byId[id]
    fun index(id: String): Int = nodes.indexOfFirst { it.id == id }

    /** Undirected index pairs of the visible edges, for [ForceLayout]. */
    fun edgeIndexPairs(): List<Pair<Int, Int>> {
        val idx = nodes.withIndex().associate { it.value.id to it.index }
        return edges.mapNotNull { e -> val a = idx[e.sourceId]; val b = idx[e.targetId]; if (a != null && b != null) a to b else null }
    }
}

/**
 * What the admin chose in the graph tab. [localDepth] with a [focusNodeId] gives the Obsidian-style local graph (undirected breadth-first
 * search up to that depth); without a focus the graph is global. [query] marks matches (the UI dims the rest) and never removes nodes.
 */
data class GraphViewOptions(
    val focusNodeId: String? = null,
    val local: Boolean = false,
    val localDepth: Int = 2,
    val hiddenKinds: Set<String> = emptySet(),
    val hiddenRelations: Set<Relation> = emptySet(),
    val onlyApprovedActive: Boolean = false,
    val showOrphans: Boolean = true,
    val query: String = "",
    val maxNodes: Int = 400,
)

/**
 * Builds the visible subgraph of a pack (pure). Order of the steps: nodes (and SOP sources) -> kind, active and approval filters -> relation
 * filter on edges -> local depth around the focus -> orphan filter -> search matches -> cap at `maxNodes` (highest degree, then matches and
 * the focus first). Edges only connect visible nodes.
 */
object GraphSubgraph {
    fun build(pack: GraphPack, o: GraphViewOptions, highlightNodeIds: Set<String> = emptySet()): VisibleGraph {
        val nodes = LinkedHashMap<String, Pair<String, KgNode?>>() // id -> (kind, node)
        for (n in pack.nodes) nodes[n.id] = n.type.name to n
        for (s in pack.sources) nodes.putIfAbsent(GraphKinds.sourceNodeId(s.id), GraphKinds.SOURCE to null)
        val sourceById = pack.sources.associateBy { GraphKinds.sourceNodeId(it.id) }

        fun approval(id: String, node: KgNode?): String? =
            if (node?.type == NodeType.RECOMMENDATION) node.str("approvalStatus") else sourceById[id]?.status?.name
        fun approvedActive(id: String, kind: String, node: KgNode?): Boolean = when {
            node != null && !node.active -> false
            kind == "RECOMMENDATION" -> approval(id, node) == ApprovalStatus.APPROVED.name
            kind == GraphKinds.SOURCE -> approval(id, node) == "APPROVED"
            else -> true
        }

        var keep = nodes.filter { (id, v) ->
            v.first !in o.hiddenKinds && (!o.onlyApprovedActive || approvedActive(id, v.first, v.second))
        }.keys.toMutableSet()
        // always keep highlighted nodes visible (an incident path must not lose a node to a kind filter)
        keep.addAll(highlightNodeIds.filter { it in nodes })

        var edges = pack.edges.filter { e ->
            e.relation !in o.hiddenRelations && e.sourceId in keep && e.targetId in keep && (!o.onlyApprovedActive || (e.active && e.approvalStatus == ApprovalStatus.APPROVED))
        }

        if (o.local && o.focusNodeId != null && o.focusNodeId in keep) {
            val adj = HashMap<String, MutableList<String>>()
            for (e in edges) { adj.getOrPut(e.sourceId) { mutableListOf() } += e.targetId; adj.getOrPut(e.targetId) { mutableListOf() } += e.sourceId }
            val reach = HashSet<String>().apply { add(o.focusNodeId) }
            var frontier = listOf(o.focusNodeId)
            repeat(o.localDepth.coerceIn(1, 6)) {
                val next = mutableListOf<String>()
                for (id in frontier) for (m in adj[id].orEmpty()) if (reach.add(m)) next += m
                frontier = next
            }
            keep = reach
            edges = edges.filter { it.sourceId in keep && it.targetId in keep }
        }

        if (!o.showOrphans) {
            val linked = HashSet<String>().apply { edges.forEach { add(it.sourceId); add(it.targetId) } }
            keep = keep.filter { it in linked || it == o.focusNodeId }.toMutableSet()
        }

        val degree = HashMap<String, Int>()
        for (e in edges) { degree.merge(e.sourceId, 1, Int::plus); degree.merge(e.targetId, 1, Int::plus) }
        val q = o.query.trim().lowercase()
        val matched = if (q.isEmpty()) emptySet() else keep.filter { id -> matches(id, nodes.getValue(id).second, q) }.toSet()

        var truncated = false
        var ordered = keep.toList()
        if (ordered.size > o.maxNodes) {
            truncated = true
            val must = matched + highlightNodeIds + listOfNotNull(o.focusNodeId)
            ordered = ordered.sortedWith(compareByDescending<String> { it in must }.thenByDescending { degree[it] ?: 0 }.thenBy { it }).take(o.maxNodes)
            val set = ordered.toSet()
            edges = edges.filter { it.sourceId in set && it.targetId in set }
            degree.clear(); for (e in edges) { degree.merge(e.sourceId, 1, Int::plus); degree.merge(e.targetId, 1, Int::plus) }
        }
        val viewNodes = ordered.sorted().map { id ->
            val (kind, node) = nodes.getValue(id)
            val src = sourceById[id]
            ViewNode(
                id = id, label = label(id, node, src?.title), kind = kind, degree = degree[id] ?: 0, approval = approval(id, node),
                verification = node?.verificationStatus?.name, active = node?.active ?: true,
            )
        }
        return VisibleGraph(
            viewNodes,
            edges.map { ViewEdge(it.id, it.sourceId, it.targetId, it.relation, it.approvalStatus, it.active) }
                .filter { e -> viewNodes.any { it.id == e.sourceId } && viewNodes.any { it.id == e.targetId } },
            matched.filter { m -> viewNodes.any { it.id == m } }.toSet(), truncated,
        )
    }

    private fun label(id: String, node: KgNode?, sourceTitle: String?): String = when {
        node != null -> node.str("title")?.takeIf { it.isNotBlank() } ?: node.name.ifBlank { id }
        sourceTitle != null -> sourceTitle
        else -> id
    }

    private fun matches(id: String, node: KgNode?, q: String): Boolean =
        id.lowercase().contains(q) || (node?.name?.lowercase()?.contains(q) == true) || (node?.str("code")?.lowercase()?.contains(q) == true) ||
            (node?.str("title")?.lowercase()?.contains(q) == true)

    /** The neighbours of [nodeId] in the visible graph: (edge, other node), outgoing first. Used by the node sheet and the "selected neighbourhood" highlight. */
    fun neighbours(graph: VisibleGraph, nodeId: String): List<Pair<ViewEdge, ViewNode>> =
        graph.edges.filter { it.sourceId == nodeId }.mapNotNull { e -> graph.node(e.targetId)?.let { e to it } } +
            graph.edges.filter { it.targetId == nodeId }.mapNotNull { e -> graph.node(e.sourceId)?.let { e to it } }
}
