package com.example.test_app.admin

import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.EdgeConditions
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.KgEdge
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.SourceStatus
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.knowledgegraph.rules.Instants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Result of validating a form: the built value or the field errors (field name -> message). */
sealed interface FormResult<out T> {
    data class Ok<T>(val value: T) : FormResult<T>
    data class Invalid(val errors: Map<String, String>) : FormResult<Nothing>
}

/** The text fields of the node form. [propertiesJson] is a JSON object (empty = none). */
data class NodeDraft(
    val id: String = "", val type: String = NodeType.ACTION.name, val name: String = "", val propertiesJson: String = "{}",
    val verification: String = VerificationStatus.NEEDS_ADMIN_VERIFICATION.name, val active: Boolean = true,
)

/** The text fields of the edge form. [conditionsJson] is a JSON object of `EdgeConditions` (empty = no conditions). */
data class EdgeDraft(
    val id: String = "", val sourceId: String = "", val relation: String = Relation.TRIGGERS_ACTION.name, val targetId: String = "", val priority: String = "50",
    val conditionsJson: String = "{}", val sourceRef: String = "", val approval: String = ApprovalStatus.DRAFT.name, val active: Boolean = true,
)

data class SourceDraft(
    val id: String = "", val title: String = "", val version: String = "1", val uri: String = "", val approvedBy: String = "", val approvedAt: String = "",
    val expiresAt: String = "", val status: String = SourceStatus.NEEDS_ADMIN_VERIFICATION.name, val displayLabel: String = "",
)

/** Field validation of the Knowledge editor forms (pure). The service validates the whole pack again (dangling references, cycles) when the change is applied. */
object EditorForms {
    private val strict = Json { ignoreUnknownKeys = false }
    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9:._>\\-]*$")

    fun node(d: NodeDraft, pack: GraphPack? = null, editing: Boolean = false): FormResult<KgNode> {
        val e = linkedMapOf<String, String>()
        val id = d.id.trim()
        if (id.isEmpty()) e["id"] = "Enter an id, for example action:inspect-wiring." else if (!ID.matches(id)) e["id"] = "Use letters, digits and : . _ - only."
        else if (!editing && pack?.nodes?.any { it.id == id } == true) e["id"] = "A node with this id exists already."
        val type = NodeType.entries.firstOrNull { it.name == d.type }
        if (type == null) e["type"] = "Pick a node type."
        if (d.name.isBlank()) e["name"] = "Enter a name."
        val verification = VerificationStatus.entries.firstOrNull { it.name == d.verification }
        if (verification == null) e["verification"] = "Pick a verification status."
        val props = parseObject(d.propertiesJson)
        if (props == null) e["properties"] = "Properties must be a JSON object, for example {\"phase\": \"IMMEDIATE\"}."
        return if (e.isNotEmpty()) FormResult.Invalid(e) else FormResult.Ok(KgNode(id, type!!, d.name.trim(), props!!, verification!!, 1, d.active))
    }

    fun edge(d: EdgeDraft, pack: GraphPack? = null, editing: Boolean = false): FormResult<KgEdge> {
        val e = linkedMapOf<String, String>()
        val id = d.id.trim()
        if (id.isEmpty()) e["id"] = "Enter an id." else if (!ID.matches(id)) e["id"] = "Use letters, digits and : . _ - > only."
        else if (!editing && pack?.edges?.any { it.id == id } == true) e["id"] = "An edge with this id exists already."
        if (d.sourceId.isBlank()) e["sourceId"] = "Enter the source node id." else if (pack != null && pack.nodes.none { it.id == d.sourceId.trim() }) e["sourceId"] = "No node has this id."
        val rel = Relation.entries.firstOrNull { it.name == d.relation }
        if (rel == null) e["relation"] = "Pick a relation."
        if (d.targetId.isBlank()) e["targetId"] = "Enter the target id."
        else if (pack != null && rel != Relation.SUPPORTED_BY && pack.nodes.none { it.id == d.targetId.trim() }) e["targetId"] = "No node has this id."
        else if (pack != null && rel == Relation.SUPPORTED_BY && pack.sources.none { it.id == d.targetId.trim() }) e["targetId"] = "No source has this id."
        val prio = d.priority.trim().toIntOrNull()
        if (prio == null || prio !in 0..100) e["priority"] = "Priority is a whole number from 0 to 100."
        val cond = if (d.conditionsJson.isBlank()) EdgeConditions() else runCatching { strict.decodeFromString(EdgeConditions.serializer(), d.conditionsJson) }.getOrNull()
        if (cond == null) e["conditions"] = "Conditions must be a JSON object with known keys (incidentTypes, locationTypes, statuses, minSeverity, ...)."
        val approval = ApprovalStatus.entries.firstOrNull { it.name == d.approval }
        if (approval == null) e["approval"] = "Pick an approval status."
        if (d.sourceRef.isNotBlank() && pack != null && pack.sources.none { it.id == d.sourceRef.trim() }) e["sourceRef"] = "No source has this id."
        return if (e.isNotEmpty()) FormResult.Invalid(e)
        else FormResult.Ok(KgEdge(id, d.sourceId.trim(), rel!!, d.targetId.trim(), cond!!, prio!!, d.sourceRef.trim().ifEmpty { null }, approval!!, 1, d.active))
    }

    fun source(d: SourceDraft): FormResult<KgSource> {
        val e = linkedMapOf<String, String>()
        if (d.id.isBlank()) e["id"] = "Enter an id, for example sop:fire-safety:v2." else if (!ID.matches(d.id.trim())) e["id"] = "Use letters, digits and : . _ - only."
        if (d.title.isBlank()) e["title"] = "Enter a title."
        val status = SourceStatus.entries.firstOrNull { it.name == d.status }
        if (status == null) e["status"] = "Pick a status."
        if (d.approvedAt.isNotBlank() && Instants.parse(d.approvedAt) == null) e["approvedAt"] = "Use an ISO date or time, for example 2026-10-11T10:00:00+05:30."
        if (d.expiresAt.isNotBlank() && Instants.parse(d.expiresAt) == null) e["expiresAt"] = "Use an ISO date or time, for example 2027-06-01."
        if (status == SourceStatus.APPROVED) {
            if (d.approvedBy.isBlank()) e["approvedBy"] = "An approved source needs the reviewer's name."
            if (d.approvedAt.isBlank()) e["approvedAt"] = "An approved source needs the approval time."
        }
        return if (e.isNotEmpty()) FormResult.Invalid(e)
        else FormResult.Ok(
            KgSource(
                d.id.trim(), d.title.trim(), d.version.trim().ifEmpty { "1" }, d.uri.trim().ifEmpty { null }, d.approvedBy.trim().ifEmpty { null },
                d.approvedAt.trim().ifEmpty { null }, d.expiresAt.trim().ifEmpty { null }, status!!, d.displayLabel.trim().ifEmpty { null },
            ),
        )
    }

    /** Fields of the approve-recommendation form: the reviewer is required; the expiry, when given, must be a date after [nowIso]. */
    fun approval(reviewer: String, expiresAt: String, nowIso: String): FormResult<Pair<String, String?>> {
        val e = linkedMapOf<String, String>()
        if (reviewer.isBlank()) e["reviewer"] = "Enter the reviewer's name."
        val exp = expiresAt.trim()
        if (exp.isNotEmpty()) {
            val t = Instants.parse(exp)
            if (t == null) e["expiresAt"] = "Use an ISO date, for example 2027-06-01."
            else if (!t.isAfter(Instants.parse(nowIso) ?: java.time.Instant.now())) e["expiresAt"] = "The expiry must be in the future."
        }
        return if (e.isNotEmpty()) FormResult.Invalid(e) else FormResult.Ok(reviewer.trim() to exp.ifEmpty { null })
    }

    fun draftOf(n: KgNode) = NodeDraft(n.id, n.type.name, n.name, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), n.properties), n.verificationStatus.name, n.active)

    fun draftOf(e: KgEdge) = EdgeDraft(
        e.id, e.sourceId, e.relation.name, e.targetId, e.priority.toString(), Json { prettyPrint = true; encodeDefaults = false }.encodeToString(EdgeConditions.serializer(), e.conditions),
        e.sourceRef.orEmpty(), e.approvalStatus.name, e.active,
    )

    fun draftOf(s: KgSource) = SourceDraft(s.id, s.title, s.version, s.uri.orEmpty(), s.approvedBy.orEmpty(), s.approvedAt.orEmpty(), s.expiresAt.orEmpty(), s.status.name, s.displayLabel.orEmpty())

    private fun parseObject(text: String): JsonObject? =
        if (text.isBlank()) JsonObject(emptyMap()) else runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
}

/** One row of the Knowledge editor lists. */
data class EditorRow(val id: String, val title: String, val subtitle: String, val status: String?, val active: Boolean)

enum class EditorSection(val label: String) { NODES("Nodes"), EDGES("Edges"), RECOMMENDATIONS("Recommendations"), SOURCES("Sources"), TEAMS("Teams"), CONTACTS("Contacts") }

/** Browsing of the active pack in the Knowledge editor (pure, searchable). */
object EditorRows {
    fun of(pack: GraphPack, section: EditorSection, query: String): List<EditorRow> {
        val rows = when (section) {
            EditorSection.NODES -> pack.nodes.map { EditorRow(it.id, it.name, it.type.name, it.verificationStatus.name, it.active) }
            EditorSection.EDGES -> pack.edges.map { EditorRow(it.id, "${it.sourceId}  ${it.relation.name}  ${it.targetId}", "priority ${it.priority}", it.approvalStatus.name, it.active) }
            EditorSection.RECOMMENDATIONS -> pack.nodes.filter { it.type == NodeType.RECOMMENDATION }.map {
                EditorRow(it.id, it.str("title") ?: it.name, it.str("actionId").orEmpty(), it.str("approvalStatus"), it.active)
            }
            EditorSection.SOURCES -> pack.sources.map { EditorRow(it.id, it.title, listOfNotNull("v${it.version}", it.expiresAt?.let { e -> "expires $e" }).joinToString(", "), it.status.name, true) }
            EditorSection.TEAMS -> pack.nodes.filter { it.type == NodeType.RESPONSE_TEAM }.map { EditorRow(it.id, it.str("label") ?: it.name, it.str("role").orEmpty(), it.verificationStatus.name, it.active) }
            EditorSection.CONTACTS -> pack.nodes.filter { it.type == NodeType.EMERGENCY_CONTACT }.map { EditorRow(it.id, it.name, "team ${it.str("teamId").orEmpty()}", it.verificationStatus.name, it.active) }
        }
        val q = query.trim().lowercase()
        return if (q.isEmpty()) rows else rows.filter { it.id.lowercase().contains(q) || it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q) }
    }
}
