package com.example.test_app.knowledgegraph.repository

import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.KgEdge
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation

/**
 * Read-only in-memory index of one [GraphPack] (the active graph is kept in memory, ADR-055). Built once per pack; lookups are by id,
 * by `code` property (incident types, location types, asset types, hazards), by edge source/target and by relation, mirroring the
 * indexes the SQL schema of spec section 8 would have. Inactive nodes and edges stay in the index: filtering is the engine's job so
 * that every suppression can be recorded with its reason.
 */
class GraphIndex(val pack: GraphPack) {
    val nodes: Map<String, KgNode> = pack.nodes.associateBy { it.id }
    val sources: Map<String, KgSource> = pack.sources.associateBy { it.id }
    val edges: Map<String, KgEdge> = pack.edges.associateBy { it.id }
    val version: Int get() = pack.packVersion

    private val byCode: Map<Pair<NodeType, String>, KgNode> = pack.nodes.mapNotNull { n ->
        n.str("code")?.let { (n.type to it.uppercase()) to n }
    }.toMap()
    private val from: Map<String, List<KgEdge>> = pack.edges.groupBy { it.sourceId }
    private val to: Map<String, List<KgEdge>> = pack.edges.groupBy { it.targetId }
    private val recByAction: Map<String, KgNode> = pack.nodes.filter { it.type == NodeType.RECOMMENDATION }
        .mapNotNull { r -> r.str("actionId")?.let { it to r } }.toMap()

    fun node(id: String?): KgNode? = id?.let { nodes[it] }

    /** A node of [type] by its `code` property (case-insensitive), or null. */
    fun byCode(type: NodeType, code: String?): KgNode? = code?.let { byCode[type to it.uppercase()] }

    fun nodesOfType(type: NodeType): List<KgNode> = pack.nodes.filter { it.type == type }

    fun edgesFrom(id: String, relation: Relation): List<KgEdge> = from[id].orEmpty().filter { it.relation == relation }

    fun edgesTo(id: String, relation: Relation): List<KgEdge> = to[id].orEmpty().filter { it.relation == relation }

    /** The RECOMMENDATION node that wraps the ACTION [actionId], or null (a sourceless / unreviewed action). */
    fun recommendationFor(actionId: String): KgNode? = recByAction[actionId]

    /** The SOP source a recommendation relies on through its `SUPPORTED_BY` edge(s): edge plus source (the source may be missing = dangling). */
    fun supportEdges(recId: String): List<KgEdge> = edgesFrom(recId, Relation.SUPPORTED_BY)

    /** The owning team of an action (first `OWNED_BY` edge to an existing team node), or null. */
    fun ownerOf(actionOrArrangementId: String): KgNode? =
        edgesFrom(actionOrArrangementId, Relation.OWNED_BY).firstNotNullOfOrNull { nodes[it.targetId]?.takeIf { n -> n.type == NodeType.RESPONSE_TEAM } }

    /** Prerequisite actions of [actionId] (targets of its `DEPENDS_ON` edges). */
    fun prerequisitesOf(actionId: String): List<String> = edgesFrom(actionId, Relation.DEPENDS_ON).map { it.targetId }

    fun contactsOfTeam(teamId: String): List<KgNode> =
        pack.nodes.filter { it.type == NodeType.EMERGENCY_CONTACT && it.str("teamId") == teamId }
}
