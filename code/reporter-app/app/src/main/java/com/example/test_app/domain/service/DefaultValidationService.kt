package com.example.test_app.domain.service

import com.example.test_app.domain.config.IncidentConfig
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InjuryOutcome
import com.example.test_app.domain.model.Severity
import com.example.test_app.domain.model.Validation
import com.example.test_app.domain.model.asBooleanOrNull
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.isEmptyValue

/**
 * Real (non-mock) validation. Rules (see `doc/DATA_SCHEMA.md` section "Validation rules"):
 * - Missing: mandatory field whose value is null / blank / empty list, or an injury/severity outside its allowed values.
 * - Low confidence: mandatory, present, askable (in `askOrder`), confidence < threshold, source `ai`.
 *   (date_time is never asked, so it is never "low confidence".)
 * - Follow-up (situational, asked at most once, never blocks completeness):
 *   persons_involved if someone was injured; immediate_actions_taken if severity is High/Critical or the hazard is active.
 * - Complete: nothing missing and nothing low-confidence.
 */
class DefaultValidationService : ValidationService {
    override fun validate(report: IncidentReport, config: IncidentConfig): IncidentReport {
        val missing = mutableListOf<String>()
        val low = mutableListOf<String>()
        for (key in config.mandatoryFields) {
            val entry = report.fields[key]
            val text = entry?.value?.asTextOrNull()
            val invalidEnum = (key == IncidentField.SEVERITY_LEVEL.key && text !in Severity.ALLOWED) ||
                (key == IncidentField.INJURY_OUTCOME.key && text !in InjuryOutcome.ALLOWED)
            when {
                entry == null || entry.value.isEmptyValue() || invalidEnum -> missing += key
                key in config.askOrder && entry.source == FieldSource.AI && entry.confidence < config.confidenceThreshold -> low += key
            }
        }

        val asked = report.clarification.history.flatMap { it.askedFields }.toSet()
        fun empty(f: IncidentField) = report.fields[f.key]?.value?.isEmptyValue() ?: true
        val injured = InjuryOutcome.isInjury(report.fields[IncidentField.INJURY_OUTCOME.key]?.value?.asTextOrNull())
        val severity = report.fields[IncidentField.SEVERITY_LEVEL.key]?.value?.asTextOrNull()
        val hazardActive = report.fields[IncidentField.HAZARD_STILL_ACTIVE.key]?.value?.asBooleanOrNull() == true

        val followUps = buildList {
            val persons = IncidentField.PERSONS_INVOLVED
            if (persons.key in config.askOrder && injured && empty(persons) && persons.key !in asked) add(persons.key)
            val actions = IncidentField.IMMEDIATE_ACTIONS_TAKEN
            if (actions.key in config.askOrder && (severity == "High" || severity == "Critical" || hazardActive) &&
                empty(actions) && actions.key !in asked
            ) add(actions.key)
        }

        val complete = missing.isEmpty() && low.isEmpty()
        return report.copy(
            status = if (complete) IncidentReport.STATUS_COMPLETE else IncidentReport.STATUS_NEEDS_CLARIFICATION,
            validation = Validation(
                mandatoryFields = config.mandatoryFields,
                missingFields = missing,
                lowConfidenceFields = low,
                isComplete = complete,
                followUpFields = followUps,
            ),
        )
    }
}
