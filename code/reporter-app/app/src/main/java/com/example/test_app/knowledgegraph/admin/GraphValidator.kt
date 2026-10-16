package com.example.test_app.knowledgegraph.admin

import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.IncidentStatus
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Phase
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.SourceStatus
import com.example.test_app.knowledgegraph.model.ValidatedIncident
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.knowledgegraph.rules.Instants
import com.example.test_app.knowledgegraph.rules.MedicalOutputGuard
import com.example.test_app.knowledgegraph.rules.PhoneNumberGuard
import com.example.test_app.knowledgegraph.rules.SafetyPolicy

/** Findings of [GraphValidator]: [errors] block an import or an admin edit, [warnings] do not. */
data class ValidationReport(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * Spec section 18: validates a pack before it can become active: dangling references, prerequisite cycles, the production approval rule
 * (an APPROVED recommendation needs a source, a reviewer and an approval time; an expiry, when given, must be readable), text that could
 * never be shown (medical wording, unverified phone numbers, unsafe instructions to the reporter) and enabled contacts that are not verified.
 */
object GraphValidator {
    private val neutralIncident = ValidatedIncident(incidentId = "validation", activeHazards = listOf("FIRE"), status = IncidentStatus.UNKNOWN)

    fun validate(pack: GraphPack): ValidationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (pack.packVersion < 1) errors += "packVersion must be at least 1"
        if (pack.schemaVersion != GraphPack.SCHEMA_VERSION) errors += "unsupported schemaVersion ${pack.schemaVersion} (this app reads ${GraphPack.SCHEMA_VERSION})"

        fun <T> duplicates(items: List<T>, key: (T) -> String) = items.groupBy(key).filterValues { it.size > 1 }.keys
        duplicates(pack.nodes) { it.id }.forEach { errors += "duplicate node id $it" }
        duplicates(pack.edges) { it.id }.forEach { errors += "duplicate edge id $it" }
        duplicates(pack.sources) { it.id }.forEach { errors += "duplicate source id $it" }
        pack.nodes.filter { it.str("code") != null }.groupBy { it.type to it.str("code")!!.uppercase() }.filterValues { it.size > 1 }
            .forEach { (k, _) -> errors += "duplicate ${k.first} code ${k.second}" }

        val nodes = pack.nodes.associateBy { it.id }
        val sources = pack.sources.associateBy { it.id }
        for (e in pack.edges) {
            if (e.sourceId !in nodes) errors += "edge ${e.id}: source node ${e.sourceId} does not exist"
            if (e.relation == Relation.SUPPORTED_BY) {
                if (e.targetId !in sources) errors += "edge ${e.id}: SOP source ${e.targetId} does not exist"
            } else if (e.targetId !in nodes) errors += "edge ${e.id}: target node ${e.targetId} does not exist"
            e.sourceRef?.let { if (it !in sources) errors += "edge ${e.id}: sourceRef $it does not exist" }
        }

        for (n in pack.nodes) when (n.type) {
            NodeType.ACTION -> validateAction(n, pack, errors, warnings)
            NodeType.RECOMMENDATION -> validateRecommendation(n, pack, nodes, errors)
            NodeType.CONSEQUENCE -> checkText(n.id, listOfNotNull(n.name, n.str("text")).joinToString(". "), errors)
            NodeType.TEMPORARY_ARRANGEMENT -> checkText(n.id, listOfNotNull(n.str("title"), n.str("text")).joinToString(". "), errors)
            NodeType.EMERGENCY_CONTACT ->
                if (n.bool("enabled") == true && (n.verificationStatus != VerificationStatus.VERIFIED || n.str("verifiedAt").isNullOrBlank())) {
                    errors += "contact ${n.id} is enabled without verification (spec section 3: contacts must be administrator-verified)"
                }
            NodeType.HISTORICAL_PATTERN ->
                if (n.int("windowDays") == null || n.int("threshold") == null || n.strings("incidentTypes").isEmpty()) errors += "pattern ${n.id} needs incidentTypes, windowDays and threshold"
            else -> Unit
        }

        // every ACTION needs a recommendation (otherwise the engine can never show it)
        val recActions = pack.nodes.filter { it.type == NodeType.RECOMMENDATION }.mapNotNull { it.str("actionId") }.toSet()
        pack.nodes.filter { it.type == NodeType.ACTION && it.id !in recActions }.forEach { warnings += "action ${it.id} has no recommendation record: it is never shown" }
        for (e in pack.edges.filter { it.relation == Relation.HAS_CONTINUITY_OPTION && it.sourceRef == null }) warnings += "edge ${e.id}: temporary arrangement without a source is never shown"

        // prerequisite cycles
        findCycle(pack)?.let { errors += "prerequisite cycle: ${it.joinToString(" -> ")}" }
        for (r in pack.normalization.buildings) if (r.code !in nodes) warnings += "normalization building rule ${r.code} names no node"
        return ValidationReport(errors, warnings)
    }

    private fun validateAction(n: KgNode, pack: GraphPack, errors: MutableList<String>, warnings: MutableList<String>) {
        if (n.str("phase")?.let { p -> Phase.entries.none { it.name == p.uppercase() } } != false) errors += "action ${n.id}: phase must be one of ${Phase.entries.map { it.name }}"
        if (n.str("canonicalText").isNullOrBlank()) errors += "action ${n.id}: canonicalText is empty"
        if (n.bool("userCanPerform") == null || n.bool("requiresAuthorization") == null) errors += "action ${n.id}: userCanPerform and requiresAuthorization must be set"
        checkText(n.id, listOfNotNull(n.str("title"), n.str("canonicalText")).joinToString(". "), errors)
        SafetyPolicy.check(n, neutralIncident).forEach { errors += "action ${n.id}: $it" }
        if (pack.edges.none { it.relation == Relation.OWNED_BY && it.sourceId == n.id }) warnings += "action ${n.id} has no responsible team (OWNED_BY)"
    }

    private fun validateRecommendation(n: KgNode, pack: GraphPack, nodes: Map<String, KgNode>, errors: MutableList<String>) {
        val actionId = n.str("actionId")
        if (actionId == null || nodes[actionId]?.type != NodeType.ACTION) errors += "recommendation ${n.id}: actionId $actionId is not an action"
        val status = n.str("approvalStatus")?.let { s -> ApprovalStatus.entries.firstOrNull { it.name == s } }
        if (status == null) { errors += "recommendation ${n.id}: approvalStatus is missing or unknown"; return }
        val support = pack.edges.filter { it.relation == Relation.SUPPORTED_BY && it.sourceId == n.id }
        val expiry = n.str("expiresAt")
        if (!expiry.isNullOrBlank() && Instants.parse(expiry) == null) errors += "recommendation ${n.id}: expiresAt is not an ISO date"
        if (status == ApprovalStatus.APPROVED) {
            // production rule (spec section 18): no APPROVED without source, reviewer and approval time
            if (support.isEmpty()) errors += "recommendation ${n.id} is APPROVED without a source"
            if (n.str("approvedBy").isNullOrBlank()) errors += "recommendation ${n.id} is APPROVED without a reviewer (approvedBy)"
            if (Instants.parse(n.str("approvedAt")) == null) errors += "recommendation ${n.id} is APPROVED without an approval time (approvedAt)"
            val src = support.firstNotNullOfOrNull { s -> pack.sources.firstOrNull { it.id == s.targetId } }
            if (src != null && src.status == SourceStatus.REJECTED) errors += "recommendation ${n.id} is APPROVED on a rejected source"
        }
    }

    private fun checkText(id: String, text: String, errors: MutableList<String>) {
        MedicalOutputGuard.violations(text).takeIf { it.isNotEmpty() }?.let { errors += "$id: text breaks the medical output rules $it" }
        PhoneNumberGuard.unverifiedNumbers(text).takeIf { it.isNotEmpty() }?.let { errors += "$id: text contains a phone number" }
    }

    /** One prerequisite cycle (ids in order) or null. DEPENDS_ON edges between actions: source depends on target. */
    fun findCycle(pack: GraphPack): List<String>? {
        val next = pack.edges.filter { it.relation == Relation.DEPENDS_ON }.groupBy({ it.sourceId }, { it.targetId })
        val state = mutableMapOf<String, Int>() // 1 = on the stack, 2 = done
        val stack = ArrayDeque<String>()
        fun visit(id: String): List<String>? {
            when (state[id]) { 2 -> return null; 1 -> return stack.toList().dropWhile { it != id } + id }
            state[id] = 1; stack.addLast(id)
            for (t in next[id].orEmpty()) visit(t)?.let { return it }
            stack.removeLast(); state[id] = 2
            return null
        }
        for (id in next.keys) visit(id)?.let { return it }
        return null
    }
}
