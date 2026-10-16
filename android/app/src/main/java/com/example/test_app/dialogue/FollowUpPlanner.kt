package com.example.test_app.dialogue

/**
 * What the planner needs to know about the report so far (all derived by [DialogueForm]; the planner itself has no
 * dependency on the report model).
 */
data class PlannerState(
    /** Dialogue field keys ([DialogueFields]) that already hold a value. Never asked (except a vague location, see [locationVague]). */
    val filled: Set<String> = emptySet(),
    /** Extracted `injury`, null while unknown. */
    val injury: Boolean? = null,
    /** Severity (by the deterministic policy) is High or Critical. */
    val severityAtLeastHigh: Boolean = false,
    /** `hazard_still_active == true`. Informational: it no longer triggers a question on its own. */
    val hazardOngoing: Boolean = false,
    /** Harassment / threat / assault style incident. */
    val personalIncident: Boolean = false,
    /** The first description is thin (Spec v2 section 4.3). */
    val thinDescription: Boolean = false,
    /** The transcript implies the past (Spec v2 section 4.4). */
    val pastCue: Boolean = false,
    /** A location was given but it is too vague to route the report ("near", "here", "the building"): asked once like a missing one. */
    val locationVague: Boolean = false,
)

/**
 * Picks the next field to ask: the first field in [DialogueFields.PRIORITY] that is eligible, not already asked, and
 * whose condition holds. A question is eligible only when its answer changes the report materially or completes a required
 * field the app cannot infer (relevance rules below). Deterministic, pure Kotlin, no Android imports.
 *
 * | Priority | Field | Asked when |
 * |---|---|---|
 * | 1 | `location` | missing or vague (required for routing) |
 * | 2 | `description` | the first description is thin |
 * | 3 | `time` | a past cue and no time |
 * | 4 | `immediate_actions` | severity High/Critical and no action mentioned |
 * | 5 | `people_involved` | personal incident (harassment, threat, assault) |
 * | 6 | `injured_person` | the narration already says injury true and nobody injured is named |
 * | 7 | `injury_type` | the narration already says injury true, CARE mode, no type |
 *
 * `injury` itself is never asked (ADR-050): not mandatory, filled when said, set on Review.
 *
 * The URGENT safety check comes before all of them (spoken by `DialogueManager`). The total number of questions per report
 * (`DialogueConfig.maxQuestionsTotal`, the safety check included) and the per-mode cap are applied by the caller via
 * [capReached]. [explain] gives the reason for every field (logged for audit). See `doc/DIALOGUE.md`.
 */
class FollowUpPlanner {

    /** The next field to ask, or null when nothing is left. Never returns a member of [askedFields]. */
    fun next(state: PlannerState, mode: DialogueMode, askedFields: Set<String>): String? =
        missing(state, mode).firstOrNull { it !in askedFields }

    /** Every field that would be asked if nothing had been asked yet, in priority order (ignores asked fields and the cap). */
    fun missing(state: PlannerState, mode: DialogueMode): List<String> = DialogueFields.PRIORITY.filter { eligible(it, state, mode) }

    /** Fields still to ask, in order: [missing] minus [askedFields]. */
    fun remaining(state: PlannerState, mode: DialogueMode, askedFields: Set<String>): List<String> =
        missing(state, mode).filter { it !in askedFields }

    /**
     * True once no further question may be asked: [askedFields] reached the cap of [mode], or the report's total of
     * [maxTotal] questions is used up. [otherQuestions] counts questions that are not planner fields (the URGENT safety check).
     */
    fun capReached(mode: DialogueMode, askedFields: Set<String>, otherQuestions: Int = 0, maxTotal: Int = Int.MAX_VALUE): Boolean =
        askedFields.size >= mode.maxQuestions || askedFields.size + otherQuestions >= maxTotal

    /** Why each field is (not) a candidate right now, for the audit log: `field -> "ask: ..."` or `"skip: ..."`. */
    fun explain(state: PlannerState, mode: DialogueMode, askedFields: Set<String>): Map<String, String> =
        DialogueFields.PRIORITY.associateWith { f ->
            when {
                f in askedFields -> "skip: already asked"
                eligible(f, state, mode) -> "ask: ${reason(f, state)}"
                else -> "skip: ${skipReason(f, state, mode)}"
            }
        }

    private fun eligible(field: String, s: PlannerState, mode: DialogueMode): Boolean {
        val notFilled = field !in s.filled
        return when (field) {
            DialogueFields.LOCATION -> notFilled || s.locationVague
            DialogueFields.DESCRIPTION -> s.thinDescription
            DialogueFields.TIME -> s.pastCue && notFilled
            DialogueFields.IMMEDIATE_ACTIONS -> s.severityAtLeastHigh && notFilled
            DialogueFields.PEOPLE_INVOLVED -> s.personalIncident && notFilled
            DialogueFields.INJURED_PERSON -> s.injury == true && notFilled
            DialogueFields.INJURY_TYPE -> s.injury == true && notFilled && mode == DialogueMode.CARE
            else -> false
        }
    }

    private fun reason(field: String, s: PlannerState): String = when (field) {
        DialogueFields.LOCATION -> if (DialogueFields.LOCATION in s.filled) "location too vague to route the report" else "location missing (required for routing)"
        DialogueFields.DESCRIPTION -> "description is thin"
        DialogueFields.TIME -> "past cue and no time given"
        DialogueFields.IMMEDIATE_ACTIONS -> "severity High/Critical and no action mentioned"
        DialogueFields.PEOPLE_INVOLVED -> "personal incident, people not named"
        DialogueFields.INJURED_PERSON -> "someone is hurt and not named"
        DialogueFields.INJURY_TYPE -> "someone is hurt, type unknown (CARE)"
        else -> "eligible"
    }

    private fun skipReason(field: String, s: PlannerState, mode: DialogueMode): String = when (field) {
        DialogueFields.LOCATION -> "location already given"
        DialogueFields.DESCRIPTION -> "description is not thin"
        DialogueFields.TIME -> if (DialogueFields.TIME in s.filled) "time already given" else "no past cue, time defaults to now"
        DialogueFields.IMMEDIATE_ACTIONS -> if (DialogueFields.IMMEDIATE_ACTIONS in s.filled) "action already mentioned" else "severity below High"
        DialogueFields.PEOPLE_INVOLVED -> if (DialogueFields.PEOPLE_INVOLVED in s.filled) "people already named" else "not a personal incident"
        DialogueFields.INJURED_PERSON -> if (s.injury != true) "nobody known to be hurt" else "injured person already named"
        DialogueFields.INJURY_TYPE -> when {
            s.injury != true -> "nobody known to be hurt"
            DialogueFields.INJURY_TYPE in s.filled -> "injury type already given"
            mode != DialogueMode.CARE -> "injury type is asked in CARE mode only"
            else -> "not needed"
        }
        else -> "not a planner field"
    }
}
