package com.example.test_app.knowledgegraph.history

import com.example.test_app.knowledgegraph.model.HistoryCitation
import com.example.test_app.knowledgegraph.model.NodeType
import com.example.test_app.knowledgegraph.repository.GraphIndex
import com.example.test_app.knowledgegraph.rules.Instants
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * One earlier (or the current) incident as the detector sees it: ids, dates and place keys only. No reporter, no injured person, no
 * medical history, no protected characteristics (spec section 15). [locationKey] is the normalised place text, [zoneKey] a building plus
 * floor key and [buildingKey] a building id; any of them may be null when the report does not say.
 */
data class HistoricIncident(
    val incidentId: String,
    val type: String?,
    val createdAt: String,
    val locationKey: String? = null,
    val zoneKey: String? = null,
    val buildingKey: String? = null,
    val assets: List<String> = emptyList(),
)

/**
 * Spec section 15: deterministic recurrence queries over saved reports. A `HISTORICAL_PATTERN` node of the graph (data, so thresholds
 * are editable) says: incident types, scope, window in days, threshold (and an hour window for time clusters):
 *  - `LOCATION`, `ZONE`, `BUILDING`: the same type at the same place key within the window (spec: "same room/zone/building within N days");
 *  - `ASSET`: the same asset type at the same place key (repeated failures of the same kind of asset there);
 *  - `TIME_CLUSTER`: the same type in the same building within [hourWindow] hours of the current time of day.
 * A pattern fires when the count (the current incident included) reaches the threshold; the citation lists every supporting incident id
 * and date. Not implemented (no data in the reports): same utility branch, repeated incomplete corrective action (spec section 15).
 */
class HistoryPatternDetector(private val graph: () -> GraphIndex) {
    fun detect(current: HistoricIncident, history: List<HistoricIncident>): List<HistoryCitation> {
        val currentTime = Instants.parse(current.createdAt) ?: return emptyList()
        val currentType = current.type?.uppercase() ?: return emptyList()
        val everything = (history.filter { it.incidentId != current.incidentId } + current).distinctBy { it.incidentId }
        val out = mutableListOf<HistoryCitation>()
        for (pattern in graph().nodesOfType(NodeType.HISTORICAL_PATTERN).filter { it.active }) {
            val types = pattern.strings("incidentTypes").map { it.uppercase() }.toSet()
            val window = pattern.int("windowDays") ?: continue
            val threshold = pattern.int("threshold") ?: continue
            if (currentType !in types) continue
            val scope = pattern.str("scope")?.uppercase() ?: continue
            val since = currentTime.minus(Duration.ofDays(window.toLong()))
            val supporting = everything.filter { h ->
                val t = Instants.parse(h.createdAt) ?: return@filter false
                h.type?.uppercase() in types && !t.isBefore(since) && !t.isAfter(currentTime) && sameScope(scope, pattern.int("hourWindow"), current, h, t, currentTime)
            }.sortedBy { Instants.parse(it.createdAt) }
            if (supporting.size >= threshold && supporting.any { it.incidentId == current.incidentId }) {
                out += HistoryCitation(
                    patternId = pattern.id, description = pattern.str("description") ?: pattern.name, incidentIds = supporting.map { it.incidentId },
                    dates = supporting.map { (Instants.parse(it.createdAt) ?: currentTime).atOffset(ZoneOffset.UTC).toLocalDate().toString() }, count = supporting.size, windowDays = window,
                )
            }
        }
        return out
    }

    private fun sameScope(scope: String, hourWindow: Int?, current: HistoricIncident, other: HistoricIncident, otherTime: Instant, currentTime: Instant): Boolean = when (scope) {
        "LOCATION" -> current.locationKey != null && current.locationKey == other.locationKey
        "ZONE" -> current.zoneKey != null && current.zoneKey == other.zoneKey
        "BUILDING" -> current.buildingKey != null && current.buildingKey == other.buildingKey
        "ASSET" -> current.locationKey != null && current.locationKey == other.locationKey && current.assets.any { a -> other.assets.any { it.equals(a, true) } }
        "TIME_CLUSTER" -> current.buildingKey != null && current.buildingKey == other.buildingKey && hourWindow != null && hoursApart(otherTime, currentTime) <= hourWindow
        else -> false
    }

    private fun hoursApart(a: Instant, b: Instant): Int {
        fun minutesOfDay(i: Instant) = i.atOffset(ZoneOffset.UTC).let { it.hour * 60 + it.minute }
        val diff = Math.abs(minutesOfDay(a) - minutesOfDay(b))
        return Math.min(diff, 24 * 60 - diff) / 60
    }
}
