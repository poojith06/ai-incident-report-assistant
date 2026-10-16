package com.example.test_app.knowledgegraph.engine

/** Spec section 12 ranking weights (configurable, not learned truth). */
data class RankingWeights(
    val graphPriority: Double = 0.30,
    val severityRelevance: Double = 0.20,
    val locationMatch: Double = 0.15,
    val assetMatch: Double = 0.15,
    val recurrence: Double = 0.10,
    val evidenceCompleteness: Double = 0.10,
)

/** Spec section 12 output limits. */
data class PhaseLimits(
    val immediate: Int = 3,
    val operational: Int = 3,
    val recovery: Int = 3,
    val temporaryArrangements: Int = 2,
    val preventive: Int = 2,
    val questions: Int = 1,
)

/**
 * Engine settings. [abstainBelowEvidence]: an incident whose evidence confidence is lower than this gets no guidance, only the
 * question "can you confirm the details". [maxConsequences] caps the consequence list (spec section 12 gives no limit).
 */
data class EngineConfig(
    val weights: RankingWeights = RankingWeights(),
    val limits: PhaseLimits = PhaseLimits(),
    val maxConsequences: Int = 4,
    val abstainBelowEvidence: Double = 0.3,
)
