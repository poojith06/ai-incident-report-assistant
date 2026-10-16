package com.example.test_app.knowledgegraph.admin

import com.example.test_app.knowledgegraph.engine.EngineConfig
import com.example.test_app.knowledgegraph.engine.KnowledgeGraphEngine
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.HistoryCitation
import com.example.test_app.knowledgegraph.model.KgEdge
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.RecommendationSet
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.SourceStatus
import com.example.test_app.knowledgegraph.model.ValidatedIncident
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.knowledgegraph.repository.GraphIndex
import com.example.test_app.knowledgegraph.repository.GraphRepository
import com.example.test_app.knowledgegraph.rules.Instants
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** An admin action that is refused; [message] says why, [report] carries the validation findings when that was the reason. */
class AdminException(message: String, val report: ValidationReport? = null) : Exception(message)

/** Result of [AdminGraphService.importPack]. */
data class ImportResult(val version: Int, val report: ValidationReport)

/**
 * Spec section 18 as an API surface (the admin dashboard of the future calls these; today they are used by tests and could back a dev
 * screen). Every change creates a NEW graph version (a copy of the active pack with the change applied), validates it, stores it and makes it
 * active; older versions stay and [rollback] simply re-activates one. Nothing mutates a stored pack. See `doc/ADMIN_DASHBOARD_CONTRACT.md`.
 */
class AdminGraphService(
    private val repository: GraphRepository,
    private val config: EngineConfig = EngineConfig(),
    private val clock: () -> Instant = { Instant.now() },
) {
    /** Imports a pack JSON: decodes it, validates it (errors reject it, nothing is stored) and installs it as the next version (its own `packVersion` is replaced). */
    fun importPack(json: String): ImportResult {
        val pack = try { GraphPackCodec.decode(json) } catch (e: IllegalArgumentException) { throw AdminException(e.message ?: "invalid pack") }
        val report = GraphValidator.validate(pack)
        if (!report.ok) throw AdminException("the pack has ${report.errors.size} error(s): ${report.errors.first()}", report)
        val next = nextVersion()
        repository.save(restamp(pack, next))
        repository.activate(next)
        return ImportResult(next, report)
    }

    /** The JSON of a stored version (the active one by default). */
    fun exportPack(version: Int? = null): String {
        val pack = if (version == null) repository.activePack() else repository.load(version) ?: throw AdminException("unknown graph version $version")
        return GraphPackCodec.encode(pack)
    }

    fun upsertNode(node: KgNode): Int = change("upsert node ${node.id}") { p ->
        val stamped = node.copy(version = nextVersion())
        p.copy(nodes = if (p.nodes.any { it.id == node.id }) p.nodes.map { if (it.id == node.id) stamped else it } else p.nodes + stamped)
    }

    fun upsertEdge(edge: KgEdge): Int = change("upsert edge ${edge.id}") { p ->
        val stamped = edge.copy(version = nextVersion())
        p.copy(edges = if (p.edges.any { it.id == edge.id }) p.edges.map { if (it.id == edge.id) stamped else it } else p.edges + stamped)
    }

    /** Disables a node, edge (inactive) or recommendation (also status DISABLED). Returns the new version. */
    fun disable(id: String): Int = change("disable $id") { p ->
        val isNode = p.nodes.any { it.id == id }
        val isEdge = p.edges.any { it.id == id }
        if (!isNode && !isEdge) throw AdminException("nothing with id $id")
        p.copy(
            nodes = p.nodes.map { n ->
                if (n.id != id) n else n.copy(
                    active = false,
                    properties = if (n.type == NodeType.RECOMMENDATION) kotlinx.serialization.json.JsonObject(n.properties + ("approvalStatus" to JsonPrimitive(ApprovalStatus.DISABLED.name))) else n.properties,
                )
            },
            edges = p.edges.map { if (it.id == id) it.copy(active = false, approvalStatus = ApprovalStatus.DISABLED) else it },
        )
    }

    /** Adds or replaces an SOP source (with its expiry) and, when [recommendationId] is given, points that recommendation at it (replacing its earlier source edges). */
    fun attachSource(source: KgSource, recommendationId: String? = null): Int = change("attach source ${source.id}") { p ->
        var next = p.copy(sources = p.sources.filter { it.id != source.id } + source)
        if (recommendationId != null) {
            if (next.nodes.none { it.id == recommendationId && it.type == NodeType.RECOMMENDATION }) throw AdminException("$recommendationId is not a recommendation")
            next = next.copy(
                edges = next.edges.filterNot { it.relation == Relation.SUPPORTED_BY && it.sourceId == recommendationId } +
                    KgEdge("edge:$recommendationId:supported-by:${source.id}", recommendationId, Relation.SUPPORTED_BY, source.id, approvalStatus = ApprovalStatus.APPROVED, priority = 100),
            )
        }
        next
    }

    /**
     * Production rule (spec section 18): a recommendation becomes APPROVED only with a source, a reviewer and an approval time (set now),
     * and an optional [expiresAt]. The source must exist and be APPROVED itself (a draft source cannot make guidance reach ordinary users).
     */
    fun approveRecommendation(recommendationId: String, reviewer: String, expiresAt: String? = null): Int {
        if (reviewer.isBlank()) throw AdminException("approval needs a reviewer")
        val pack = repository.activePack()
        val rec = pack.nodes.firstOrNull { it.id == recommendationId && it.type == NodeType.RECOMMENDATION } ?: throw AdminException("$recommendationId is not a recommendation")
        val supports = pack.edges.filter { it.relation == Relation.SUPPORTED_BY && it.sourceId == recommendationId && it.active }
        val source = supports.firstNotNullOfOrNull { s -> pack.sources.firstOrNull { it.id == s.targetId } } ?: throw AdminException("cannot approve $recommendationId without a source")
        if (source.status != SourceStatus.APPROVED) throw AdminException("cannot approve $recommendationId: its source ${source.id} is ${source.status}")
        if (Instants.isExpired(source.expiresAt, clock())) throw AdminException("cannot approve $recommendationId: its source ${source.id} is expired")
        if (!expiresAt.isNullOrBlank() && Instants.parse(expiresAt) == null) throw AdminException("expiresAt is not an ISO date")
        return change("approve $recommendationId") { p ->
            p.copy(nodes = p.nodes.map { n ->
                if (n.id != rec.id) n else n.copy(
                    properties = kotlinx.serialization.json.JsonObject(
                        n.properties + mapOf(
                            "approvalStatus" to JsonPrimitive(ApprovalStatus.APPROVED.name), "approvedBy" to JsonPrimitive(reviewer.trim()),
                            "approvedAt" to JsonPrimitive(clock().toString()),
                        ) + (if (expiresAt.isNullOrBlank()) emptyMap() else mapOf("expiresAt" to JsonPrimitive(expiresAt))),
                    ),
                )
            })
        }
    }

    /** Marks a campus location node (campus, building, floor, zone, room) as verified by [verifiedBy]. */
    fun verifyLocation(nodeId: String, verifiedBy: String): Int = verify(nodeId, verifiedBy, setOf(NodeType.CAMPUS, NodeType.BUILDING, NodeType.FLOOR, NodeType.ZONE, NodeType.ROOM))

    /** Marks a response team (or a contact) as verified by [verifiedBy]. */
    fun verifyTeam(teamId: String, verifiedBy: String): Int = verify(teamId, verifiedBy, setOf(NodeType.RESPONSE_TEAM, NodeType.EMERGENCY_CONTACT))

    private fun verify(id: String, by: String, types: Set<NodeType>): Int {
        if (by.isBlank()) throw AdminException("verification needs the verifier's name")
        return change("verify $id") { p ->
            if (p.nodes.none { it.id == id && it.type in types }) throw AdminException("$id is not one of $types")
            p.copy(nodes = p.nodes.map { n ->
                if (n.id != id) n else n.copy(
                    verificationStatus = VerificationStatus.VERIFIED,
                    properties = kotlinx.serialization.json.JsonObject(n.properties + mapOf("verifiedBy" to JsonPrimitive(by.trim()), "verifiedAt" to JsonPrimitive(clock().toString()))),
                )
            })
        }
    }

    /** Validation findings of the active pack. */
    fun validate(): ValidationReport = GraphValidator.validate(repository.activePack())

    /** What an incident would get from the ACTIVE graph, for the admin: all non-disabled guidance with drafts labelled (or [Audience.REPORTER] to see what users see). Writes no audit log. */
    fun preview(incident: ValidatedIncident, audience: Audience = Audience.ADMIN_PREVIEW, history: List<HistoryCitation> = emptyList()): RecommendationSet {
        val index = GraphIndex(repository.activePack())
        return KnowledgeGraphEngine({ index }, null, config, clock).recommend(incident, audience, history)
    }

    /** Re-activates a stored version. */
    fun rollback(version: Int) {
        if (version !in repository.versions()) throw AdminException("unknown graph version $version")
        repository.activate(version)
    }

    fun versions(): List<Int> = repository.versions()

    fun activeVersion(): Int = repository.activePack().packVersion

    // ---------------------------------------------------------------------------------------------

    private fun nextVersion(): Int = (repository.versions().maxOrNull() ?: 0) + 1

    private fun restamp(pack: GraphPack, version: Int) =
        pack.copy(packVersion = version, createdAt = clock().toString())

    private fun change(what: String, transform: (GraphPack) -> GraphPack): Int {
        val active = repository.activePack()
        val next = nextVersion()
        val changed = restamp(transform(active), next)
        val report = GraphValidator.validate(changed)
        if (!report.ok) throw AdminException("$what rejected: ${report.errors.first()}", report)
        repository.save(changed)
        repository.activate(next)
        return next
    }
}
