package com.example.test_app.knowledgegraph

import com.example.test_app.knowledgegraph.engine.EngineConfig
import com.example.test_app.knowledgegraph.engine.KnowledgeGraphEngine
import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.EdgeConditions
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.HistoryCitation
import com.example.test_app.knowledgegraph.model.IncidentStatus
import com.example.test_app.knowledgegraph.model.KgEdge
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.KgSeverity
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.RecommendationSet
import com.example.test_app.knowledgegraph.model.SourceStatus
import com.example.test_app.knowledgegraph.model.ValidatedIncident
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.knowledgegraph.repository.GraphIndex
import com.example.test_app.knowledgegraph.repository.InMemoryAuditStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

/** The fixed "now" of the knowledge graph tests. */
val KG_NOW: Instant = Instant.parse("2026-10-11T06:00:00Z")
const val OK_AT = "2026-10-01T00:00:00+05:30"

fun jp(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { jp(it) })
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to jp(it.value) })
    else -> error("bad json value $v")
}

fun props(vararg p: Pair<String, Any?>) = JsonObject(p.associate { it.first to jp(it.second) })

/** Builds small graphs for the engine tests (the seed pack has its own tests). Every helper returns the builder. */
class PackBuilder(var version: Int = 1) {
    val nodes = mutableListOf<KgNode>()
    val edges = mutableListOf<KgEdge>()
    val sources = mutableListOf<KgSource>(
        KgSource("sop:ok", "Approved SOP", approvedBy = "Reviewer", approvedAt = OK_AT, status = SourceStatus.APPROVED, displayLabel = "Test guidance"),
    )
    private var edgeNo = 0

    fun node(id: String, type: NodeType, name: String = id, p: JsonObject = JsonObject(emptyMap()), verified: VerificationStatus = VerificationStatus.NEEDS_ADMIN_VERIFICATION) = apply {
        nodes += KgNode(id, type, name, p, verified)
    }

    fun incidentType(code: String, category: String = "FIRE") = node("incident-type:$code", NodeType.INCIDENT_TYPE, code, props("code" to code, "category" to category))
    fun locationType(code: String) = node("location-type:$code", NodeType.LOCATION_TYPE, code, props("code" to code))
    fun assetType(code: String) = node("asset-type:$code", NodeType.ASSET_TYPE, code, props("code" to code))
    fun hazard(code: String) = node("hazard:$code", NodeType.HAZARD, code, props("code" to code, "activeRisk" to true))
    fun team(slug: String, role: String = slug.uppercase(), verified: VerificationStatus = VerificationStatus.NEEDS_ADMIN_VERIFICATION) =
        node("team:$slug", NodeType.RESPONSE_TEAM, slug, props("role" to role, "label" to slug.replace('-', ' ')), verified)

    fun consequence(slug: String, text: String = "Something could follow.", type: String = "SAFETY", observedWhenEffect: String? = null) =
        node("consequence:$slug", NodeType.CONSEQUENCE, slug, props("consequenceType" to type, "text" to text, "observedWhenEffect" to observedWhenEffect))

    fun action(
        slug: String, text: String = "Ask authorized staff to inspect the area.", phase: String = "IMMEDIATE", userCan: Boolean = false, auth: Boolean = true,
        key: String = slug.uppercase().replace('-', '_'), orderClass: String = "HAZARD_ISOLATION", team: String? = "team:ops", medicalClass: String? = null,
        forbidden: List<String> = emptyList(), humanApproval: Boolean = false,
    ) = apply {
        node(
            "action:$slug", NodeType.ACTION, slug,
            props(
                "title" to slug, "phase" to phase, "userCanPerform" to userCan, "requiresAuthorization" to auth, "priority" to 50, "canonicalText" to text, "canonicalKey" to key,
                "orderClass" to orderClass, "medicalClass" to medicalClass, "forbiddenForReporter" to forbidden, "requiresHumanApproval" to humanApproval,
            ),
        )
        if (team != null) edge("action:$slug", Relation.OWNED_BY, team)
    }

    /** The recommendation record of `action:<slug>` with its support edge to [source]. */
    fun rec(
        slug: String, status: ApprovalStatus = ApprovalStatus.APPROVED, source: String? = "sop:ok", approvedBy: String? = "Reviewer", approvedAt: String? = OK_AT,
        expiresAt: String? = null, supportStatus: ApprovalStatus = ApprovalStatus.APPROVED,
    ) = apply {
        node(
            "rec:$slug", NodeType.RECOMMENDATION, slug,
            props("actionId" to "action:$slug", "approvalStatus" to status.name, "approvedBy" to approvedBy, "approvedAt" to approvedAt, "expiresAt" to expiresAt),
        )
        if (source != null) edges += KgEdge("edge:rec-$slug-source", "rec:$slug", Relation.SUPPORTED_BY, source, approvalStatus = supportStatus, priority = 100)
    }

    fun edge(from: String, rel: Relation, to: String, cond: EdgeConditions = EdgeConditions(), priority: Int = 50, status: ApprovalStatus = ApprovalStatus.APPROVED, sourceRef: String? = null, active: Boolean = true) = apply {
        edges += KgEdge("edge:t${edgeNo++}:${from.substringAfterLast(':')}-${to.substringAfterLast(':')}", from, rel, to, cond, priority, sourceRef, status, 1, active)
    }

    /** type --TRIGGERS_ACTION--> action, plus a default recommendation when none exists yet. */
    fun trigger(typeCode: String, slug: String, cond: EdgeConditions = EdgeConditions(), priority: Int = 50, status: ApprovalStatus = ApprovalStatus.APPROVED) =
        edge("incident-type:$typeCode", Relation.TRIGGERS_ACTION, "action:$slug", cond, priority, status)

    fun source(s: KgSource) = apply { sources += s }

    fun build() = GraphPack(packVersion = version, name = "test", nodes = nodes.toList(), edges = edges.toList(), sources = sources.toList())

    companion object {
        /** A one-type graph: `FIRE`, `team:ops`, one APPROVED recommendation `rec:<slug>` per given slug triggered unconditionally. */
        fun basic(vararg slugs: String): PackBuilder {
            val b = PackBuilder().incidentType("FIRE").team("ops")
            for (s in slugs) { b.action(s).rec(s).trigger("FIRE", s) }
            return b
        }
    }
}

fun incident(
    type: String? = "FIRE", severity: KgSeverity? = KgSeverity.HIGH, status: IncidentStatus = IncidentStatus.ACTIVE, locationType: String? = "COMPUTER_LAB",
    assets: List<String> = emptyList(), hazards: List<String> = emptyList(), injury: Boolean? = false, confirmed: Boolean = true, evidence: Double = 0.9,
    id: String = "INC-T-1", locationId: String? = null, effects: List<String> = emptyList(), people: Int? = null,
) = ValidatedIncident(
    incidentId = id, type = type, severity = severity, status = status, locationId = locationId, locationType = locationType, affectedAssets = assets, activeHazards = hazards,
    peopleAffected = people, operationalEffects = effects, evidenceConfidence = evidence, timestamp = "2026-10-11T10:00:00+05:30", userConfirmed = confirmed, injury = injury,
    locationText = "KRB computer lab",
)

fun engineFor(pack: GraphPack, audits: InMemoryAuditStore? = null, config: EngineConfig = EngineConfig()): KnowledgeGraphEngine {
    val index = GraphIndex(pack)
    return KnowledgeGraphEngine({ index }, audits, config) { KG_NOW }
}

fun PackBuilder.run(i: ValidatedIncident = incident(), audience: Audience = Audience.REPORTER, history: List<HistoryCitation> = emptyList(), config: EngineConfig = EngineConfig()): RecommendationSet =
    engineFor(build(), null, config).recommend(i, audience, history)

fun RecommendationSet.ids() = allRecommendations.map { it.id }

fun RecommendationSet.whyNot(id: String): String? = suppressed.firstOrNull { it.candidateId == id || it.candidateId == id.replace("rec:", "action:") }?.reason

/** The directory with the seed pack (works from the module or the project directory). */
fun kgAssetsDir(): File = listOf("src/main/assets/kg", "app/src/main/assets/kg").map { File(it) }.first { it.isDirectory }

fun kgResource(name: String): File = listOf("src/test/resources/kg/$name", "app/src/test/resources/kg/$name").map { File(it) }.first { it.isFile }
