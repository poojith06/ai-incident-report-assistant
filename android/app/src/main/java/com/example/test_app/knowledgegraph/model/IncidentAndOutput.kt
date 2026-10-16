package com.example.test_app.knowledgegraph.model

import kotlinx.serialization.Serializable

/** Who the engine output is for (ADR-055). Only the approved, unexpired, verified-source guidance reaches [REPORTER]. */
enum class Audience {
    /** Ordinary users: approved recommendations on approved, unexpired sources only. */
    REPORTER,

    /** Administrators previewing a pack: draft, reviewed and unverified-source guidance too, each labelled as an unverified draft. */
    ADMIN_PREVIEW,
}

/**
 * Spec section 2 input: a verified incident normalised onto the graph vocabulary. Unknown facts are null / empty (never guessed): the
 * engine then abstains from what needs them and asks one question. Built by `KnowledgeGraphInput` from an `IncidentReport`.
 * [injury] is true when someone is reported hurt; the reporter's or the injured person's identity is deliberately NOT part of this class.
 */
@Serializable
data class ValidatedIncident(
    val incidentId: String,
    /** A graph incident type CODE (for example `COMPUTER_FIRE`), or null when the report could not be mapped. */
    val type: String? = null,
    val severity: KgSeverity? = null,
    val urgency: String? = null,
    val status: IncidentStatus = IncidentStatus.UNKNOWN,
    /** A graph location id (a BUILDING node id when the text names one), or null. */
    val locationId: String? = null,
    /** A graph location type CODE (for example `COMPUTER_LAB`), or null when unknown. */
    val locationType: String? = null,
    val affectedAssets: List<String> = emptyList(),
    val activeHazards: List<String> = emptyList(),
    val peopleAffected: Int? = null,
    val operationalEffects: List<String> = emptyList(),
    val evidenceConfidence: Double = 0.0,
    val timestamp: String = "",
    val userConfirmed: Boolean = false,
    val injury: Boolean? = null,
    /** The place as the reporter said it (may name a hostel room: never exported for residential places). */
    val locationText: String? = null,
)

enum class Likelihood { POSSIBLE, LIKELY, CONFIRMED }

/** How the shown text was produced. */
enum class RenderMode { TEMPLATE, LLM, FALLBACK }

/** One suggested action (spec section 12 output; [id] is the recommendation id, always a node of the graph). */
@Serializable
data class RecommendationItem(
    val id: String,
    val actionId: String,
    val title: String,
    val phase: Phase,
    val rank: Int,
    val score: Double,
    val sourceId: String,
    val sourceLabel: String? = null,
    /** The traversal paths that produced it, e.g. `incident-type:x -TRIGGERS_ACTION-> action:y`. */
    val provenance: List<String>,
    val canonicalText: String,
    val renderedText: String,
    val renderMode: RenderMode = RenderMode.TEMPLATE,
    val ownerTeamId: String? = null,
    val ownerTeamName: String? = null,
    val requiresHumanApproval: Boolean = false,
    /** Things the reporter must never do for this action (spec section 17), shown as a caution. */
    val forbiddenForReporter: List<String> = emptyList(),
    /** true when shown only because the audience is [Audience.ADMIN_PREVIEW] (draft, reviewed or unverified source). */
    val unverifiedDraft: Boolean = false,
    /** Cites the supporting incidents when a history pattern triggered it. */
    val history: List<HistoryCitation> = emptyList(),
)

/** A possible consequence; never stated as certain unless observed (spec section 11). */
@Serializable
data class ConsequenceItem(
    val id: String,
    val name: String,
    val text: String,
    val consequenceType: String,
    val likelihood: Likelihood,
    val provenance: List<String>,
)

@Serializable
data class TemporaryArrangementItem(
    val id: String,
    val title: String,
    val text: String,
    val ownerTeamId: String? = null,
    val provenance: List<String>,
    val sourceId: String? = null,
    val unverifiedDraft: Boolean = false,
)

/** A responsible team (a role label). [contactNumber] is set ONLY from a verified, enabled, unexpired contact (spec section 3). */
@Serializable
data class TeamItem(val id: String, val name: String, val role: String, val verified: Boolean, val contactNumber: String? = null)

@Serializable
data class HistoryCitation(val patternId: String, val description: String, val incidentIds: List<String>, val dates: List<String>, val count: Int, val windowDays: Int)

/** Why a candidate was removed (spec section 13: every suppression is logged). */
@Serializable
data class Suppression(val candidateId: String, val kind: String, val reason: String, val path: String? = null)

@Serializable
data class UnresolvedQuestion(val id: String, val text: String)

/** Spec section 2 output. */
@Serializable
data class RecommendationSet(
    val incidentId: String,
    val audience: Audience,
    val graphVersion: Int,
    /** true when the engine produced nothing because required facts are missing (the unresolved question says which). */
    val abstained: Boolean = false,
    val immediateActions: List<RecommendationItem> = emptyList(),
    val consequences: List<ConsequenceItem> = emptyList(),
    val operationalSteps: List<RecommendationItem> = emptyList(),
    val recoverySteps: List<RecommendationItem> = emptyList(),
    val temporaryArrangements: List<TemporaryArrangementItem> = emptyList(),
    val preventiveActions: List<RecommendationItem> = emptyList(),
    val responsibleTeams: List<TeamItem> = emptyList(),
    val unresolvedQuestions: List<UnresolvedQuestion> = emptyList(),
    val recommendationIds: List<String> = emptyList(),
    val suppressed: List<Suppression> = emptyList(),
    val historyCitations: List<HistoryCitation> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** The medical footer (spec section 3) for any injury case, else null. */
    val medicalFooter: String? = null,
    /** "Demo guidance - pending IIIT-H admin approval" when a shown item relies on the demo seed source; else null. */
    val guidanceLabel: String? = null,
) {
    val allRecommendations: List<RecommendationItem>
        get() = immediateActions + operationalSteps + recoverySteps + preventiveActions

    val isEmpty: Boolean
        get() = allRecommendations.isEmpty() && consequences.isEmpty() && temporaryArrangements.isEmpty()
}

/** Spec section 8 `kg_recommendation_log`: the audit record of one engine run (written to `files/kg/audit/<incidentId>.json`). */
@Serializable
data class RecommendationLog(
    val id: String,
    val incidentId: String,
    val graphVersion: Int,
    val audience: Audience,
    val createdAt: String,
    val userConfirmed: Boolean,
    val abstained: Boolean,
    val recommendationIds: List<String>,
    val shown: List<ShownEntry>,
    val suppressed: List<Suppression>,
    val unresolvedQuestions: List<UnresolvedQuestion>,
    val warnings: List<String>,
    val historyCitations: List<HistoryCitation>,
    /** Facts the engine used (no identities): the audit must be reproducible from these and the graph version. */
    val inputFacts: InputFacts,
)

@Serializable
data class ShownEntry(val id: String, val kind: String, val phase: String? = null, val rank: Int? = null, val score: Double? = null, val sourceId: String? = null, val provenance: List<String> = emptyList(), val renderMode: String? = null)

@Serializable
data class InputFacts(
    val type: String?,
    val severity: String?,
    val status: String,
    val locationType: String?,
    val affectedAssets: List<String>,
    val activeHazards: List<String>,
    val peopleAffected: Int?,
    val operationalEffects: List<String>,
    val injury: Boolean?,
    val evidenceConfidence: Double,
)
