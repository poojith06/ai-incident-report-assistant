package com.example.test_app.admin

import com.example.test_app.knowledgegraph.model.GraphPack
import com.example.test_app.knowledgegraph.model.IncidentStatus
import com.example.test_app.knowledgegraph.model.KgSeverity
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.model.RecommendationSet
import com.example.test_app.knowledgegraph.model.ValidatedIncident
import com.example.test_app.knowledgegraph.admin.graphview.PathHighlight

/** A sample incident the admin builds with pickers to preview the engine (Knowledge editor > Preview). Codes come from the active pack. */
data class PreviewSpec(
    val type: String? = null,
    val locationType: String? = null,
    val severity: KgSeverity? = KgSeverity.HIGH,
    val status: IncidentStatus = IncidentStatus.UNKNOWN,
    val assets: List<String> = emptyList(),
    val hazards: List<String> = emptyList(),
    val injury: Boolean? = false,
) {
    fun toIncident(nowIso: String): ValidatedIncident = ValidatedIncident(
        incidentId = "PREVIEW", type = type, severity = severity, status = status, locationType = locationType, affectedAssets = assets, activeHazards = hazards,
        evidenceConfidence = 0.9, timestamp = nowIso, userConfirmed = true, injury = injury, locationText = null,
    )
}

/** Side-by-side result of a preview: what ordinary users would see and what the admin preview adds, plus the path highlight of each. */
data class PreviewResult(
    val incident: ValidatedIncident,
    val reporter: RecommendationSet,
    val admin: RecommendationSet,
    val reporterPath: PathHighlight,
    val adminPath: PathHighlight,
)

/** The codes an admin may pick in the preview (read from the pack, so new scenarios appear without code). */
data class PreviewChoices(val incidentTypes: List<String>, val locationTypes: List<String>, val assets: List<String>, val hazards: List<String>) {
    companion object {
        fun from(pack: GraphPack): PreviewChoices {
            fun codes(t: NodeType) = pack.nodes.filter { it.type == t && it.active }.mapNotNull { it.str("code") }.distinct().sorted()
            return PreviewChoices(codes(NodeType.INCIDENT_TYPE), codes(NodeType.LOCATION_TYPE), codes(NodeType.ASSET_TYPE), codes(NodeType.HAZARD))
        }
    }
}
