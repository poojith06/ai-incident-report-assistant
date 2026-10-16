package com.example.test_app.dialogue

import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InjuryOutcome
import com.example.test_app.domain.model.asBooleanOrNull
import com.example.test_app.domain.model.asStringList
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.isEmptyValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.service.SeverityCues
import com.example.test_app.domain.service.TextMatch
import com.example.test_app.domain.service.SeverityInput
import com.example.test_app.domain.service.SeverityPolicy

/** Reads the report the way the dialogue needs it and keeps `injury` and `injury_outcome` consistent. */
class DialogueForm(
    private val cues: AnswerCues,
    private val classifier: ModeClassifier,
    private val severityCues: SeverityCues,
    private val relevance: RelevanceCues,
) {

    /** `injury`, or derived from `injury_outcome` when only that is set (mock / Phase 1 extraction). */
    fun injury(report: IncidentReport): Boolean? {
        val direct = report.fields[IncidentField.INJURY.key]?.value
        if (direct != null && !direct.isEmptyValue()) direct.asBooleanOrNull()?.let { return it }
        val outcome = report.fields[IncidentField.INJURY_OUTCOME.key]?.value?.asTextOrNull()
        return if (outcome.isNullOrBlank()) null else InjuryOutcome.isInjury(outcome)
    }

    /**
     * Severity by the deterministic policy (`SeverityPolicy`: max of the model's hint and the floors), as the planner and the
     * mode classifier should see it. [urgent] = the dialogue is already URGENT; [texts] = everything the user said. Null while unknown.
     */
    fun severity(report: IncidentReport, urgent: Boolean, texts: List<String>): String? =
        SeverityPolicy.decide(
            SeverityInput(
                hint = report.fields[IncidentField.SEVERITY_LEVEL.key]?.value?.asTextOrNull()?.takeIf { it.isNotBlank() },
                urgent = urgent,
                texts = texts,
                injury = injury(report),
                injuryOutcome = report.fields[IncidentField.INJURY_OUTCOME.key]?.value?.asTextOrNull(),
                hazardActive = hazardOngoing(report),
                incidentType = report.fields[IncidentField.INCIDENT_TYPE.key]?.value?.asTextOrNull(),
            ),
            severityCues,
        )

    fun hazardOngoing(report: IncidentReport): Boolean =
        report.fields[IncidentField.HAZARD_STILL_ACTIVE.key]?.value?.asBooleanOrNull() == true

    /** Dialogue fields that hold a value. */
    fun filled(report: IncidentReport): Set<String> {
        fun has(f: IncidentField) = report.fields[f.key]?.value?.isEmptyValue() == false
        val persons = report.fields[IncidentField.PERSONS_INVOLVED.key]?.value?.asStringList().orEmpty()
        return buildSet {
            if (persons.any { DialogueFields.isInjured(it) }) add(DialogueFields.INJURED_PERSON)
            if (has(IncidentField.INJURY_TYPE)) add(DialogueFields.INJURY_TYPE)
            if (has(IncidentField.INCIDENT_LOCATION)) add(DialogueFields.LOCATION)
            if (has(IncidentField.INCIDENT_DESCRIPTION)) add(DialogueFields.DESCRIPTION)
            if (has(IncidentField.DATE_TIME)) add(DialogueFields.TIME)
            if (has(IncidentField.IMMEDIATE_ACTIONS_TAKEN)) add(DialogueFields.IMMEDIATE_ACTIONS)
            if (persons.any { !DialogueFields.isInjured(it) }) add(DialogueFields.PEOPLE_INVOLVED)
        }
    }

    /**
     * Thin description (Spec v2 section 4.3): no `incident_type`, fewer than 6 content words in the first transcript
     * (articles and fillers do not count), or an empty description.
     */
    fun isThin(report: IncidentReport, firstTranscript: String): Boolean =
        report.fields[IncidentField.INCIDENT_TYPE.key]?.value?.isEmptyValue() != false ||
            cues.contentWordCount(firstTranscript) < 6 ||
            report.fields[IncidentField.INCIDENT_DESCRIPTION.key]?.value?.isEmptyValue() != false

    fun plannerState(report: IncidentReport, transcripts: List<String>, thin: Boolean, urgent: Boolean): PlannerState {
        val severity = severity(report, urgent, transcripts)
        return PlannerState(
            filled = filled(report),
            injury = injury(report),
            severityAtLeastHigh = severity == "High" || severity == "Critical",
            hazardOngoing = hazardOngoing(report),
            personalIncident = classifier.isPersonalIncident(
                report.fields[IncidentField.INCIDENT_TYPE.key]?.value?.asTextOrNull(), *transcripts.toTypedArray(),
            ),
            thinDescription = thin,
            pastCue = transcripts.any { cues.hasPastCue(it) },
            locationVague = isVagueLocation(report),
        )
    }

    /** A location that only consists of vague words ("near the building") cannot route the report. */
    fun isVagueLocation(report: IncidentReport): Boolean {
        val loc = report.fields[IncidentField.INCIDENT_LOCATION.key]?.value?.asTextOrNull() ?: return false
        val tokens = TextMatch.tokens(loc)
        return tokens.isNotEmpty() && tokens.all { it in relevance.vagueLocations }
    }

    /**
     * Makes `injury` and `injury_outcome` agree: derives `injury` from the outcome; derives the outcome from `injury`
     * (`false` -> "No injury"; `true` -> "Minor injury" at low confidence so Review highlights it). Existing values win.
     * Nothing is derived while `injury` is unknown: an unanswered injury question never leaves an outcome behind.
     */
    fun reconcileInjury(report: IncidentReport): IncidentReport {
        val injuryEntry = report.fields[IncidentField.INJURY.key]
        val outcomeEntry = report.fields[IncidentField.INJURY_OUTCOME.key]
        val injuryEmpty = injuryEntry?.value?.isEmptyValue() != false
        val outcomeEmpty = outcomeEntry?.value?.isEmptyValue() != false
        var fields = report.fields
        if (injuryEmpty && !outcomeEmpty) {
            val v = InjuryOutcome.isInjury(outcomeEntry!!.value.asTextOrNull())
            fields = fields + (IncidentField.INJURY.key to FieldEntry(boolValue(v), outcomeEntry.confidence, outcomeEntry.source))
        } else if (!injuryEmpty && outcomeEmpty) {
            val v = injuryEntry!!.value.asBooleanOrNull()
            if (v != null) {
                val entry = if (v) FieldEntry(textValue(InjuryOutcome.MINOR), LOW_OUTCOME_CONFIDENCE, FieldSource.AI)
                else FieldEntry(textValue(InjuryOutcome.NONE), injuryEntry.confidence, injuryEntry.source)
                fields = fields + (IncidentField.INJURY_OUTCOME.key to entry)
            }
        }
        return if (fields === report.fields) report else report.copy(fields = fields)
    }

    companion object {
        /** Below `IncidentConfig.confidenceThreshold` (0.6) so Review highlights the guessed outcome. */
        const val LOW_OUTCOME_CONFIDENCE = 0.5
    }
}
