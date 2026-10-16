package com.example.test_app.eval

/** How well an extracted value matches the expected one. */
enum class MatchLevel {
    /** Identical after trimming. */
    EXACT,

    /** Identical after [TextNorm] normalisation (case, punctuation, number words). */
    NORMALIZED,

    /** Equal under the relaxed rule of the field kind (see [FieldMatch]). */
    RELAXED,
    NONE,
    ;

    val matched: Boolean get() = this != NONE
}

/** Which comparison a field uses. */
enum class MatchKind { STRICT, LOCATION, TIME, OVERLAP }

/**
 * Field-value matching for the evaluation. Pure Kotlin. Every rule first tries [MatchLevel.EXACT] (trimmed strings
 * equal) and [MatchLevel.NORMALIZED] (equal after [TextNorm.normalize]); the kind then decides about [MatchLevel.RELAXED]:
 *
 * | Kind | Fields | Relaxed rule |
 * |---|---|---|
 * | `STRICT` | `incident_type`, `severity_level`, `injury`, `injury_outcome`, `hazard_still_active` | none: exact or normalised only |
 * | `LOCATION` | `incident_location` | location tokens (normal form minus articles, prepositions of place, "area") of the expected value are all present (stemmed) in the actual value: "near Conference Room 2" and "Conference Room 2, second floor" match "Conference Room 2"; "Conference Room" or "lab" do NOT match "Conference Room 2" / "lab 3" |
 * | `TIME` | `date_time` | both sides are parsed to a time of day (`10 AM`, `around 10 a.m.`, `ten am`, `10:00`, `15:30`, noon, midnight, ISO `...T10:00:00+05:30`; the date is ignored) and differ by at most [TIME_TOLERANCE_MIN] minutes. If a side has no parsable time (for example "this morning") the text rule of `OVERLAP` with recall 1.0 applies |
 * | `OVERLAP` | descriptions, actions, people, injury type, equipment, witnesses, follow-up | at least [OVERLAP_RECALL] of the expected content tokens (normal form, stop words removed, stemmed) occur in the actual value: expected "Area isolated" matches "Maintenance has already isolated the area" |
 *
 * Severity is derived by app policy from the injury outcome, so cases only list it when the policy result is certain.
 */
object FieldMatch {
    const val OVERLAP_RECALL = 0.6
    const val TIME_TOLERANCE_MIN = 15

    fun kindOf(fieldKey: String): MatchKind = when (fieldKey) {
        "incident_location" -> MatchKind.LOCATION
        "date_time" -> MatchKind.TIME
        "incident_description", "immediate_actions_taken", "injury_type", "persons_involved", "equipment_asset_involved",
        "recommended_follow_up_action", "witness_information",
        -> MatchKind.OVERLAP
        else -> MatchKind.STRICT
    }

    fun match(fieldKey: String, expected: String, actual: String?): MatchLevel = matchKind(kindOf(fieldKey), expected, actual)

    fun matchKind(kind: MatchKind, expected: String, actual: String?): MatchLevel {
        if (actual.isNullOrBlank() || expected.isBlank()) return MatchLevel.NONE
        if (expected.trim() == actual.trim()) return MatchLevel.EXACT
        val ne = TextNorm.normalize(expected)
        if (ne.isNotEmpty() && ne == TextNorm.normalize(actual)) return MatchLevel.NORMALIZED
        val relaxed = when (kind) {
            MatchKind.STRICT -> false
            MatchKind.LOCATION -> location(expected, actual)
            MatchKind.TIME -> time(expected, actual)
            MatchKind.OVERLAP -> overlap(expected, actual, OVERLAP_RECALL)
        }
        return if (relaxed) MatchLevel.RELAXED else MatchLevel.NONE
    }

    private fun location(expected: String, actual: String): Boolean {
        val e = TextNorm.locationTokens(expected).map(TextNorm::stem)
        if (e.isEmpty()) return false
        val a = TextNorm.locationTokens(actual).map(TextNorm::stem).toSet()
        return a.containsAll(e)
    }

    private fun time(expected: String, actual: String): Boolean {
        val e = TimeOfDay.minutes(expected)
        val a = TimeOfDay.minutes(actual)
        if (e != null && a != null) return kotlin.math.abs(e - a) <= TIME_TOLERANCE_MIN
        return overlap(expected, actual, 1.0)
    }

    private fun overlap(expected: String, actual: String, minRecall: Double): Boolean {
        val e = TextNorm.contentTokens(expected).toSet()
        if (e.isEmpty()) return false
        val a = TextNorm.contentTokens(actual).toSet()
        return e.count { it in a }.toDouble() / e.size >= minRecall
    }
}

/** Time-of-day parsing for [FieldMatch] (minutes after midnight). */
object TimeOfDay {
    private val ISO = Regex("""\d{4}-\d{2}-\d{2}t(\d{1,2}):(\d{2})""")
    private val AM_PM = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""")
    private val H24 = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")

    /** Minutes after midnight, or null when [text] contains no recognisable time of day. The date part is ignored. */
    fun minutes(text: String): Int? {
        val t = TextNorm.digitsForNumberWords(TextNorm.lightClean(text))
        ISO.find(t)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        AM_PM.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h in 1..12 && min in 0..59) return (h % 12 + if (m.groupValues[3] == "pm") 12 else 0) * 60 + min
        }
        H24.find(t)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        if (Regex("""\bnoon\b""").containsMatchIn(t)) return 12 * 60
        if (Regex("""\bmidnight\b""").containsMatchIn(t)) return 0
        return null
    }
}

/** Where an expected value ended up. */
enum class Placement {
    /** The right field holds a matching value. */
    CORRECT,

    /** The right field does not match, but another field holds a matching value (value in the wrong field). */
    MISPLACED,

    /** The right field has a value that does not match and the expected value is nowhere else. */
    WRONG,

    /** The right field is empty and the expected value is nowhere else. */
    MISSING,
}

/**
 * Mapping metric: was an extracted value put into the right field? For every expected `(field, value)`:
 * [Placement.CORRECT] if [FieldMatch.match] succeeds on that field; otherwise the value is searched in all OTHER
 * fields (same match kind as the expected field; `incident_description` and `recommended_follow_up_action` are
 * not searched because they legitimately repeat everything; `true` / `false` are never searched) and the result is
 * [Placement.MISPLACED] when found, else [Placement.WRONG] (right field non-empty) or [Placement.MISSING] (empty).
 * The placement rate is `CORRECT / (CORRECT + MISPLACED)`: of the values that were extracted at all, the share that
 * landed in the right field. It is null when nothing was extracted.
 */
object Mapping {
    private val NOT_SEARCHED = setOf("incident_description", "recommended_follow_up_action")

    fun classify(expected: Map<String, String>, actual: Map<String, String?>): Map<String, Placement> =
        expected.mapValues { (field, exp) ->
            val here = actual[field]
            when {
                FieldMatch.match(field, exp, here).matched -> Placement.CORRECT
                foundElsewhere(field, exp, actual) -> Placement.MISPLACED
                here.isNullOrBlank() -> Placement.MISSING
                else -> Placement.WRONG
            }
        }

    private fun foundElsewhere(field: String, expected: String, actual: Map<String, String?>): Boolean {
        if (expected.trim().lowercase() in setOf("true", "false")) return false
        val kind = FieldMatch.kindOf(field)
        return actual.any { (k, v) -> k != field && k !in NOT_SEARCHED && FieldMatch.matchKind(kind, expected, v).matched }
    }

    /** `correct / (correct + misplaced)`, null when both are 0. */
    fun placementRate(correct: Int, misplaced: Int): Double? = if (correct + misplaced == 0) null else correct.toDouble() / (correct + misplaced)
}
