package com.example.test_app.admin

import com.example.test_app.knowledgegraph.engine.EngineConfig
import com.example.test_app.knowledgegraph.engine.PhaseLimits
import com.example.test_app.knowledgegraph.engine.RankingWeights
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The knowledge-graph engine settings an admin may change (Admin > Settings): abstention threshold, output limits and ranking weights
 * (spec section 12: "configurable, not learned truth"). Serialisable, validated, convertible to [EngineConfig]; persisted as JSON by the
 * app and reset to the defaults of [EngineConfig] on demand.
 */
@Serializable
data class AnalysisSettings(
    val abstainBelowEvidence: Double = DEFAULT.abstainBelowEvidence,
    val maxConsequences: Int = DEFAULT.maxConsequences,
    val immediate: Int = DEFAULT.limits.immediate,
    val operational: Int = DEFAULT.limits.operational,
    val recovery: Int = DEFAULT.limits.recovery,
    val temporaryArrangements: Int = DEFAULT.limits.temporaryArrangements,
    val preventive: Int = DEFAULT.limits.preventive,
    val wGraphPriority: Double = DEFAULT.weights.graphPriority,
    val wSeverity: Double = DEFAULT.weights.severityRelevance,
    val wLocation: Double = DEFAULT.weights.locationMatch,
    val wAsset: Double = DEFAULT.weights.assetMatch,
    val wRecurrence: Double = DEFAULT.weights.recurrence,
    val wEvidence: Double = DEFAULT.weights.evidenceCompleteness,
) {
    fun toEngineConfig(): EngineConfig = EngineConfig(
        weights = RankingWeights(wGraphPriority, wSeverity, wLocation, wAsset, wRecurrence, wEvidence),
        limits = PhaseLimits(immediate, operational, recovery, temporaryArrangements, preventive, DEFAULT.limits.questions),
        maxConsequences = maxConsequences, abstainBelowEvidence = abstainBelowEvidence,
    )

    /** Problems that make these settings unusable (empty = valid): thresholds in 0..1, limits 0..10, weights 0..1. */
    fun problems(): List<String> = buildList {
        if (abstainBelowEvidence !in 0.0..1.0) add("The evidence threshold must be between 0 and 1.")
        listOf("immediate" to immediate, "operational" to operational, "recovery" to recovery, "temporary arrangements" to temporaryArrangements, "preventive" to preventive, "consequences" to maxConsequences)
            .forEach { (n, v) -> if (v !in 0..10) add("The limit for $n must be between 0 and 10.") }
        listOf(wGraphPriority, wSeverity, wLocation, wAsset, wRecurrence, wEvidence).forEach { if (it !in 0.0..1.0 || it.isNaN()) add("Every weight must be between 0 and 1.") }
    }.distinct()

    companion object {
        val DEFAULT: EngineConfig = EngineConfig()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromEngineConfig(c: EngineConfig) = AnalysisSettings(
            c.abstainBelowEvidence, c.maxConsequences, c.limits.immediate, c.limits.operational, c.limits.recovery, c.limits.temporaryArrangements, c.limits.preventive,
            c.weights.graphPriority, c.weights.severityRelevance, c.weights.locationMatch, c.weights.assetMatch, c.weights.recurrence, c.weights.evidenceCompleteness,
        )

        fun encode(s: AnalysisSettings): String = json.encodeToString(serializer(), s)

        /** The stored settings, or the defaults for null, damaged or invalid JSON (a bad value never breaks the engine). */
        fun decode(text: String?): AnalysisSettings {
            val s = text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: return AnalysisSettings()
            return if (s.problems().isEmpty()) s else AnalysisSettings()
        }
    }
}
