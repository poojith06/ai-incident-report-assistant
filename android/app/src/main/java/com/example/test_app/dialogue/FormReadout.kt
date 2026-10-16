package com.example.test_app.dialogue

import com.example.test_app.domain.config.EmergencyContacts
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.TimeSource
import com.example.test_app.domain.model.asBooleanOrNull
import com.example.test_app.domain.model.asTextOrNull
import java.time.OffsetDateTime

/**
 * The two short lines the app reads aloud AFTER the dialogue: [review] when the Review screen shows the filled form, and
 * [submitted] on the Success screen. Pure Kotlin (no Android). Wording rules: plain words, about 40 words at most, and no
 * claim that anything was sent, notified or dispatched (nothing is, in this phase): the lists of `blocked_phrases.json`
 * ("help is on the way") and the words notified / alerted / dispatched / called are forbidden here and checked by
 * `FormReadoutTest`. The line is spoken once per Review entry by `IncidentFlowViewModel`.
 *
 * The sentences are the [ReadoutTemplates] of a [LanguagePack] and the words for the enumerations (incident type, severity, field
 * labels, numbers) come from its [Glossary]; without a pack (null) the line is English. The report values stay English (ADR rule): a
 * free-text value (the location) is spoken as it is, as an English run inside the sentence of the language (the TTS routes each script
 * run to its own voice). A glossary entry that is missing leaves the English word. See `doc/DIALOGUE.md`.
 */
object FormReadout {
    /** Upper bound of the review line in words (the line is shortened to fit). */
    const val MAX_WORDS = 45

    private const val MAX_FLAGGED = 3
    private const val MAX_VALUE_WORDS = 6

    /** One short read-out of the filled form (type, place, time, severity, injury, up to 3 missing or flagged fields). */
    fun review(report: IncidentReport, pack: LanguagePack? = null): String {
        val t = pack?.readout ?: ReadoutTemplates.ENGLISH
        val g = pack?.glossary ?: Glossary.NONE
        val englishVoice = pack?.isEnglish ?: true
        val sep = t["sep"]
        fun text(f: IncidentField) = report.fields[f.key]?.value?.asTextOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        // The English TTS voice cannot read Devanagari / Telugu: such text becomes a generic phrase (other languages have a voice for it).
        val type = text(IncidentField.INCIDENT_TYPE)?.takeIf { !englishVoice || !LanguageDetect.hasNonLatin(it) }?.let { g.incidentType(it) ?: it }
        val location = text(IncidentField.INCIDENT_LOCATION)?.let {
            shorten(if (englishVoice) LanguageDetect.speakable(it, t["fallback_place"]) else it)
        }
        val whenText = timePhrase(report, t, g)
        val severity = text(IncidentField.SEVERITY_LEVEL)?.let { g.severity(it) ?: it.lowercase() }
        val severe = EmergencyContacts.isSevere(text(IncidentField.SEVERITY_LEVEL))
        val injury = report.fields[IncidentField.INJURY.key]?.value?.asBooleanOrNull()

        val flagged = flaggedLabels(report, g)
        val summary = buildList {
            if (type != null) add(type)
            if (location != null) add(t.render("summary_at", "location" to location))
            if (whenText != null && (type != null || location != null)) add(whenText)
        }.joinToString(sep)

        fun build(withInjury: Boolean, flaggedCount: Int): String {
            val parts = mutableListOf(t["ready"])
            if (summary.isNotEmpty()) parts += t.render("summary", "summary" to summary)
            if (severity != null) parts += t.render("severity", "severity" to severity)
            if (withInjury && injury != null) parts += if (injury) t["hurt"] else t["not_hurt"]
            if (flaggedCount > 0) parts += t.render("missing", "fields" to flagged.take(flaggedCount).joinToString(sep))
            if (severe) parts += t["emergency"]
            parts += if (flagged.isNotEmpty()) t["tail_flagged"] else t["tail"]
            return parts.joinToString(" ")
        }

        // Shorten in a fixed order until it fits: injury sentence, then the missing list down to one field.
        val attempts = listOf(true to flagged.size.coerceAtMost(MAX_FLAGGED), false to flagged.size.coerceAtMost(MAX_FLAGGED), false to 1.coerceAtMost(flagged.size), false to 0)
        for ((withInjury, n) in attempts) {
            val line = build(withInjury, n)
            if (wordCount(line) <= MAX_WORDS) return line
        }
        return build(false, 0)
    }

    /** The Success line: the reference spoken character by character, plus a pointer to the on-screen contacts when severe. */
    fun submitted(report: IncidentReport, pack: LanguagePack? = null): String {
        val t = pack?.readout ?: ReadoutTemplates.ENGLISH
        val g = pack?.glossary ?: Glossary.NONE
        val severe = EmergencyContacts.isSevere(report.fields[IncidentField.SEVERITY_LEVEL.key]?.value?.asTextOrNull())
        val line = t.render("submitted", "id" to speakableId(report.reportId, g, t["sep"]))
        return if (severe) "$line ${t["submitted_severe"]}" else line
    }

    /**
     * `INC-2026-665948` -> `I N C, 2026, 6 6 5 9 4 8` (the year as a number, the serial digit by digit). With a [glossary] that has number
     * words every digit becomes its word (`I N C, दो शून्य दो छह, छह छह ...`): the Indic voices do not read digit characters reliably.
     */
    fun speakableId(id: String, glossary: Glossary = Glossary.NONE, sep: String = ", "): String {
        val parts = id.split('-').filter { it.isNotEmpty() }
        val spoken = parts.mapIndexed { i, p ->
            when {
                p.all { it.isDigit() } && p.length == 4 && i == 1 -> p
                else -> p.toCharArray().joinToString(" ") { it.uppercaseChar().toString() }
            }
        }.joinToString(sep)
        return glossary.spellDigits(spoken)
    }

    /** Labels of mandatory fields that are missing and of fields the dialogue flagged, at most [MAX_FLAGGED] shown by the caller. */
    private fun flaggedLabels(report: IncidentReport, glossary: Glossary): List<String> {
        val keys = linkedSetOf<String>()
        keys += report.validation.missingFields
        for (f in IncidentField.entries) {
            val e = report.fields[f.key] ?: continue
            if (e.effectiveStatus in FieldStatus.NEEDS_ATTENTION) keys += f.key
        }
        return keys.mapNotNull { IncidentField.fromKey(it) }
            // injury is never "missing": it is optional (ADR-050), so it is not named here even when it is empty or flagged
            .filter { it != IncidentField.SEVERITY_LEVEL && it != IncidentField.DATE_TIME && it != IncidentField.INJURY_OUTCOME && it != IncidentField.INJURY }
            .map { glossary.fieldLabel(it.key) ?: spokenLabel(it) }
            .distinct()
    }

    private fun spokenLabel(f: IncidentField): String = when (f) {
        IncidentField.INCIDENT_DESCRIPTION -> "description"
        IncidentField.INCIDENT_LOCATION -> "location"
        IncidentField.IMMEDIATE_ACTIONS_TAKEN -> "actions taken"
        IncidentField.PERSONS_INVOLVED -> "people involved"
        IncidentField.INJURY_TYPE -> "type of injury"
        else -> f.label.lowercase()
    }

    private fun shorten(s: String): String {
        val words = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (words.size <= MAX_VALUE_WORDS) words.joinToString(" ") else words.take(MAX_VALUE_WORDS).joinToString(" ")
    }

    /** "just now" for a defaulted time, otherwise `10 AM` / `3:30 PM` (or the language's wording); null when there is no time. */
    private fun timePhrase(report: IncidentReport, t: ReadoutTemplates, g: Glossary): String? {
        if (report.timeSource == TimeSource.DEFAULT_NOW) return t["when_now"]
        val iso = report.fields[IncidentField.DATE_TIME.key]?.value?.asTextOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return clockPhrase(iso, t, g)
    }

    /** `2026-10-10T10:00:00+05:30` -> `10 AM`, `...T15:30...` -> `3:30 PM` through the templates and number words; unparsable text is returned as is. */
    fun clockPhrase(iso: String, t: ReadoutTemplates = ReadoutTemplates.ENGLISH, g: Glossary = Glossary.NONE): String {
        val time = runCatching { OffsetDateTime.parse(iso) }.getOrNull() ?: return iso
        val h = if (time.hour % 12 == 0) 12 else time.hour % 12
        val hText = g.number(h) ?: h.toString()
        val clock = if (time.minute == 0) t.render("time_h", "h" to hText)
        else t.render("time_hm", "h" to hText, "mm" to (g.number(time.minute) ?: "%02d".format(time.minute)))
        return t.render(if (time.hour < 12) "time_am" else "time_pm", "time" to clock)
    }

    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
}
