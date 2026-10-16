package com.example.test_app.knowledgegraph

import com.example.test_app.dialogue.DialogueFields
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InjuryOutcome
import com.example.test_app.domain.model.asBooleanOrNull
import com.example.test_app.domain.model.asStringList
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.isEmptyValue
import com.example.test_app.knowledgegraph.history.HistoricIncident
import com.example.test_app.knowledgegraph.model.CodeRule
import com.example.test_app.knowledgegraph.model.IncidentStatus
import com.example.test_app.knowledgegraph.model.KgSeverity
import com.example.test_app.knowledgegraph.model.NormalizationRules
import com.example.test_app.knowledgegraph.model.ValidatedIncident

/**
 * The ONE adapter from the app's `IncidentReport` to the graph's `ValidatedIncident` (and to the history detector's `HistoricIncident`).
 * Deterministic, rule-driven (the keyword rules are DATA in the pack: [NormalizationRules]) and honest: a fact the report does not carry
 * (asset, hazard, status, location type, people count) stays null / empty / UNKNOWN, never guessed, so the engine abstains from what
 * needs it. Only a SUBMITTED report counts as user-confirmed. The reporter and injured people are not copied. See `doc/KNOWLEDGE_GRAPH.md`.
 */
object KnowledgeGraphInput {
    /** [finalSeverity] overrides the report's `severity_level` (the app's policies already ran, so normally the same). */
    fun fromReport(report: IncidentReport, rules: NormalizationRules, finalSeverity: String? = null): ValidatedIncident {
        fun text(f: IncidentField) = report.fields[f.key]?.value?.asTextOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val appType = text(IncidentField.INCIDENT_TYPE)
        val description = text(IncidentField.INCIDENT_DESCRIPTION)
        val place = text(IncidentField.INCIDENT_LOCATION)
        val actionsTaken = text(IncidentField.IMMEDIATE_ACTIONS_TAKEN)
        val all = normalize(listOfNotNull(appType, description, place).joinToString(" "))
        val placeNorm = normalize(place.orEmpty())
        val descNorm = normalize(description.orEmpty())

        val type = rules.incidentTypes.firstOrNull { r ->
            val byAppType = appType != null && r.appTypes.any { it.equals(appType, ignoreCase = true) }
            val hasKeywords = r.anyKeywords.isNotEmpty() || r.allKeywords.isNotEmpty()
            val byKeywords = hasKeywords && (r.anyKeywords.isEmpty() || r.anyKeywords.any { has(all, it) }) && r.allKeywords.all { has(all, it) }
            byAppType || byKeywords
        }?.code
        val locationType = firstCode(rules.locationTypes, placeNorm) ?: firstCode(rules.locationTypes, descNorm)
        val assets = allCodes(rules.assets, all)
        val hazards = allCodes(rules.hazards, all)
        val effects = allCodes(rules.operationalEffects, all)
        val building = firstCode(rules.buildings, placeNorm)

        val stillActive = report.fields[IncidentField.HAZARD_STILL_ACTIVE.key]?.value?.asBooleanOrNull() == true
        val statusText = normalize(listOfNotNull(description, actionsTaken).joinToString(" "))
        val status = when {
            stillActive -> IncidentStatus.ACTIVE
            else -> rules.statuses.firstOrNull { r -> r.anyKeywords.any { has(statusText, it) } }?.status ?: IncidentStatus.UNKNOWN
        }

        val outcome = report.fields[IncidentField.INJURY_OUTCOME.key]?.value?.asTextOrNull()
        val injuryField = report.fields[IncidentField.INJURY.key]?.value?.asBooleanOrNull()
        val injury = when {
            injuryField != null -> injuryField
            outcome.isNullOrBlank() -> null
            else -> InjuryOutcome.isInjury(outcome)
        }
        val injuredNamed = report.fields[IncidentField.PERSONS_INVOLVED.key]?.value?.asStringList().orEmpty().count { DialogueFields.isInjured(it) }

        val severity = KgSeverity.parse(finalSeverity ?: text(IncidentField.SEVERITY_LEVEL))
        val keyFields = listOf(IncidentField.INCIDENT_TYPE, IncidentField.INCIDENT_LOCATION, IncidentField.INCIDENT_DESCRIPTION)
        val confidences = keyFields.map { f -> report.fields[f.key]?.takeIf { !it.value.isEmptyValue() }?.let { if (it.source == FieldSource.USER_EDIT) 1.0 else it.confidence } ?: 0.0 }
        return ValidatedIncident(
            incidentId = report.reportId, type = type, severity = severity,
            urgency = report.dialogue.mode.takeIf { report.dialogue.emergency || it != "PLAIN" },
            status = status, locationId = building, locationType = locationType, affectedAssets = assets, activeHazards = hazards,
            peopleAffected = injuredNamed.takeIf { it > 0 }, operationalEffects = effects, evidenceConfidence = Math.round(confidences.average() * 100) / 100.0,
            timestamp = text(IncidentField.DATE_TIME) ?: report.createdAt, userConfirmed = report.status == IncidentReport.STATUS_SUBMITTED,
            injury = injury, locationText = place,
        )
    }

    /** The history record of [report]: type, date, place keys and assets, nothing personal. */
    fun historic(report: IncidentReport, rules: NormalizationRules): HistoricIncident {
        val v = fromReport(report, rules)
        val place = normalize(v.locationText.orEmpty())
        val floor = Regex("\\b(ground|first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th|basement)\\s+floor\\b").find(place)?.value
        return HistoricIncident(
            incidentId = report.reportId, type = v.type, createdAt = v.timestamp.takeIf { runCatching { java.time.OffsetDateTime.parse(it) }.isSuccess } ?: report.createdAt,
            locationKey = placeKey(place), zoneKey = if (v.locationId != null && floor != null) "${v.locationId}|$floor" else null,
            buildingKey = v.locationId, assets = v.affectedAssets,
        )
    }

    /** The place text reduced to its words (lower case, no filler), so that "KRB washroom, 3rd floor" and "krb washroom 3rd floor" match. */
    fun placeKey(normalisedPlace: String): String? =
        normalisedPlace.split(' ').filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ").takeIf { it.isNotEmpty() }

    private val FILLER = setOf("the", "a", "an", "in", "at", "near", "on", "of", "by", "next", "to", "outside", "inside", "front")

    /** Lower case, letters and digits only, single spaces. */
    fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun has(normalisedText: String, keyword: String): Boolean {
        val k = normalize(keyword)
        if (k.isEmpty()) return false
        return " $normalisedText ".contains(" $k ")
    }

    private fun firstCode(rules: List<CodeRule>, normalisedText: String): String? =
        if (normalisedText.isEmpty()) null else rules.firstOrNull { r -> r.anyKeywords.any { has(normalisedText, it) } }?.code

    private fun allCodes(rules: List<CodeRule>, normalisedText: String): List<String> =
        rules.filter { r -> r.anyKeywords.any { has(normalisedText, it) } }.map { it.code }.distinct()
}
