package com.example.test_app.knowledgegraph

import com.example.test_app.knowledgegraph.model.Audience
import com.example.test_app.knowledgegraph.model.ConsequenceItem
import com.example.test_app.knowledgegraph.model.HistoryCitation
import com.example.test_app.knowledgegraph.model.NormalizationRules
import com.example.test_app.knowledgegraph.model.Phase
import com.example.test_app.knowledgegraph.model.RecommendationSet
import com.example.test_app.knowledgegraph.model.RenderMode
import com.example.test_app.knowledgegraph.model.Suppression
import com.example.test_app.knowledgegraph.model.TeamItem
import com.example.test_app.knowledgegraph.model.TemporaryArrangementItem
import com.example.test_app.knowledgegraph.model.UnresolvedQuestion
import com.example.test_app.knowledgegraph.model.ValidatedIncident
import kotlinx.serialization.Serializable

/**
 * The hand-off contract to the future admin dashboard: ONE JSON document per analysed incident (`doc/ADMIN_DASHBOARD_CONTRACT.md`).
 * It carries the incident SUMMARY (graph facts only: no reporter, no injured person, and no exact place for residential locations such as
 * hostel rooms), the graph version, every recommendation with its provenance and source, what was suppressed and why, the unresolved
 * question, history citations and warnings. [schemaVersion] grows only with incompatible changes.
 */
@Serializable
data class IncidentAnalysisExport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportId: String,
    val createdAt: String,
    val graphVersion: Int,
    val audience: Audience,
    val incident: ExportIncident,
    val abstained: Boolean,
    val guidanceLabel: String?,
    val medicalFooter: String?,
    val recommendations: List<ExportRecommendation>,
    val consequences: List<ConsequenceItem>,
    val temporaryArrangements: List<TemporaryArrangementItem>,
    val responsibleTeams: List<TeamItem>,
    val suppressed: List<Suppression>,
    val unresolvedQuestions: List<UnresolvedQuestion>,
    val historyCitations: List<HistoryCitation>,
    val warnings: List<String>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class ExportIncident(
    val incidentId: String,
    val type: String?,
    val severity: String?,
    val status: String,
    val urgency: String?,
    val locationType: String?,
    /** The place as reported, or null when [locationRedacted] (residential locations are never exported with their exact place). */
    val locationLabel: String?,
    val locationRedacted: Boolean,
    val affectedAssets: List<String>,
    val activeHazards: List<String>,
    val injuryReported: Boolean?,
    val peopleAffected: Int?,
    val operationalEffects: List<String>,
    val evidenceConfidence: Double,
    val occurredAt: String,
)

@Serializable
data class ExportRecommendation(
    val id: String,
    val actionId: String,
    val title: String,
    val phase: Phase,
    val rank: Int,
    val score: Double,
    val sourceId: String,
    val sourceLabel: String?,
    val provenance: List<String>,
    val canonicalText: String,
    val renderedText: String,
    val renderMode: RenderMode,
    val ownerTeamId: String?,
    val ownerTeamName: String?,
    val requiresHumanApproval: Boolean,
    val unverifiedDraft: Boolean,
    val historyPatternIds: List<String>,
)

/** Builds the export document from an analysis run. */
object AnalysisExportBuilder {
    fun build(incident: ValidatedIncident, set: RecommendationSet, rules: NormalizationRules, createdAt: String): IncidentAnalysisExport {
        val residential = incident.locationType != null && rules.residentialLocationTypes.any { it.equals(incident.locationType, ignoreCase = true) }
        return IncidentAnalysisExport(
            exportId = "export:${incident.incidentId}:g${set.graphVersion}", createdAt = createdAt, graphVersion = set.graphVersion, audience = set.audience,
            incident = ExportIncident(
                incidentId = incident.incidentId, type = incident.type, severity = incident.severity?.name, status = incident.status.name, urgency = incident.urgency,
                locationType = incident.locationType, locationLabel = if (residential) null else incident.locationText, locationRedacted = residential,
                affectedAssets = incident.affectedAssets, activeHazards = incident.activeHazards, injuryReported = incident.injury, peopleAffected = incident.peopleAffected,
                operationalEffects = incident.operationalEffects, evidenceConfidence = incident.evidenceConfidence, occurredAt = incident.timestamp,
            ),
            abstained = set.abstained, guidanceLabel = set.guidanceLabel, medicalFooter = set.medicalFooter,
            recommendations = set.allRecommendations.map {
                ExportRecommendation(
                    it.id, it.actionId, it.title, it.phase, it.rank, it.score, it.sourceId, it.sourceLabel, it.provenance, it.canonicalText, it.renderedText, it.renderMode,
                    it.ownerTeamId, it.ownerTeamName, it.requiresHumanApproval, it.unverifiedDraft, it.history.map { h -> h.patternId },
                )
            },
            consequences = set.consequences, temporaryArrangements = set.temporaryArrangements, responsibleTeams = set.responsibleTeams, suppressed = set.suppressed,
            unresolvedQuestions = set.unresolvedQuestions, historyCitations = set.historyCitations, warnings = set.warnings,
        )
    }
}
