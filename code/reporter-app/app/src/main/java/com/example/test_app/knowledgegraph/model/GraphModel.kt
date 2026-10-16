package com.example.test_app.knowledgegraph.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Table-shaped graph model (spec section 8 without Room, ADR-055): kg_node, kg_edge, kg_source rows kept as plain data classes and
 * stored as versioned JSON packs. Everything here is pure Kotlin (no android imports). See doc/KNOWLEDGE_GRAPH.md.
 */

/** Spec section 6: who may rely on a node. */
enum class VerificationStatus { VERIFIED, NEEDS_ADMIN_VERIFICATION, REJECTED }

/** Spec section 6: life cycle of a recommendation (and of an edge). Only APPROVED reaches ordinary users. */
enum class ApprovalStatus { DRAFT, REVIEWED, APPROVED, EXPIRED, DISABLED }

/** Spec section 6. */
enum class Phase { IMMEDIATE, OPERATIONAL, RECOVERY, PREVENTION }

/** Spec section 6. */
enum class IncidentStatus { ACTIVE, CONTAINED, RESOLVED, UNKNOWN }

/** Incident severity as the graph sees it (the app's `Low/Medium/High/Critical`). */
enum class KgSeverity { LOW, MEDIUM, HIGH, CRITICAL;
    companion object {
        /** "High", "HIGH" or "high" -> [HIGH]; unknown or null -> null (never guessed). */
        fun parse(text: String?): KgSeverity? = entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) }
    }
}

/** Status of an SOP source (spec section 6 `SopSource.status`). */
enum class SourceStatus { APPROVED, NEEDS_ADMIN_VERIFICATION, REJECTED, EXPIRED }

/** Node types: spec section 6 plus `LOCATION_TYPE`, `ASSET_TYPE` and `RECOMMENDATION` (the units of the whitelisted paths of section 10). */
enum class NodeType {
    CAMPUS, BUILDING, FLOOR, ZONE, ROOM, ASSET, UTILITY, INCIDENT_TYPE, LOCATION_TYPE, ASSET_TYPE, HAZARD, CONSEQUENCE, ACTION,
    TEMPORARY_ARRANGEMENT, RESPONSE_TEAM, EMERGENCY_CONTACT, RECOMMENDATION, HISTORICAL_PATTERN, EVIDENCE,
}

/** Edge types of spec section 7. */
enum class Relation {
    CONTAINS, CONNECTED_TO, HAS_EXIT, SERVED_BY, DEPENDS_ON, OCCURRED_AT, INVOLVES, PRODUCES_HAZARD, MAY_CAUSE, TRIGGERS_ACTION,
    REQUIRES_ACTION, OWNED_BY, BLOCKED_BY, REPLACES, SUPPORTED_BY, SIMILAR_TO, OBSERVED_AT, ESCALATES_TO, AFFECTS, HAS_CONTINUITY_OPTION,
}

/** One row of `kg_node`. [properties] holds the type-specific fields (see `doc/KNOWLEDGE_GRAPH.md`). */
@Serializable
data class KgNode(
    val id: String,
    val type: NodeType,
    val name: String,
    val properties: JsonObject = JsonObject(emptyMap()),
    val verificationStatus: VerificationStatus = VerificationStatus.NEEDS_ADMIN_VERIFICATION,
    val version: Int = 1,
    val active: Boolean = true,
) {
    fun str(key: String): String? = (properties[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun bool(key: String): Boolean? = (properties[key] as? JsonPrimitive)?.booleanOrNull
    fun int(key: String): Int? = (properties[key] as? JsonPrimitive)?.intOrNull
    fun num(key: String): Double? = (properties[key] as? JsonPrimitive)?.doubleOrNull
    fun strings(key: String): List<String> = (properties[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    fun obj(key: String): JsonObject? = properties[key] as? JsonObject
}

/**
 * The conditions of an edge (spec section 7). Every field is optional; an empty field puts no constraint. Lists are "any of" except
 * [hazardsAll]. A condition on a fact the incident does not have (unknown location type, unknown asset, unknown status, unknown
 * severity or injury) FAILS, so the engine abstains instead of guessing (spec section 3). See `rules/ConditionEvaluator`.
 */
@Serializable
data class EdgeConditions(
    val incidentTypes: List<String> = emptyList(),
    val locationTypes: List<String> = emptyList(),
    val assetTypes: List<String> = emptyList(),
    val statuses: List<IncidentStatus> = emptyList(),
    val hazardsAny: List<String> = emptyList(),
    val hazardsAll: List<String> = emptyList(),
    val hazardsNone: List<String> = emptyList(),
    val minSeverity: KgSeverity? = null,
    val maxSeverity: KgSeverity? = null,
    /** true = only when someone is reported hurt, false = only when nobody is. */
    val injury: Boolean? = null,
    val minPeopleAffected: Int? = null,
    val operationalEffectsAny: List<String> = emptyList(),
    val minEvidenceConfidence: Double? = null,
)

/** One row of `kg_edge`. */
@Serializable
data class KgEdge(
    val id: String,
    val sourceId: String,
    val relation: Relation,
    val targetId: String,
    val conditions: EdgeConditions = EdgeConditions(),
    val priority: Int = 50,
    /** The SOP source the edge relies on (optional for structural edges; `SUPPORTED_BY` edges name it in [targetId]). */
    val sourceRef: String? = null,
    val approvalStatus: ApprovalStatus = ApprovalStatus.DRAFT,
    val version: Int = 1,
    val active: Boolean = true,
)

/** One row of `kg_source` (spec section 6 `SopSource`). [displayLabel] is shown next to guidance that relies on it. */
@Serializable
data class KgSource(
    val id: String,
    val title: String,
    val version: String = "1",
    val uri: String? = null,
    val approvedBy: String? = null,
    val approvedAt: String? = null,
    val expiresAt: String? = null,
    val status: SourceStatus = SourceStatus.NEEDS_ADMIN_VERIFICATION,
    val displayLabel: String? = null,
)

/**
 * Deterministic text-to-fact rules (data, not code): they map a saved report onto the graph vocabulary in `KnowledgeGraphInput`.
 * Keywords match whole words, case-insensitive, in the report's English values. Rules are tried in the order given; for type, location
 * type and status the first matching rule wins, for assets, hazards and effects every matching rule adds its code.
 */
@Serializable
data class NormalizationRules(
    val incidentTypes: List<TypeRule> = emptyList(),
    val locationTypes: List<CodeRule> = emptyList(),
    val assets: List<CodeRule> = emptyList(),
    val hazards: List<CodeRule> = emptyList(),
    val operationalEffects: List<CodeRule> = emptyList(),
    val statuses: List<StatusRule> = emptyList(),
    /** Words that name a building (or a campus area); the code is the id of the BUILDING node. */
    val buildings: List<CodeRule> = emptyList(),
    /** Location types whose place text is residential: an export hides the exact place (spec section 3: no hostel-room identity). */
    val residentialLocationTypes: List<String> = emptyList(),
)

/** `appTypes`: the report's own `incident_type` values (case-insensitive) that select [code] by themselves; [anyKeywords]: words in the type, description or place. */
@Serializable
data class TypeRule(val code: String, val appTypes: List<String> = emptyList(), val anyKeywords: List<String> = emptyList(), val allKeywords: List<String> = emptyList())

@Serializable
data class CodeRule(val code: String, val anyKeywords: List<String>)

@Serializable
data class StatusRule(val status: IncidentStatus, val anyKeywords: List<String>)

/** A whole versioned graph: what is imported, exported, activated and rolled back (`GraphRepository`). */
@Serializable
data class GraphPack(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** The graph version (spec `graphVersion`); grows with every import or admin edit. */
    val packVersion: Int,
    val name: String = "pack",
    val description: String = "",
    val createdAt: String = "",
    val nodes: List<KgNode> = emptyList(),
    val edges: List<KgEdge> = emptyList(),
    val sources: List<KgSource> = emptyList(),
    val normalization: NormalizationRules = NormalizationRules(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}
