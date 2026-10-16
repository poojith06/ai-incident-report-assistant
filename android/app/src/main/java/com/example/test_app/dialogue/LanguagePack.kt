package com.example.test_app.dialogue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The templates of the spoken read-outs ([FormReadout.review], [FormReadout.submitted], the dialogue's read-back placeholders) of one
 * language. Data, not code: the `readout` object of `question_bank.<lang>.json`. A key a language does not define falls back to the
 * English default of [DEFAULTS] (the English sentence is then spoken by the English voice), so a pack can be completed step by step.
 * Placeholders are written `{name}`. See `doc/DIALOGUE.md` "Languages".
 */
class ReadoutTemplates(private val values: Map<String, String>) {
    operator fun get(key: String): String = values[key] ?: DEFAULTS.getValue(key)

    /** [key] with every `{name}` replaced by its value from [args]. */
    fun render(key: String, vararg args: Pair<String, String>): String {
        var s = get(key)
        for ((name, value) in args) s = s.replace("{$name}", value)
        return s
    }

    /** The keys this pack defines itself (the rest is English). */
    val definedKeys: Set<String> get() = values.keys

    companion object {
        /** The English read-outs: exactly the sentences `FormReadout` always spoke. A pack defines the same keys with the same placeholders. */
        val DEFAULTS: Map<String, String> = linkedMapOf(
            "ready" to "Your report is ready.",
            "summary" to "{summary}.",
            "summary_at" to "at {location}",
            "when_now" to "just now",
            "time_h" to "{h}",
            "time_hm" to "{h}:{mm}",
            "time_am" to "{time} AM",
            "time_pm" to "{time} PM",
            "severity" to "Severity {severity}.",
            "hurt" to "Someone was hurt.",
            "not_hurt" to "Nobody was hurt.",
            "missing" to "Still missing: {fields}.",
            "emergency" to "Emergency contact numbers are shown after you submit.",
            "tail" to "Please review and tap submit.",
            "tail_flagged" to "Please review the highlighted fields and tap submit.",
            "submitted" to "Report submitted. Reference {id}.",
            "submitted_severe" to "You can call the emergency contacts on screen.",
            "sep" to ", ",
            "summary_join" to " at ",
            "fallback_incident" to "an incident",
            "fallback_place" to "the place you mentioned",
            "fallback_report" to "your report",
            "time_set_now" to "time set to now",
        )

        /** English: no key of its own. */
        val ENGLISH = ReadoutTemplates(emptyMap())

        private val json = Json { ignoreUnknownKeys = true }

        /** Keys whose value is a separator between words: its spaces matter. */
        private val JOINERS = setOf("sep", "summary_join")

        /** Reads the `readout` object of a question bank file; an unknown key or a blank value is an error, a missing object gives English. */
        fun parse(bankText: String): ReadoutTemplates {
            val root = try {
                json.parseToJsonElement(bankText).jsonObject
            } catch (e: Exception) {
                throw DialogueAssetException("question bank is not valid JSON", e)
            }
            val obj = root["readout"] as? JsonObject ?: return ENGLISH
            val values = LinkedHashMap<String, String>()
            for ((k, v) in obj) {
                if (k.startsWith("_")) continue
                if (k !in DEFAULTS) throw DialogueAssetException("readout: unknown key '$k'")
                val raw = (v as? JsonPrimitive)?.contentOrNull?.removePrefix(QuestionBank.TRANSLATE_MARKER)
                if (raw == null || raw.isBlank() && k !in JOINERS) throw DialogueAssetException("readout: blank value for '$k'")
                values[k] = if (k in JOINERS) raw else raw.trim() // joiners keep their spaces (" at ")
            }
            return ReadoutTemplates(values)
        }
    }
}

/**
 * Words of one language for the English enumerations that appear in the spoken read-outs: incident type, severity, field label and the
 * numbers 0..59 (the Rasa character frontend does not read digits as words; a read-out spells hours, minutes and id digits with these).
 * Data: `assets/dialogue/glossary.<lang>.json`. A missing entry is null: the caller keeps the English word (spoken by the English voice).
 * Lookups of incident type and severity ignore case.
 */
class Glossary(
    private val incidentTypes: Map<String, String> = emptyMap(),
    private val severities: Map<String, String> = emptyMap(),
    private val fieldLabels: Map<String, String> = emptyMap(),
    private val numbers: Map<Int, String> = emptyMap(),
) {
    fun incidentType(value: String): String? = incidentTypes[value.trim().lowercase()]

    fun severity(value: String): String? = severities[value.trim().lowercase()]

    /** The spoken label of a field by its JSON key (`incident_location`), or null. */
    fun fieldLabel(key: String): String? = fieldLabels[key]

    /** The word of a number, or null when this glossary has no number words. */
    fun number(n: Int): String? = numbers[n]

    val hasNumbers: Boolean get() = numbers.isNotEmpty()

    /** Keys of each table, for validation and tests. */
    val incidentTypeKeys: Set<String> get() = incidentTypes.keys
    val severityKeys: Set<String> get() = severities.keys
    val fieldLabelKeys: Set<String> get() = fieldLabels.keys
    val numberKeys: Set<Int> get() = numbers.keys

    /** Every digit of [text] replaced by its word (digit by digit, space separated); nothing changes without number words. */
    fun spellDigits(text: String): String {
        if (!hasNumbers) return text
        val out = StringBuilder()
        for (ch in text) {
            val word = if (ch in '0'..'9') numbers[ch - '0'] else null
            if (word != null) {
                if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                out.append(word).append(' ')
            } else {
                out.append(ch)
            }
        }
        return out.toString().replace(Regex(" +"), " ").replace(Regex(" ([,.;:\u0964])"), "$1").trim()
    }

    companion object {
        val NONE = Glossary()

        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject?.strings(lowercaseKeys: Boolean): Map<String, String> =
            this?.entries?.filter { !it.key.startsWith("_") }?.associate { (k, v) ->
                (if (lowercaseKeys) k.trim().lowercase() else k) to ((v as? JsonPrimitive)?.contentOrNull?.trim()
                    ?: throw DialogueAssetException("glossary: '$k' is not a string"))
            } ?: emptyMap()

        fun parse(text: String): Glossary {
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw DialogueAssetException("glossary is not valid JSON", e)
            }
            val numbers = (root["numbers"] as? JsonObject).strings(false).entries.associate { (k, v) ->
                (k.toIntOrNull() ?: throw DialogueAssetException("glossary: number key '$k' is not an integer")) to v
            }
            val g = Glossary(
                (root["incident_type"] as? JsonObject).strings(true),
                (root["severity"] as? JsonObject).strings(true),
                (root["field_labels"] as? JsonObject).strings(false),
                numbers,
            )
            if (g.incidentTypeKeys.isEmpty() && g.severityKeys.isEmpty()) throw DialogueAssetException("glossary: no incident_type and no severity entries")
            return g
        }
    }
}

/**
 * Everything the dialogue and the read-outs need in ONE language: the question bank (questions and fixed lines), the read-out templates
 * and the glossary. [code] is the language code (`en`, `hi`, `te`); English has [Glossary.NONE]. Chosen by [DialogueAssets.pack].
 */
class LanguagePack(val code: String, val bank: QuestionBank, val readout: ReadoutTemplates, val glossary: Glossary) {
    val isEnglish: Boolean get() = code == "en"
}
