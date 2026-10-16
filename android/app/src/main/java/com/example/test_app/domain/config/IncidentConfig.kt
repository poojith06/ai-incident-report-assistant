package com.example.test_app.domain.config

import com.example.test_app.domain.model.IncidentField

/**
 * Single source of truth for validation and questioning rules (see `doc/DATA_SCHEMA.md`).
 * UI code MUST read mandatory fields from the report's `validation.mandatory_fields`, never hardcode them.
 */
data class IncidentConfig(
    /** Required fields. Missing or low-confidence ones are asked about. */
    val mandatoryFields: List<String> = DEFAULT_MANDATORY_FIELDS,
    /**
     * The ONLY fields the assistant may ask about, in priority order (injury is NOT asked and not mandatory, ADR-050: it is filled
     * when the narration says it and editable on Review). Everything else (date/time, equipment, witnesses, type, severity, follow-up) is never asked.
     */
    val askOrder: List<String> = DEFAULT_ASK_ORDER,
    /** Fields whose confidence is below this (and which are filled in) are "low confidence". */
    val confidenceThreshold: Double = 0.6,
    /** Maximum number of clarification rounds before jumping to the form. */
    val maxClarificationRounds: Int = 3,
    /** Recordings shorter than this are rejected as "too short". */
    val minRecordingMs: Long = 1000,
) {
    companion object {
        val DEFAULT_MANDATORY_FIELDS = listOf(
            IncidentField.INCIDENT_DESCRIPTION.key,
            IncidentField.INCIDENT_LOCATION.key,
            IncidentField.DATE_TIME.key,
        )

        val DEFAULT_ASK_ORDER = listOf(
            IncidentField.INCIDENT_LOCATION.key,
            IncidentField.INCIDENT_DESCRIPTION.key,
            IncidentField.PERSONS_INVOLVED.key,
            IncidentField.IMMEDIATE_ACTIONS_TAKEN.key,
        )
    }
}
