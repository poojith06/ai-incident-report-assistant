package com.example.test_app.dialogue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Thrown when a dialogue asset file is malformed. Fail loudly at app start (Spec v2 section 11 step 1). */
class DialogueAssetException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * One question per field x mode, plus the fixed lines (greeting, opening, safety check, defer, read-back, closing).
 * Loaded from `assets/dialogue/question_bank.<lang>.json` (Spec v2 section 6). `URGENT` falls back to `CARE`,
 * `PLAIN` falls back to `CARE` (fields such as `injured_person` only have a CARE text).
 * `TODO_TRANSLATE:` prefixes in `hi` / `te` banks are stripped, leaving the English text until a human translates it.
 * See `doc/DIALOGUE.md`.
 */
class QuestionBank private constructor(
    val language: String,
    val greeting: String,
    private val fields: Map<String, Map<DialogueMode, String>>,
    private val lines: Map<String, String>,
) {
    /** The single question for [field] in [mode]. */
    fun question(field: String, mode: DialogueMode): String {
        val byMode = fields[field] ?: throw DialogueAssetException("No question for field '$field'")
        return byMode[mode] ?: byMode[DialogueMode.CARE] ?: byMode.getValue(DialogueMode.PLAIN)
    }

    /** A fixed line by key (see [REQUIRED_LINES]). */
    fun line(key: String): String = lines[key] ?: throw DialogueAssetException("Missing line '$key'")

    /** Every question text (all fields, all modes), for validation and tests. */
    fun allQuestions(): List<Pair<String, String>> =
        fields.flatMap { (field, byMode) -> byMode.map { (mode, text) -> "$field/${mode.name}" to text } }

    companion object {
        const val TRANSLATE_MARKER = "TODO_TRANSLATE:"
        const val MAX_QUESTION_WORDS = 20

        val REQUIRED_LINES = listOf(
            "opening_one", "opening_many", "opening_care", "safety_check", "defer",
            "readback_plain", "readback_care", "closing_plain", "closing_care", "offer_typing",
        )

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses and validates a bank. Rejects any `clarify` key (ask-once rule) and any question that breaks the wording rules. */
        fun parse(text: String): QuestionBank {
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw DialogueAssetException("question bank is not valid JSON", e)
            }
            rejectClarifyKeys(root, "")
            val version = root["version"]?.jsonPrimitive?.int
            if (version != 2) throw DialogueAssetException("question bank version must be 2 (was $version)")
            val language = root.str("language")
            val greeting = clean(root.str("greeting"))
            val fieldsObj = root["fields"]?.jsonObject ?: throw DialogueAssetException("missing 'fields'")
            val fields = DialogueFields.PRIORITY.associateWith { field ->
                val obj = fieldsObj[field]?.jsonObject ?: throw DialogueAssetException("no entry for field '$field'")
                val byMode = obj.entries.associate { (modeName, v) ->
                    val mode = DialogueMode.entries.firstOrNull { it.name == modeName }
                        ?: throw DialogueAssetException("unknown mode '$modeName' in field '$field'")
                    val q = clean(v.jsonPrimitive.content)
                    checkQuestion("$field/$modeName", q)
                    mode to q
                }
                if (byMode.isEmpty()) throw DialogueAssetException("field '$field' has no question")
                byMode
            }
            val linesObj = root["lines"]?.jsonObject ?: throw DialogueAssetException("missing 'lines'")
            val lines = REQUIRED_LINES.associateWith { key -> clean(linesObj.str(key)) }
            // The read-back is a statement (ADR-048): it asks nothing and the dialogue does not listen after it.
            for (key in listOf("readback_plain", "readback_care")) {
                if ('?' in lines.getValue(key)) throw DialogueAssetException("$key: the read-back is a statement and must not contain '?'")
            }
            return QuestionBank(language, greeting, fields, lines)
        }

        /** Every question: exactly one `?`, at most 20 words (Spec v2 section 7). */
        fun checkQuestion(where: String, q: String) {
            val marks = q.count { it == '?' }
            if (marks != 1) throw DialogueAssetException("$where: expected exactly one '?' but found $marks: \"$q\"")
            val words = wordCount(q)
            if (words > MAX_QUESTION_WORDS) throw DialogueAssetException("$where: $words words (max $MAX_QUESTION_WORDS): \"$q\"")
        }

        fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

        private fun clean(s: String): String = s.removePrefix(TRANSLATE_MARKER).trim()

        private fun JsonObject.str(key: String): String =
            (this[key] as? JsonPrimitive)?.contentOrNull ?: throw DialogueAssetException("missing string '$key'")

        private fun rejectClarifyKeys(element: JsonElement, path: String) {
            if (element is JsonObject) {
                for ((k, v) in element) {
                    if (k.contains("clarify", ignoreCase = true)) {
                        throw DialogueAssetException("'$path/$k': clarify / retry variants are forbidden (ask-once rule)")
                    }
                    rejectClarifyKeys(v, "$path/$k")
                }
            }
        }
    }
}
