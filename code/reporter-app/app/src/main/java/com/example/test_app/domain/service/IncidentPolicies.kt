package com.example.test_app.domain.service

import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InjuryOutcome
import com.example.test_app.domain.model.asBooleanOrNull
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.isEmptyValue
import com.example.test_app.domain.model.textValue
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Real (non-mock) app-side rules that fill fields the user is never asked about. Run after every extraction
 * pass and after every Review-form edit. See `doc/DATA_SCHEMA.md` section "App-filled fields".
 *
 * 1. date_time empty -> now (confidence 0.7; the read-back TTS lets the user correct it).
 * 2. incident_location empty and GPS available -> GPS coordinates (confidence 0.5: a backup, so it is still asked).
 * 3. severity_level (unless the user set it): `max(model hint, floors)` by [SeverityPolicy] (injury/hazard table, URGENT mode,
 *    escalation cues, fire / gas / electrical types). Never lowered by a later pass; a user-edited value is never touched.
 */
object IncidentPolicies {
    const val DEFAULTED_CONFIDENCE = 0.7
    const val GPS_CONFIDENCE = 0.5
    const val SUGGESTED_SEVERITY_CONFIDENCE = 0.75

    /**
     * @param cues word lists of `severity_cues.json` (`DialogueAssets.severityCues`).
     * @param extraTexts user words not stored in the report (safety-check reply, answers that were not extracted); the report's
     * own transcript and the answers in `clarification.history` are always used.
     */
    fun apply(
        report: IncidentReport,
        now: OffsetDateTime = OffsetDateTime.now(),
        cues: SeverityCues,
        extraTexts: List<String> = emptyList(),
    ): IncidentReport {
        var out = report
        fun empty(f: IncidentField) = out.fields[f.key]?.value?.isEmptyValue() ?: true

        if (empty(IncidentField.DATE_TIME)) {
            out = out.copy(fields = out.fields + (IncidentField.DATE_TIME.key to
                FieldEntry(textValue(now.truncatedTo(ChronoUnit.SECONDS).toString()), DEFAULTED_CONFIDENCE, FieldSource.AI)))
        }
        val gps = out.device?.gps
        if (empty(IncidentField.INCIDENT_LOCATION) && gps != null) {
            val text = String.format(Locale.US, "GPS %.5f, %.5f", gps.latitude, gps.longitude)
            out = out.copy(fields = out.fields + (IncidentField.INCIDENT_LOCATION.key to
                FieldEntry(textValue(text), GPS_CONFIDENCE, FieldSource.AI)))
        }
        val current = out.fields[IncidentField.SEVERITY_LEVEL.key]
        if (current == null || current.source != FieldSource.USER_EDIT) {
            val hint = current?.value?.asTextOrNull()?.takeIf { SeverityPolicy.max(it, null) != null }
            val decided = SeverityPolicy.decide(
                SeverityInput(
                    hint = hint,
                    urgent = out.dialogue.emergency || out.dialogue.mode == "URGENT",
                    texts = listOf(out.input.transcript) + out.clarification.history.map { it.answer } + extraTexts,
                    injury = out.fields[IncidentField.INJURY.key]?.value?.asBooleanOrNull(),
                    injuryOutcome = out.fields[IncidentField.INJURY_OUTCOME.key]?.value?.asTextOrNull(),
                    hazardActive = out.fields[IncidentField.HAZARD_STILL_ACTIVE.key]?.value?.asBooleanOrNull() == true,
                    incidentType = out.fields[IncidentField.INCIDENT_TYPE.key]?.value?.asTextOrNull(),
                ),
                cues,
            )
            val entry = when {
                decided == null -> FieldEntry()
                current != null && decided.equals(hint, ignoreCase = true) -> current.copy(value = textValue(decided))
                else -> FieldEntry(textValue(decided), SUGGESTED_SEVERITY_CONFIDENCE, FieldSource.AI)
            }
            out = out.copy(fields = out.fields + (IncidentField.SEVERITY_LEVEL.key to entry))
        }
        return out
    }

    /** Null while the injury outcome is unknown. */
    fun suggestSeverity(injury: String?, hazardActive: Boolean): String? = when (injury) {
        InjuryOutcome.FATAL -> "Critical"
        InjuryOutcome.SERIOUS -> if (hazardActive) "Critical" else "High"
        InjuryOutcome.MINOR -> if (hazardActive) "High" else "Medium"
        InjuryOutcome.NONE -> if (hazardActive) "Medium" else "Low"
        else -> null
    }
}
