package com.example.test_app.domain.service

import com.example.test_app.domain.model.InjuryOutcome
import com.example.test_app.domain.model.Severity

/**
 * Word lists of `assets/dialogue/severity_cues.json` (English only; edit the file, not the code).
 * [negationWords] / [negationWindowTokens] are the ones of `mode_keywords.json` ("not urgent", "no emergency").
 */
class SeverityCues(
    /** Distress / escalation phrases: with an URGENT mode they make the severity Critical. Negation-guarded. */
    val escalation: List<String>,
    /** Incident types that are at least High on their own (compared case-insensitively). */
    val highIncidentTypes: Set<String>,
    val negationWords: Set<String> = emptySet(),
    val negationAfterWords: Set<String> = emptySet(),
    val negationWindowTokens: Int = 4,
) {
    companion object {
        /** No cues at all: only the injury/hazard table and the URGENT-mode floor apply. Tests only. */
        val NONE = SeverityCues(emptyList(), emptySet())
    }
}

/** What the severity policy looks at. All fields come from the report and the user's words. */
data class SeverityInput(
    /** The model's (or an earlier) severity: only a hint. Null when empty. */
    val hint: String?,
    /** The dialogue reached URGENT (or the emergency flag is set). */
    val urgent: Boolean,
    /** Everything the user said so far (narration, answers, safety-check reply, read-back reply). */
    val texts: List<String>,
    val injury: Boolean?,
    val injuryOutcome: String?,
    val hazardActive: Boolean,
    val incidentType: String?,
)

/**
 * Deterministic severity (build prompt follow-up B): `final = max(model hint, every floor)`. The LLM's severity is
 * only a hint; the app never lets it lower what the rules say. Floors:
 *
 * | Floor | Severity |
 * |---|---|
 * | injury outcome / hazard table ([IncidentPolicies.suggestSeverity]) | Low .. Critical |
 * | mode URGENT | at least High |
 * | mode URGENT and (an un-negated escalation cue in the user's words, or an injury) | Critical |
 * | incident type Fire / Gas Leak / Electrical Hazard (`severity_cues.json`) | at least High |
 *
 * Pure Kotlin, no clock, no Android. Escalate-only follows from the hint being the previous value. A value the user
 * edited on the Review screen is never passed in here (see [IncidentPolicies.apply]). See `doc/DATA_SCHEMA.md`.
 */
object SeverityPolicy {
    private fun rank(s: String?): Int = Severity.ALLOWED.indexOfFirst { it.equals(s, ignoreCase = true) }

    /** Canonical severity text for [rank], or null for -1. */
    private fun name(rank: Int): String? = Severity.ALLOWED.getOrNull(rank)

    /** The highest severity among [a] and [b] (null counts as lowest). */
    fun max(a: String?, b: String?): String? = name(maxOf(rank(a), rank(b)))

    /** True when the user's words contain an escalation cue that is not negated ("it's not urgent" does not count). */
    fun hasEscalationCue(texts: List<String>, cues: SeverityCues): Boolean =
        texts.any { t ->
            val lower = t.lowercase().replace('\u2019', '\'')
            cues.escalation.any { TextMatch.containsUnnegated(lower, it, cues.negationWords, cues.negationWindowTokens, cues.negationAfterWords) }
        }

    /** The final severity, or null when there is no hint and no floor applies (nothing is known yet). */
    fun decide(input: SeverityInput, cues: SeverityCues): String? {
        var best = rank(input.hint)
        fun floor(s: String?) {
            best = maxOf(best, rank(s))
        }
        // injury / hazard table (an injury answer without an outcome counts as a minor injury, "no" as none)
        val outcome = input.injuryOutcome?.takeIf { it.isNotBlank() }
            ?: when (input.injury) {
                true -> InjuryOutcome.MINOR
                false -> InjuryOutcome.NONE
                null -> null
            }
        floor(IncidentPolicies.suggestSeverity(outcome, input.hazardActive))
        val injured = input.injury == true || InjuryOutcome.isInjury(input.injuryOutcome?.takeIf { it.isNotBlank() })
        if (input.urgent) {
            floor("High")
            if (injured || hasEscalationCue(input.texts, cues)) floor("Critical")
        }
        if (input.incidentType != null && cues.highIncidentTypes.any { it.equals(input.incidentType.trim(), ignoreCase = true) }) floor("High")
        return name(best)
    }
}
