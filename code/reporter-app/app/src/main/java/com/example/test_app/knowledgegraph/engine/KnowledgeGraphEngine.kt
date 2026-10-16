package com.example.test_app.knowledgegraph.engine

import com.example.test_app.knowledgegraph.model.ApprovalStatus
import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.ConsequenceItem
import com.example.test_app.knowledgegraph.model.HistoryCitation
import com.example.test_app.knowledgegraph.model.InputFacts
import com.example.test_app.knowledgegraph.model.KgEdge
import com.example.test_app.knowledgegraph.model.KgNode
import com.example.test_app.knowledgegraph.model.KgSeverity
import com.example.test_app.knowledgegraph.model.KgSource
import com.example.test_app.knowledgegraph.model.Likelihood
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.Phase
import com.example.test_app.knowledgegraph.model.RecommendationItem
import com.example.test_app.knowledgegraph.model.RecommendationLog
import com.example.test_app.knowledgegraph.model.RecommendationSet
import com.example.test_app.knowledgegraph.model.Relation
import com.example.test_app.knowledgegraph.model.ShownEntry
import com.example.test_app.knowledgegraph.model.SourceStatus
import com.example.test_app.knowledgegraph.model.Suppression
import com.example.test_app.knowledgegraph.model.TeamItem
import com.example.test_app.knowledgegraph.model.TemporaryArrangementItem
import com.example.test_app.knowledgegraph.model.UnresolvedQuestion
import com.example.test_app.knowledgegraph.model.ValidatedIncident
import com.example.test_app.knowledgegraph.model.VerificationStatus
import com.example.test_app.knowledgegraph.repository.AuditStore
import com.example.test_app.knowledgegraph.repository.GraphIndex
import com.example.test_app.knowledgegraph.rules.ConditionEvaluator
import com.example.test_app.knowledgegraph.rules.ConditionResult
import com.example.test_app.knowledgegraph.rules.Instants
import com.example.test_app.knowledgegraph.rules.MedicalOutputGuard
import com.example.test_app.knowledgegraph.rules.PhoneNumberGuard
import com.example.test_app.knowledgegraph.rules.SafetyPolicy
import java.time.Instant

/** The engine's result plus the audit record built from the same run (the caller may patch render modes into the log before saving it again). */
data class EngineResult(val set: RecommendationSet, val log: RecommendationLog)

/**
 * Spec section 12: `recommend(ValidatedIncident)`. Deterministic, offline, pure Kotlin. The graph SELECTS approved facts and actions;
 * nothing here (or in the renderer) may add advice. Steps: reject unconfirmed input; resolve the incident type; traverse ONLY the
 * whitelisted paths of spec section 10 (type -> action, type -> hazard -> consequence -> action, location type -> temporary
 * arrangement, asset type -> action, history pattern -> action); apply edge conditions; drop unapproved, expired, disabled or
 * sourceless recommendations; enforce authorization, safety, medical and phone-number rules; remove candidates whose prerequisite is
 * unavailable; de-duplicate by canonical key; rank; order by the safety ordering with prerequisites first; apply the output limits;
 * pick the one most useful unresolved question; write the audit log. EVERY removal is recorded with its reason (spec section 13).
 *
 * [graph] returns the active index (the engine never caches it, so an import or rollback takes effect at once).
 */
class KnowledgeGraphEngine(
    private val graph: () -> GraphIndex,
    private val auditStore: AuditStore? = null,
    private val config: EngineConfig = EngineConfig(),
    private val clock: () -> Instant = { Instant.now() },
) {
    /** Runs the analysis and persists the audit log (step 14 of spec section 12). */
    fun recommend(incident: ValidatedIncident, audience: Audience = Audience.REPORTER, history: List<HistoryCitation> = emptyList()): RecommendationSet {
        val result = analyze(incident, audience, history)
        auditStore?.save(result.log)
        return result.set
    }

    /** Same analysis without persisting: the caller owns the audit log (used by the service, which renders text first). */
    fun analyze(incident: ValidatedIncident, audience: Audience = Audience.REPORTER, history: List<HistoryCitation> = emptyList()): EngineResult =
        Run(graph(), incident, audience, history).execute()

    // ---------------------------------------------------------------------------------------------

    private class Candidate(val action: KgNode) {
        var priority = 0
        val paths = linkedSetOf<String>()
        var locationMatch = false
        var assetMatch = false
        var viaHistory = false
        val citations = mutableListOf<HistoryCitation>()
        lateinit var rec: KgNode
        lateinit var source: KgSource
        var unverifiedDraft = false
        var score = 0.0
        val key: String get() = action.str("canonicalKey") ?: action.id
        val order: Int get() = ORDER.indexOf(action.str("orderClass")?.uppercase()).let { if (it < 0) ORDER.size else it }
    }

    private class ArrangementCandidate(val node: KgNode, val edge: KgEdge, val path: String, val source: KgSource, val unverifiedDraft: Boolean)

    private class SourceCheck(val usable: Boolean, val reason: String, val source: KgSource? = null, val unverified: Boolean = false)

    private class ConsequenceCandidate(val node: KgNode, val hazard: KgNode, val path: String)

    private inner class Run(val idx: GraphIndex, val incident: ValidatedIncident, val audience: Audience, val history: List<HistoryCitation>) {
        val now: Instant = clock()
        val suppressed = mutableListOf<Suppression>()
        val warnings = mutableListOf<String>()
        val missing = mutableListOf<Pair<Int, String>>() // graph priority of the blocked edge, missing fact
        val candidates = linkedMapOf<String, Candidate>() // by action id
        val consequences = linkedMapOf<String, ConsequenceCandidate>()
        val arrangements = linkedMapOf<String, ArrangementCandidate>()
        val usableContactNumbers: List<String> by lazy {
            idx.nodesOfType(NodeType.EMERGENCY_CONTACT).filter { contactUsable(it) }.mapNotNull { it.str("number") }
        }

        fun execute(): EngineResult {
            val createdAt = now.toString()
            if (!incident.userConfirmed) {
                suppressed += Suppression(incident.incidentId, "incident", "incident is not confirmed by the user: only confirmed incidents are analysed")
                return finish(abstainedSet("The incident is not confirmed yet.", question = null), createdAt)
            }
            if (incident.evidenceConfidence < config.abstainBelowEvidence) {
                suppressed += Suppression(incident.incidentId, "incident", "evidence confidence ${incident.evidenceConfidence} is below ${config.abstainBelowEvidence}")
                return finish(abstainedSet(null, UnresolvedQuestion("q:evidence", "Can you confirm what happened and where?")), createdAt)
            }
            val typeNode = idx.byCode(NodeType.INCIDENT_TYPE, incident.type)?.takeIf { it.active }
            if (typeNode == null) {
                val q = if (incident.type == null) UnresolvedQuestion("q:incidentType", QUESTIONS.getValue("incidentType")) else null
                suppressed += Suppression(incident.type ?: "unknown-type", "incident", if (incident.type == null) "incident type unknown" else "incident type ${incident.type} is not in the graph")
                return finish(abstainedSet(if (incident.type != null) "Incident type ${incident.type} is not in the graph." else null, q), createdAt)
            }
            traverseType(typeNode)
            traverseLocation()
            traverseAssets()
            traverseHistory()
            return finish(select(typeNode), createdAt)
        }

        // ---- traversal (spec section 10: whitelisted paths only) --------------------------------------

        fun traverseType(type: KgNode) {
            for (e in idx.edgesFrom(type.id, Relation.TRIGGERS_ACTION)) consider(e, "${type.id} -TRIGGERS_ACTION-> ${e.targetId}")
            for (e in idx.edgesFrom(type.id, Relation.PRODUCES_HAZARD)) {
                val hazard = idx.node(e.targetId)?.takeIf { it.type == NodeType.HAZARD && it.active }
                if (!edgeAllowed(e, "${type.id} -PRODUCES_HAZARD-> ${e.targetId}") || !conditionsHold(e)) continue
                if (hazard == null) { suppressed += Suppression(e.id, "edge", "hazard ${e.targetId} is missing or inactive"); continue }
                for (m in idx.edgesFrom(hazard.id, Relation.MAY_CAUSE)) {
                    val path = "${type.id} -PRODUCES_HAZARD-> ${hazard.id} -MAY_CAUSE-> ${m.targetId}"
                    if (!edgeAllowed(m, path) || !conditionsHold(m)) continue
                    val cons = idx.node(m.targetId)?.takeIf { it.type == NodeType.CONSEQUENCE && it.active }
                    if (cons == null) { suppressed += Suppression(m.id, "edge", "consequence ${m.targetId} is missing or inactive", path); continue }
                    if (!textAllowed(cons.id, "consequence", listOfNotNull(cons.name, cons.str("text")).joinToString(". "), path)) continue
                    consequences.putIfAbsent(cons.id, ConsequenceCandidate(cons, hazard, path))
                    for (r in idx.edgesFrom(cons.id, Relation.REQUIRES_ACTION)) consider(r, "$path -REQUIRES_ACTION-> ${r.targetId}")
                }
            }
        }

        fun traverseLocation() {
            val code = incident.locationType
            if (code == null) {
                missing += 40 to "locationType"
                suppressed += Suppression("continuity", "arrangement", "location type unknown: temporary arrangements cannot be chosen")
                return
            }
            val loc = idx.byCode(NodeType.LOCATION_TYPE, code)?.takeIf { it.active }
            if (loc == null) { warnings += "Location type $code is not in the graph."; return }
            for (e in idx.edgesFrom(loc.id, Relation.HAS_CONTINUITY_OPTION)) {
                val path = "${loc.id} -HAS_CONTINUITY_OPTION-> ${e.targetId}"
                if (!edgeAllowed(e, path) || !conditionsHold(e)) continue
                val node = idx.node(e.targetId)?.takeIf { it.type == NodeType.TEMPORARY_ARRANGEMENT && it.active }
                if (node == null) { suppressed += Suppression(e.id, "arrangement", "arrangement ${e.targetId} is missing or inactive", path); continue }
                val ref = e.sourceRef
                if (ref == null) { suppressed += Suppression(node.id, "arrangement", "sourceless arrangement (the edge names no SOP source)", path); continue }
                val check = checkSource(ref)
                if (!check.usable) { suppressed += Suppression(node.id, "arrangement", check.reason, path); continue }
                if (!textAllowed(node.id, "arrangement", listOfNotNull(node.str("title"), node.str("text")).joinToString(". "), path)) continue
                val needs = node.obj("requirements")
                if ((needs?.get("knownCapacity") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" && !capacityKnown()) {
                    suppressed += Suppression(node.id, "arrangement", "capacity data unknown: no replacement room is invented", path); continue
                }
                if ((needs?.get("alternateLocationVerified") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" && !alternateVerified()) {
                    suppressed += Suppression(node.id, "arrangement", "no verified alternate location in the graph: none is invented", path); continue
                }
                arrangements.putIfAbsent(node.id, ArrangementCandidate(node, e, path, check.source!!, check.unverified))
            }
        }

        fun traverseAssets() {
            for (code in incident.affectedAssets.distinct()) {
                val asset = idx.byCode(NodeType.ASSET_TYPE, code)?.takeIf { it.active }
                if (asset == null) { warnings += "Asset type $code is not in the graph."; continue }
                for (e in idx.edgesFrom(asset.id, Relation.REQUIRES_ACTION)) consider(e, "${asset.id} -REQUIRES_ACTION-> ${e.targetId}", assetPath = true)
            }
        }

        fun traverseHistory() {
            for (c in history) {
                val pattern = idx.node(c.patternId)?.takeIf { it.type == NodeType.HISTORICAL_PATTERN && it.active }
                if (pattern == null) { suppressed += Suppression(c.patternId, "pattern", "historical pattern is missing or inactive"); continue }
                if (c.incidentIds.isEmpty() || c.dates.isEmpty()) {
                    suppressed += Suppression(pattern.id, "pattern", "history suggestion without supporting incident ids and dates"); continue
                }
                for (e in idx.edgesFrom(pattern.id, Relation.TRIGGERS_ACTION)) consider(e, "${pattern.id} -TRIGGERS_ACTION-> ${e.targetId}", citation = c)
            }
        }

        /** One edge into an ACTION: edge approval, conditions, then register the candidate. */
        fun consider(e: KgEdge, path: String, assetPath: Boolean = false, citation: HistoryCitation? = null) {
            if (!edgeAllowed(e, path)) return
            val result = evaluate(e)
            if (!result.passed) {
                suppressed += Suppression(e.targetId, "action", "condition failed: ${result.reason}", path)
                if (result.reasons.size == result.missingFacts.size) result.missingFacts.forEach { missing += e.priority to it }
                return
            }
            val action = idx.node(e.targetId)?.takeIf { it.type == NodeType.ACTION }
            if (action == null) { suppressed += Suppression(e.targetId, "action", "action node is missing", path); return }
            if (!action.active) { suppressed += Suppression(action.id, "action", "action is disabled", path); return }
            val c = candidates.getOrPut(action.id) { Candidate(action) }
            c.priority = maxOf(c.priority, e.priority)
            c.paths += "$path [${e.id}]"
            c.locationMatch = c.locationMatch || result.matchedLocationType
            c.assetMatch = c.assetMatch || result.matchedAssetType || assetPath
            if (citation != null) { c.viaHistory = true; if (c.citations.none { it.patternId == citation.patternId }) c.citations += citation }
        }

        fun evaluate(e: KgEdge): ConditionResult = ConditionEvaluator.evaluate(e.conditions, incident)

        fun conditionsHold(e: KgEdge): Boolean {
            val r = evaluate(e)
            if (!r.passed) {
                suppressed += Suppression(e.id, "edge", "condition failed: ${r.reason}")
                if (r.reasons.size == r.missingFacts.size) r.missingFacts.forEach { missing += e.priority to it }
            }
            return r.passed
        }

        /** Edge life cycle: active, approved for the audience, its own SOP source (when named) usable. */
        fun edgeAllowed(e: KgEdge, path: String): Boolean {
            if (!e.active) { suppressed += Suppression(e.id, "edge", "edge is disabled", path); return false }
            val approved = when (audience) {
                Audience.REPORTER -> e.approvalStatus == ApprovalStatus.APPROVED
                Audience.ADMIN_PREVIEW -> e.approvalStatus != ApprovalStatus.DISABLED && e.approvalStatus != ApprovalStatus.EXPIRED
            }
            if (!approved) { suppressed += Suppression(e.id, "edge", "edge approval status is ${e.approvalStatus}", path); return false }
            e.sourceRef?.let { ref ->
                val check = checkSource(ref)
                if (!check.usable) { suppressed += Suppression(e.id, "edge", "edge ${check.reason}", path); return false }
            }
            return true
        }

        // ---- source / approval checks -----------------------------------------------------------------

        fun checkSource(id: String): SourceCheck {
            val s = idx.sources[id] ?: return SourceCheck(false, "source $id not found")
            if (s.status == SourceStatus.EXPIRED || Instants.isExpired(s.expiresAt, now)) return SourceCheck(false, "source $id is expired (SOP guidance is not shown after expiry)")
            if (s.status == SourceStatus.REJECTED) return SourceCheck(false, "source $id is rejected")
            return when (s.status) {
                SourceStatus.APPROVED ->
                    if (audience == Audience.REPORTER && (s.approvedBy.isNullOrBlank() || s.approvedAt.isNullOrBlank())) SourceCheck(false, "source $id has an incomplete approval record")
                    else SourceCheck(true, "", s, unverified = s.approvedBy.isNullOrBlank() || s.approvedAt.isNullOrBlank())
                else ->
                    if (audience == Audience.REPORTER) SourceCheck(false, "source $id is not approved (${s.status}): unverified guidance is not shown to ordinary users")
                    else SourceCheck(true, "", s, unverified = true)
            }
        }

        fun contactUsable(n: KgNode): Boolean =
            n.verificationStatus == VerificationStatus.VERIFIED && n.active && n.bool("enabled") == true && !n.str("verifiedAt").isNullOrBlank() &&
                !Instants.isExpired(n.str("expiresAt"), now) && !n.str("number").isNullOrBlank()

        /** Safety, medical and phone-number filters for any text that could reach a user. */
        fun textAllowed(id: String, kind: String, text: String, path: String?): Boolean {
            val medical = MedicalOutputGuard.violations(text)
            if (medical.isNotEmpty()) { suppressed += Suppression(id, kind, "medical output rule: diagnosis, medication or treatment content ($medical)", path); return false }
            val numbers = PhoneNumberGuard.unverifiedNumbers(text, usableContactNumbers)
            if (numbers.isNotEmpty()) { suppressed += Suppression(id, kind, "contains an unverified phone number", path); return false }
            return true
        }

        fun capacityKnown(): Boolean {
            val loc = idx.node(incident.locationId) ?: return false
            return (loc.int("capacity") ?: 0) > 0
        }

        fun alternateVerified(): Boolean = idx.nodesOfType(NodeType.ROOM).any {
            it.active && it.verificationStatus == VerificationStatus.VERIFIED && incident.locationType?.uppercase() in it.strings("alternateForLocationTypes").map { c -> c.uppercase() }
        }

        // ---- vetting, ranking, selection --------------------------------------------------------------

        fun vet(c: Candidate): Boolean {
            val a = c.action
            val path = c.paths.firstOrNull()
            val rec = idx.recommendationFor(a.id)
            if (rec == null) { suppressed += Suppression(a.id, "action", "no recommendation record: unreviewed or sourceless action", path); return false }
            if (!rec.active) { suppressed += Suppression(rec.id, "action", "recommendation is disabled", path); return false }
            val status = rec.str("approvalStatus")?.let { s -> ApprovalStatus.entries.firstOrNull { it.name == s } } ?: ApprovalStatus.DRAFT
            if (status == ApprovalStatus.DISABLED) { suppressed += Suppression(rec.id, "action", "recommendation is DISABLED", path); return false }
            if (status == ApprovalStatus.EXPIRED || Instants.isExpired(rec.str("expiresAt"), now)) { suppressed += Suppression(rec.id, "action", "recommendation is expired", path); return false }
            var draft = false
            if (status != ApprovalStatus.APPROVED) {
                if (audience == Audience.REPORTER) { suppressed += Suppression(rec.id, "action", "recommendation is not approved (status $status)", path); return false }
                draft = true
            } else if (audience == Audience.REPORTER && (rec.str("approvedBy").isNullOrBlank() || rec.str("approvedAt").isNullOrBlank())) {
                suppressed += Suppression(rec.id, "action", "approval record incomplete (reviewer or approval time missing)", path); return false
            }
            val support = idx.supportEdges(rec.id).filter { it.active }
            if (support.isEmpty()) { suppressed += Suppression(rec.id, "action", "sourceless recommendation (no SUPPORTED_BY source)", path); return false }
            var chosen: SourceCheck? = null
            val reasons = mutableListOf<String>()
            for (s in support) {
                if (audience == Audience.REPORTER && s.approvalStatus != ApprovalStatus.APPROVED) { reasons += "support edge ${s.id} is ${s.approvalStatus}"; continue }
                val check = checkSource(s.targetId)
                if (check.usable) { chosen = check; break } else reasons += check.reason
            }
            if (chosen == null) { suppressed += Suppression(rec.id, "action", reasons.joinToString("; "), path); return false }
            val safety = SafetyPolicy.check(a, incident)
            if (safety.isNotEmpty()) { suppressed += Suppression(rec.id, "action", "safety rule: ${safety.joinToString("; ")}", path); return false }
            if (!textAllowed(rec.id, "action", listOfNotNull(a.str("title"), a.str("canonicalText")).joinToString(". "), path)) return false
            c.rec = rec
            c.source = chosen.source!!
            c.unverifiedDraft = draft || chosen.unverified
            return true
        }

        fun score(c: Candidate, known: Double): Double {
            val w = config.weights
            val sev = incident.severity
            val safetyClass = c.order <= 2
            val severityRel = when {
                sev == null -> 0.5
                safetyClass -> (sev.ordinal + 1) / 4.0
                else -> (4 - sev.ordinal) / 4.0
            }
            val ev = (known + incident.evidenceConfidence.coerceIn(0.0, 1.0)) / 2.0
            return w.graphPriority * (c.priority / 100.0).coerceIn(0.0, 1.0) +
                w.severityRelevance * severityRel +
                w.locationMatch * (if (c.locationMatch) 1.0 else 0.5) +
                w.assetMatch * (if (c.assetMatch) 1.0 else 0.5) +
                w.recurrence * (if (c.viaHistory) 1.0 else 0.0) +
                w.evidenceCompleteness * ev
        }

        fun knownFraction(): Double = listOf(
            incident.type != null, incident.severity != null, incident.locationType != null,
            incident.status != com.example.test_app.knowledgegraph.model.IncidentStatus.UNKNOWN, incident.affectedAssets.isNotEmpty(), incident.activeHazards.isNotEmpty(),
        ).count { it } / 6.0

        fun select(type: KgNode): RecommendationSet {
            val vetted = candidates.values.filter { vet(it) }.toMutableList()
            val known = knownFraction()
            vetted.forEach { it.score = score(it, known) }
            // de-duplication by canonical key: the best score survives
            val byKey = vetted.groupBy { it.key }
            val survivors = mutableListOf<Candidate>()
            for ((key, group) in byKey) {
                val best = group.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.rec.id }).first()
                survivors += best
                group.filter { it !== best }.forEach { suppressed += Suppression(it.rec.id, "action", "duplicate of ${best.rec.id} (canonical key $key)", it.paths.firstOrNull()) }
            }
            var alive = survivors.toMutableList()
            // prerequisites: a dependent whose prerequisite is not available is removed (to a fixpoint)
            while (true) {
                val keys = alive.map { it.key }.toSet()
                val blocked = alive.firstOrNull { c ->
                    idx.prerequisitesOf(c.action.id).any { p -> idx.node(p)?.let { n -> (n.str("canonicalKey") ?: n.id) !in keys } ?: true }
                } ?: break
                val missingPrereq = idx.prerequisitesOf(blocked.action.id).filter { p -> idx.node(p)?.let { n -> (n.str("canonicalKey") ?: n.id) !in keys } ?: true }
                suppressed += Suppression(blocked.rec.id, "action", "prerequisite action not available or incomplete: $missingPrereq", blocked.paths.firstOrNull())
                alive.remove(blocked)
            }
            val ordered = orderWithPrerequisites(alive)
            val placedKeys = ordered.map { it.key }.toSet()
            alive.filter { it.key !in placedKeys }.forEach { suppressed += Suppression(it.rec.id, "action", "prerequisite cycle", it.paths.firstOrNull()) }

            val lim = config.limits
            val limitOf = mapOf(Phase.IMMEDIATE to lim.immediate, Phase.OPERATIONAL to lim.operational, Phase.RECOVERY to lim.recovery, Phase.PREVENTION to lim.preventive)
            val chosen = mutableListOf<Candidate>()
            val count = mutableMapOf<Phase, Int>()
            val chosenKeys = mutableSetOf<String>()
            for (c in ordered) {
                val phase = phaseOf(c.action)
                if (phase == null) { suppressed += Suppression(c.rec.id, "action", "action has no valid phase", c.paths.firstOrNull()); continue }
                if ((count[phase] ?: 0) >= limitOf.getValue(phase)) { suppressed += Suppression(c.rec.id, "action", "phase limit (${limitOf.getValue(phase)} ${phase.name.lowercase()}) reached", c.paths.firstOrNull()); continue }
                val prereqKeys = idx.prerequisitesOf(c.action.id).mapNotNull { idx.node(it)?.let { n -> n.str("canonicalKey") ?: n.id } }
                if (prereqKeys.any { it !in chosenKeys }) { suppressed += Suppression(c.rec.id, "action", "its prerequisite did not fit the output limits", c.paths.firstOrNull()); continue }
                chosen += c; chosenKeys += c.key; count[phase] = (count[phase] ?: 0) + 1
            }
            val rank = mutableMapOf<Phase, Int>()
            val items = chosen.map { c ->
                val phase = phaseOf(c.action)!!
                rank[phase] = (rank[phase] ?: 0) + 1
                val owner = idx.ownerOf(c.action.id)
                RecommendationItem(
                    id = c.rec.id, actionId = c.action.id, title = c.action.str("title") ?: c.action.name, phase = phase, rank = rank.getValue(phase),
                    score = Math.round(c.score * 1000) / 1000.0, sourceId = c.source.id, sourceLabel = c.source.displayLabel,
                    provenance = c.paths.toList(), canonicalText = c.action.str("canonicalText").orEmpty(), renderedText = c.action.str("canonicalText").orEmpty(),
                    ownerTeamId = owner?.id, ownerTeamName = owner?.let { teamName(it) }, requiresHumanApproval = c.action.bool("requiresHumanApproval") == true,
                    forbiddenForReporter = c.action.strings("forbiddenForReporter"), unverifiedDraft = c.unverifiedDraft, history = c.citations.toList(),
                )
            }
            val shownArrangements = arrangements.values.sortedWith(compareByDescending<ArrangementCandidate> { it.edge.priority }.thenBy { it.node.id })
            val arrItems = shownArrangements.take(lim.temporaryArrangements).map {
                TemporaryArrangementItem(
                    it.node.id, it.node.str("title") ?: it.node.name, it.node.str("text").orEmpty(), idx.ownerOf(it.node.id)?.id ?: it.node.str("ownerTeamId"),
                    listOf(it.path), it.source.id, it.unverifiedDraft,
                )
            }
            shownArrangements.drop(lim.temporaryArrangements).forEach { suppressed += Suppression(it.node.id, "arrangement", "arrangement limit (${lim.temporaryArrangements}) reached", it.path) }
            val consItems = consequences.values.map { cc -> consequenceItem(cc) }
                .sortedWith(compareByDescending<ConsequenceItem> { it.likelihood.ordinal }.thenBy { CONSEQUENCE_ORDER.indexOf(it.consequenceType).let { i -> if (i < 0) 9 else i } }.thenBy { it.id })
            consItems.drop(config.maxConsequences).forEach { suppressed += Suppression(it.id, "consequence", "consequence limit (${config.maxConsequences}) reached") }

            val teams = (items.mapNotNull { it.ownerTeamId } + arrItems.mapNotNull { it.ownerTeamId }).distinct().mapNotNull { id -> idx.node(id)?.takeIf { it.type == NodeType.RESPONSE_TEAM && it.active }?.let { teamItem(it) } }
            val anyDraft = items.any { it.unverifiedDraft } || arrItems.any { it.unverifiedDraft }
            if (audience == Audience.ADMIN_PREVIEW && anyDraft) warnings += "Preview: some guidance relies on draft or unverified sources and would not be shown to ordinary users."
            if (items.isEmpty() && arrItems.isEmpty()) warnings += "No approved guidance matched this incident."
            val label = (items.mapNotNull { it.sourceLabel } + (arrItems.mapNotNull { a -> a.sourceId?.let { idx.sources[it]?.displayLabel } })).firstOrNull()
            val footer = if (incident.injury == true || type.str("category").equals("MEDICAL", true)) MedicalOutputGuard.FOOTER else null
            val question = pickQuestion()
            return RecommendationSet(
                incidentId = incident.incidentId, audience = audience, graphVersion = idx.version,
                immediateActions = items.filter { it.phase == Phase.IMMEDIATE }, consequences = consItems.take(config.maxConsequences),
                operationalSteps = items.filter { it.phase == Phase.OPERATIONAL }, recoverySteps = items.filter { it.phase == Phase.RECOVERY },
                temporaryArrangements = arrItems, preventiveActions = items.filter { it.phase == Phase.PREVENTION }, responsibleTeams = teams,
                unresolvedQuestions = listOfNotNull(question).take(lim.questions), recommendationIds = items.map { it.id },
                suppressed = suppressed.toList(), historyCitations = items.flatMap { it.history }.distinctBy { it.patternId }, warnings = warnings.toList(),
                medicalFooter = footer, guidanceLabel = label,
            )
        }

        fun phaseOf(a: KgNode): Phase? = a.str("phase")?.let { p -> Phase.entries.firstOrNull { it.name == p.uppercase() } }

        fun teamName(n: KgNode) = n.str("label") ?: n.name

        fun teamItem(n: KgNode): TeamItem {
            val contact = idx.contactsOfTeam(n.id).firstOrNull { contactUsable(it) }
            return TeamItem(n.id, teamName(n), n.str("role") ?: "", n.verificationStatus == VerificationStatus.VERIFIED, contact?.str("number"))
        }

        fun consequenceItem(cc: ConsequenceCandidate): ConsequenceItem {
            val observed = cc.node.str("observedWhenEffect")
            val likelihood = when {
                observed != null && incident.operationalEffects.any { it.equals(observed, true) } -> Likelihood.CONFIRMED
                incident.activeHazards.any { it.equals(cc.hazard.str("code"), true) } -> Likelihood.LIKELY
                else -> Likelihood.POSSIBLE
            }
            return ConsequenceItem(cc.node.id, cc.node.name, cc.node.str("text").orEmpty(), cc.node.str("consequenceType") ?: "", likelihood, listOf(cc.path))
        }

        fun pickQuestion(): UnresolvedQuestion? {
            val fact = missing.sortedWith(compareByDescending<Pair<Int, String>> { it.first }.thenBy { FACT_ORDER.indexOf(it.second).let { i -> if (i < 0) 99 else i } })
                .map { it.second }.firstOrNull { QUESTIONS.containsKey(it) } ?: return null
            return UnresolvedQuestion("q:$fact", QUESTIONS.getValue(fact))
        }

        /** Kahn's algorithm: prerequisites first, otherwise by safety order class, then score, then id. Members of a cycle never become available. */
        fun orderWithPrerequisites(list: List<Candidate>): List<Candidate> {
            val byKey = list.associateBy { it.key }
            val remaining = list.toMutableList()
            val placed = mutableSetOf<String>()
            val out = mutableListOf<Candidate>()
            val sorter = compareBy<Candidate> { it.order }.thenByDescending { it.score }.thenBy { it.rec.id }
            while (true) {
                val ready = remaining.filter { c ->
                    idx.prerequisitesOf(c.action.id).all { p -> val k = idx.node(p)?.let { n -> n.str("canonicalKey") ?: n.id }; k == null || k in placed || k !in byKey }
                }.sortedWith(sorter)
                val next = ready.firstOrNull() ?: break
                out += next; placed += next.key; remaining.remove(next)
            }
            return out
        }

        // ---- result + audit ---------------------------------------------------------------------------

        fun abstainedSet(warning: String?, question: UnresolvedQuestion?): RecommendationSet = RecommendationSet(
            incidentId = incident.incidentId, audience = audience, graphVersion = idx.version, abstained = true,
            unresolvedQuestions = listOfNotNull(question), suppressed = suppressed.toList(), warnings = listOfNotNull(warning),
            medicalFooter = if (incident.injury == true) MedicalOutputGuard.FOOTER else null,
        )

        fun finish(set: RecommendationSet, createdAt: String): EngineResult {
            val shown = buildList {
                set.allRecommendations.forEach { add(ShownEntry(it.id, "recommendation", it.phase.name, it.rank, it.score, it.sourceId, it.provenance, it.renderMode.name)) }
                set.consequences.forEach { add(ShownEntry(it.id, "consequence", provenance = it.provenance)) }
                set.temporaryArrangements.forEach { add(ShownEntry(it.id, "arrangement", sourceId = it.sourceId, provenance = it.provenance)) }
                set.responsibleTeams.forEach { add(ShownEntry(it.id, "team")) }
            }
            val log = RecommendationLog(
                id = "log:${incident.incidentId}:g${set.graphVersion}:${now.toEpochMilli()}", incidentId = incident.incidentId, graphVersion = set.graphVersion,
                audience = audience, createdAt = createdAt, userConfirmed = incident.userConfirmed, abstained = set.abstained, recommendationIds = set.recommendationIds,
                shown = shown, suppressed = set.suppressed, unresolvedQuestions = set.unresolvedQuestions, warnings = set.warnings, historyCitations = set.historyCitations,
                inputFacts = InputFacts(
                    incident.type, incident.severity?.name, incident.status.name, incident.locationType, incident.affectedAssets, incident.activeHazards,
                    incident.peopleAffected, incident.operationalEffects, incident.injury, incident.evidenceConfidence,
                ),
            )
            return EngineResult(set, log)
        }
    }

    companion object {
        /** Spec section 12 ordering: life safety > hazard isolation > responder escalation > service continuity > evidence preservation > restoration > recurrence prevention. */
        val ORDER = listOf("LIFE_SAFETY", "HAZARD_ISOLATION", "RESPONDER_ESCALATION", "SERVICE_CONTINUITY", "EVIDENCE_PRESERVATION", "RESTORATION", "PREVENTION")
        private val CONSEQUENCE_ORDER = listOf("SAFETY", "SECONDARY", "ASSET", "OPERATIONAL")
        private val FACT_ORDER = listOf("incidentType", "locationType", "asset", "status", "severity", "injury", "peopleAffected")
        val QUESTIONS = mapOf(
            "incidentType" to "What kind of incident was this (fire, water, injury, equipment, spill, ...)?",
            "locationType" to "What kind of place was it (lab, classroom, hostel, mess, outdoors, ...)?",
            "asset" to "Which equipment or item was affected?",
            "status" to "Is the situation still going on, under control, or over?",
            "severity" to "How serious is it?",
            "injury" to "Was anyone hurt?",
            "peopleAffected" to "How many people are affected?",
        )
    }
}
