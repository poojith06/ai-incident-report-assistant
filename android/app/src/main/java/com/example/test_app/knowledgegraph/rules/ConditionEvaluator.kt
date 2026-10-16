package com.example.test_app.knowledgegraph.rules

import com.example.test_app.knowledgegraph.model.EdgeConditions
import com.example.test_app.knowledgegraph.model.IncidentStatus
import com.example.test_app.knowledgegraph.model.ValidatedIncident

/** Outcome of checking [EdgeConditions] against an incident. [missingFacts] names the unknown facts that made it fail (`locationType`, `asset`, `status`, `severity`, `injury`, `peopleAffected`, `incidentType`). */
data class ConditionResult(
    val passed: Boolean,
    val reasons: List<String> = emptyList(),
    val missingFacts: Set<String> = emptyList<String>().toSet(),
    val matchedLocationType: Boolean = false,
    val matchedAssetType: Boolean = false,
) {
    val reason: String get() = reasons.joinToString("; ")
}

/**
 * Spec sections 7 and 20: every edge condition operator. A condition on a fact the incident does not have FAILS and names the missing
 * fact (the engine abstains instead of guessing). Codes compare case-insensitively. An empty condition passes.
 */
object ConditionEvaluator {
    fun evaluate(c: EdgeConditions, incident: ValidatedIncident): ConditionResult {
        val reasons = mutableListOf<String>()
        val missing = linkedSetOf<String>()
        var locationMatched = false
        var assetMatched = false
        fun up(list: List<String>) = list.map { it.uppercase() }.toSet()

        if (c.incidentTypes.isNotEmpty()) {
            val type = incident.type?.uppercase()
            when {
                type == null -> { missing += "incidentType"; reasons += "incident type unknown" }
                type !in up(c.incidentTypes) -> reasons += "incident type $type not in ${c.incidentTypes}"
            }
        }
        if (c.locationTypes.isNotEmpty()) {
            val lt = incident.locationType?.uppercase()
            when {
                lt == null -> { missing += "locationType"; reasons += "location type unknown" }
                lt !in up(c.locationTypes) -> reasons += "location type $lt not in ${c.locationTypes}"
                else -> locationMatched = true
            }
        }
        if (c.assetTypes.isNotEmpty()) {
            val assets = up(incident.affectedAssets)
            when {
                assets.isEmpty() -> { missing += "asset"; reasons += "affected asset unknown" }
                assets.none { it in up(c.assetTypes) } -> reasons += "asset types $assets not in ${c.assetTypes}"
                else -> assetMatched = true
            }
        }
        if (c.statuses.isNotEmpty()) {
            when {
                incident.status == IncidentStatus.UNKNOWN && IncidentStatus.UNKNOWN !in c.statuses -> { missing += "status"; reasons += "incident status unknown" }
                incident.status !in c.statuses -> reasons += "status ${incident.status} not in ${c.statuses}"
            }
        }
        val hazards = up(incident.activeHazards)
        if (c.hazardsAny.isNotEmpty() && hazards.none { it in up(c.hazardsAny) }) reasons += "no active hazard of ${c.hazardsAny}"
        if (c.hazardsAll.isNotEmpty() && !hazards.containsAll(up(c.hazardsAll))) reasons += "not all hazards ${c.hazardsAll} are active"
        if (c.hazardsNone.isNotEmpty() && hazards.any { it in up(c.hazardsNone) }) reasons += "excluded hazard active (${c.hazardsNone})"
        if (c.minSeverity != null || c.maxSeverity != null) {
            val sev = incident.severity
            if (sev == null) { missing += "severity"; reasons += "severity unknown" } else {
                if (c.minSeverity != null && sev < c.minSeverity) reasons += "severity $sev below ${c.minSeverity}"
                if (c.maxSeverity != null && sev > c.maxSeverity) reasons += "severity $sev above ${c.maxSeverity}"
            }
        }
        if (c.injury != null) {
            val inj = incident.injury
            when {
                inj == null -> { missing += "injury"; reasons += "injury state unknown" }
                inj != c.injury -> reasons += "injury is $inj, needs ${c.injury}"
            }
        }
        if (c.minPeopleAffected != null) {
            val n = incident.peopleAffected
            when {
                n == null -> { missing += "peopleAffected"; reasons += "number of people affected unknown" }
                n < c.minPeopleAffected -> reasons += "people affected $n below ${c.minPeopleAffected}"
            }
        }
        if (c.operationalEffectsAny.isNotEmpty() && up(incident.operationalEffects).none { it in up(c.operationalEffectsAny) }) {
            reasons += "no operational effect of ${c.operationalEffectsAny}"
        }
        if (c.minEvidenceConfidence != null && incident.evidenceConfidence < c.minEvidenceConfidence) {
            reasons += "evidence confidence ${incident.evidenceConfidence} below ${c.minEvidenceConfidence}"
        }
        return ConditionResult(reasons.isEmpty(), reasons, missing, locationMatched, assetMatched)
    }
}
