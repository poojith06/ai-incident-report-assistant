package com.example.test_app.dialogue

import com.example.test_app.domain.model.IncidentField

/**
 * Tone of the follow-up dialogue (Spec v2 section 3). Declaration order is the escalation order
 * `PLAIN < CARE < URGENT`; the mode may only go up within one report. See `doc/DIALOGUE.md`.
 */
enum class DialogueMode(
    /** Maximum number of follow-up questions in this mode. */
    val maxQuestions: Int,
    /** Style hint for the rephrase prompt (Spec v2 section 8.2). */
    val styleDescription: String,
) {
    PLAIN(2, "short and neutral"),
    CARE(3, "warm and gentle; the user may be hurt or upset; make clear they can skip"),
    URGENT(2, "calm, very short, focused on safety");

    companion object {
        fun max(a: DialogueMode, b: DialogueMode): DialogueMode = if (a >= b) a else b
        fun fromName(name: String?): DialogueMode = entries.firstOrNull { it.name == name } ?: PLAIN
    }
}

/**
 * Dialogue-level field keys (Spec v2 section 4.2) and their mapping to report field keys
 * (build prompt section 3). `injured_person` and `people_involved` both live in `persons_involved`;
 * injured people carry the [INJURED_SUFFIX] marker.
 */
object DialogueFields {
    const val INJURED_PERSON = "injured_person"
    const val INJURY_TYPE = "injury_type"
    const val LOCATION = "location"
    const val DESCRIPTION = "description"
    const val TIME = "time"
    const val IMMEDIATE_ACTIONS = "immediate_actions"
    const val PEOPLE_INVOLVED = "people_involved"

    /**
     * Priority order of the planner (ADR-048/050; supersedes Spec v2 section 4.2): location, description, time, immediate
     * actions, people involved, then the injured-person and injury-type follow-ups. `injury` is NOT a question any more (ADR-050): it is
     * filled when the narration says it and editable on Review. The URGENT safety check precedes all of them.
     */
    val PRIORITY = listOf(
        LOCATION, DESCRIPTION, TIME, IMMEDIATE_ACTIONS, PEOPLE_INVOLVED, INJURED_PERSON, INJURY_TYPE,
    )

    /** Marker appended to a `persons_involved` entry for someone who was hurt, e.g. `"Reporter (injured)"`. */
    const val INJURED_SUFFIX = " (injured)"

    fun isInjured(entry: String): Boolean = entry.endsWith(INJURED_SUFFIX, ignoreCase = true)

    /** Report field key (`IncidentField.key`) that stores [dialogueField]. */
    fun reportKey(dialogueField: String): String = when (dialogueField) {
        INJURED_PERSON, PEOPLE_INVOLVED -> IncidentField.PERSONS_INVOLVED.key
        INJURY_TYPE -> IncidentField.INJURY_TYPE.key
        LOCATION -> IncidentField.INCIDENT_LOCATION.key
        DESCRIPTION -> IncidentField.INCIDENT_DESCRIPTION.key
        TIME -> IncidentField.DATE_TIME.key
        IMMEDIATE_ACTIONS -> IncidentField.IMMEDIATE_ACTIONS_TAKEN.key
        else -> error("Unknown dialogue field: $dialogueField")
    }
}
