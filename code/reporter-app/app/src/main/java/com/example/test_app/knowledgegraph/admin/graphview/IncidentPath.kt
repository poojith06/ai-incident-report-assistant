package com.example.test_app.knowledgegraph.admin.graphview

import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.RecommendationLog
import com.example.test_app.knowledgegraph.model.RecommendationSet
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.Relation.SUPPORTED_BY
import com.example.test_app.knowledgegraph.model.ValidatedIncident

/** What the engine touched for one incident, as graph node and edge ids: [nodeIds] and [edgeIds] were traversed and shown; the `suppressed` ones were candidates that a filter removed. */
data class PathHighlight(
    val nodeIds: Set<String> = emptySet(),
    val edgeIds: Set<String> = emptySet(),
    val suppressedNodeIds: Set<String> = emptySet(),
    val suppressedEdgeIds: Set<String> = emptySet(),
    /** The incident type node (the root of the path), when known. */
    val rootNodeId: String? = null,
) {
    val isEmpty: Boolean get() = nodeIds.isEmpty() && edgeIds.isEmpty() && suppressedNodeIds.isEmpty()
}

/** The facts [IncidentPath] needs, whether they come from an audit log or from a live preview. */
data class PathInput(
    val incidentTypeCode: String?,
    val locationTypeCode: String?,
    val assetCodes: List<String>,
    /** Provenance strings of everything shown (recommendations, consequences, arrangements). */
    val provenance: List<String>,
    val shownIds: List<String>,
    val sourceIds: List<String>,
    val teamIds: List<String>,
    val suppressedIds: List<String>,
)

/**
 * Derives the highlight of an incident from the engine's own provenance (ADR-058): the audit log and the export already carry, for every
 * shown item, the traversed path as text (`incident-type:x -TRIGGERS_ACTION-> action:y [edge:id]`); this class parses it, matches every
 * hop to a pack edge by (source, relation, target) and adds the recommendation record of each action, its SUPPORTED_BY edge and source, the
 * owning team, the incident, location and asset type nodes. Suppressed candidates (ids from the audit) become warning-coloured nodes or edges.
 * No schema change was needed, so old audit logs and exports work.
 */
object IncidentPath {
    private val HOP = Regex("\\s-([A-Z_]+)->\\s")

    /** `incident-type:x -R-> hazard:y -R2-> consequence:z [edge:..]` -> ids and hops. Unknown relation names and blanks are skipped. */
    fun parseProvenance(text: String): Pair<List<String>, List<Triple<String, Relation, String>>> {
        val core = text.substringBefore(" [edge:").trim()
        if (core.isEmpty()) return emptyList<String>() to emptyList()
        val ids = mutableListOf<String>()
        val rels = mutableListOf<Relation?>()
        var last = 0
        for (m in HOP.findAll(core)) {
            ids += core.substring(last, m.range.first).trim()
            rels += Relation.entries.firstOrNull { it.name == m.groupValues[1] }
            last = m.range.last + 1
        }
        ids += core.substring(last).trim()
        val hops = rels.indices.mapNotNull { i -> rels[i]?.let { Triple(ids[i], it, ids[i + 1]) } }
        return ids.filter { it.isNotEmpty() } to hops
    }

    fun derive(pack: GraphPack, input: PathInput): PathHighlight {
        val nodeById = pack.nodes.associateBy { it.id }
        val sourceNodeIds = pack.sources.associate { it.id to GraphKinds.sourceNodeId(it.id) }
        val nodes = LinkedHashSet<String>()
        val edges = LinkedHashSet<String>()
        fun byCode(type: NodeType, code: String?) = code?.let { c -> pack.nodes.firstOrNull { it.type == type && it.str("code").equals(c, true) }?.id }
        val root = byCode(NodeType.INCIDENT_TYPE, input.incidentTypeCode)
        root?.let { nodes += it }
        byCode(NodeType.LOCATION_TYPE, input.locationTypeCode)?.let { nodes += it }
        input.assetCodes.forEach { a -> byCode(NodeType.ASSET_TYPE, a)?.let { nodes += it } }
        for (p in input.provenance) {
            val (ids, hops) = parseProvenance(p)
            ids.filter { it in nodeById }.forEach { nodes += it }
            for ((a, rel, b) in hops) pack.edges.filter { it.sourceId == a && it.relation == rel && it.targetId == b }.forEach { edges += it.id }
            Regex("\\[(edge:[^\\]]+)]\\s*$").find(p)?.groupValues?.get(1)?.let { id -> if (pack.edges.any { it.id == id }) edges += id }
        }
        for (id in input.shownIds) if (id in nodeById) nodes += id
        // each shown action/recommendation: the record, its action, support edge, source and owner
        val actionIds = nodes.filter { nodeById[it]?.type == NodeType.ACTION }.toSet() + nodes.filter { nodeById[it]?.type == NodeType.RECOMMENDATION }.mapNotNull { nodeById[it]?.str("actionId") }
        for (a in actionIds) {
            if (a in nodeById) nodes += a
            pack.nodes.firstOrNull { it.type == NodeType.RECOMMENDATION && it.str("actionId") == a }?.let { rec ->
                nodes += rec.id
                pack.edges.filter { it.sourceId == rec.id && it.relation == SUPPORTED_BY && it.targetId in input.sourceIds }.forEach { e -> edges += e.id; sourceNodeIds[e.targetId]?.let { nodes += it } }
            }
            pack.edges.filter { it.sourceId == a && it.relation == Relation.OWNED_BY && (input.teamIds.isEmpty() || it.targetId in input.teamIds) }.forEach { e -> edges += e.id; nodes += e.targetId }
        }
        for (s in input.sourceIds) sourceNodeIds[s]?.let { nodes += it }
        for (t in input.teamIds) if (t in nodeById) nodes += t
        val supNodes = LinkedHashSet<String>()
        val supEdges = LinkedHashSet<String>()
        for (id in input.suppressedIds) {
            when {
                id.startsWith("edge:") -> if (pack.edges.any { it.id == id }) supEdges += id
                id in nodeById -> if (id !in nodes) supNodes += id
            }
        }
        return PathHighlight(nodes, edges, supNodes.filter { it !in nodes }.toSet(), supEdges.filter { it !in edges }.toSet(), root)
    }

    fun fromAudit(pack: GraphPack, log: RecommendationLog): PathHighlight = derive(
        pack,
        PathInput(
            incidentTypeCode = log.inputFacts.type, locationTypeCode = log.inputFacts.locationType, assetCodes = log.inputFacts.affectedAssets,
            provenance = log.shown.flatMap { it.provenance }, shownIds = log.shown.map { it.id },
            sourceIds = log.shown.mapNotNull { it.sourceId }.distinct(), teamIds = log.shown.filter { it.kind == "team" }.map { it.id },
            suppressedIds = log.suppressed.map { it.candidateId },
        ),
    )

    fun fromPreview(pack: GraphPack, incident: ValidatedIncident, set: RecommendationSet): PathHighlight = derive(
        pack,
        PathInput(
            incidentTypeCode = incident.type, locationTypeCode = incident.locationType, assetCodes = incident.affectedAssets,
            provenance = set.allRecommendations.flatMap { it.provenance } + set.consequences.flatMap { it.provenance } + set.temporaryArrangements.flatMap { it.provenance },
            shownIds = set.allRecommendations.map { it.id } + set.consequences.map { it.id } + set.temporaryArrangements.map { it.id },
            sourceIds = set.allRecommendations.map { it.sourceId } + set.temporaryArrangements.mapNotNull { it.sourceId },
            teamIds = set.responsibleTeams.map { it.id }, suppressedIds = set.suppressed.map { it.candidateId },
        ),
    )
}
