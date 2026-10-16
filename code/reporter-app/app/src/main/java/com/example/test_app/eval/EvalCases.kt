package com.example.test_app.eval

import com.example.test_app.dialogue.ANSWER_HINT_SAFETY
import com.example.test_app.dialogue.DialogueFields
import com.example.test_app.dialogue.DialogueMode
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/** A malformed `cases.json`. */
class EvalCaseException(message: String) : Exception(message)

/**
 * The user's reply to one question. [field] is the dialogue field the question was about (see [DialogueFields]) or
 * `safety` for the safety-check reply. [transcript] is what the reply says (null = the user stays silent);
 * [audio] is the optional recording; [asrConf] (typed input only) is the recogniser confidence to simulate (default 1.0).
 */
data class EvalAnswer(val field: String, val transcript: String?, val audio: String?, val asrConf: Float?)

/** What the case expects. Everything but [fields] is optional (null / empty = not checked). */
data class EvalExpected(
    /** `PLAIN`, `CARE` or `URGENT`: the tone mode at the end of the dialogue. */
    val mode: String?,
    /** Report field key -> expected value as text (see `FieldMatch`). */
    val fields: Map<String, String>,
    /** Report field keys that must stay empty (never invent). */
    val emptyFields: List<String>,
    /** Exact ordered list of asked dialogue fields; null = not checked. */
    val askedFields: List<String>?,
    val askedIncludes: List<String>,
    val askedExcludes: List<String>,
    /** Asked dialogue field -> outcome status (`filled`, `unknown_reporter_unsure`, `no_response`, `unverified`). */
    val outcomes: Map<String, String>,
    val emergency: Boolean?,
    /** `review` (default) or `no_narration` (the user never spoke). */
    val result: String,
    /** Photo-only cases: the lowest acceptable `severity_level` (`Low` / `Medium` / `High` / `Critical`); null = not checked. The policy floors can only raise it. */
    val severityMin: String? = null,
)

/**
 * One evaluation case (`eval/README.md`). [audio], answer audio and [images] paths are relative to the `eval` directory. A case with
 * [images] is a PHOTO-ONLY case (ADR-062): no narration, the report comes from the photos alone, no question may be asked.
 */
data class EvalCase(
    val id: String,
    val audio: String?,
    val transcript: String?,
    val tags: List<String>,
    val notes: String?,
    val followUpAnswers: List<EvalAnswer>,
    val expected: EvalExpected,
    val images: List<String> = emptyList(),
) {
    /** The scripted answer for the question about [field] (`null` = the narration is not an answer). */
    fun answerFor(field: String): EvalAnswer? = followUpAnswers.firstOrNull { it.field == field }

    /** Recordings the case needs for an audio run: the narration and every non-silent answer that has audio. */
    fun requiredAudio(): List<String> =
        listOfNotNull(audio) + followUpAnswers.filter { it.transcript != null }.mapNotNull { it.audio }

    /** True when the case has no narration at all (spec scenario 11); a photo-only case is not "silent": it has its photos. */
    val silent: Boolean get() = transcript.isNullOrBlank() && audio == null && images.isEmpty()

    /** True for a photo-only case. */
    val photoOnly: Boolean get() = images.isNotEmpty()
}

/** Parser and validator of `cases.json` (kotlinx.serialization JSON, no Android). */
object EvalCases {
    const val RESULT_REVIEW = "review"
    const val RESULT_NO_NARRATION = "no_narration"

    private val CONTROL_KEYS = setOf(ANSWER_HINT_SAFETY)
    private val ANSWER_FIELDS = DialogueFields.PRIORITY.toSet() + CONTROL_KEYS
    private val REPORT_KEYS = IncidentField.entries.map { it.key }.toSet()
    private val OUTCOMES = setOf(FieldStatus.FILLED, FieldStatus.UNKNOWN_REPORTER_UNSURE, FieldStatus.NO_RESPONSE, FieldStatus.UNVERIFIED)
    private val MODES = DialogueMode.entries.map { it.name }.toSet()

    /** Parses and validates; the first problem throws [EvalCaseException] naming the case. */
    fun parse(text: String): List<EvalCase> {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw EvalCaseException("cases.json is not valid JSON: ${e.message}")
        }
        val array = (root as? JsonArray) ?: (root as? JsonObject)?.get("cases") as? JsonArray
            ?: throw EvalCaseException("cases.json must be a JSON array of cases (or an object with a \"cases\" array)")
        val cases = array.mapIndexed { i, e -> case(e as? JsonObject ?: throw EvalCaseException("case #$i is not an object")) }
        val dup = cases.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (dup.isNotEmpty()) throw EvalCaseException("duplicate case ids: $dup")
        return cases
    }

    private fun case(o: JsonObject): EvalCase {
        val id = str(o["id"]) ?: throw EvalCaseException("a case has no \"id\"")
        fun bad(msg: String): Nothing = throw EvalCaseException("$id: $msg")
        val transcript = str(o["transcript"])
        val audio = str(o["audio"])
        val expectedObj = o["expected"] as? JsonObject ?: bad("missing \"expected\" object")
        val result = str(expectedObj["result"]) ?: RESULT_REVIEW
        if (result != RESULT_REVIEW && result != RESULT_NO_NARRATION) bad("expected.result must be \"review\" or \"no_narration\"")
        val images = strList(o["images"])
        if (images.isNotEmpty() && (!transcript.isNullOrBlank() || audio != null)) bad("a photo-only case (\"images\") has no \"transcript\" and no \"audio\"")
        if (images.isNotEmpty() && (o["follow_up_answers"] as? JsonArray).orEmpty().isNotEmpty()) bad("a photo-only case asks nothing: no \"follow_up_answers\"")
        if (result == RESULT_REVIEW && transcript.isNullOrBlank() && images.isEmpty()) bad("\"transcript\" is required (it is the reference text for WER and the typed input)")
        val answers = (o["follow_up_answers"] as? JsonArray).orEmpty().map { a ->
            val ao = a as? JsonObject ?: bad("follow_up_answers entries must be objects")
            val field = str(ao["field"]) ?: bad("a follow_up_answers entry has no \"field\"")
            if (field !in ANSWER_FIELDS) bad("unknown answer field \"$field\" (use one of ${ANSWER_FIELDS})")
            EvalAnswer(field, str(ao["transcript"]), str(ao["audio"]), (ao["asr_conf"] as? JsonPrimitive)?.floatOrNull)
        }
        answers.groupingBy { it.field }.eachCount().filterValues { it > 1 }.keys.takeIf { it.isNotEmpty() }
            ?.let { bad("answers given twice for $it (ask-once: one answer per field)") }
        val fields = (expectedObj["fields"] as? JsonObject).orEmpty().mapValues { (k, v) ->
            if (k !in REPORT_KEYS) bad("expected.fields has unknown report field \"$k\"")
            valueText(v) ?: bad("expected.fields.$k must be a string, number, boolean or list of strings")
        }
        val empty = strList(expectedObj["empty_fields"]).onEach { if (it !in REPORT_KEYS) bad("expected.empty_fields has unknown report field \"$it\"") }
        fun dialogueList(key: String): List<String>? = (expectedObj[key] as? JsonArray)?.let { arr ->
            arr.map { str(it) ?: bad("expected.$key must contain strings") }.onEach { if (it !in DialogueFields.PRIORITY) bad("expected.$key has unknown dialogue field \"$it\"") }
        }
        val mode = str(expectedObj["mode"])
        if (mode != null && mode !in MODES) bad("expected.mode must be one of $MODES")
        val outcomes = (expectedObj["outcomes"] as? JsonObject).orEmpty().mapValues { (k, v) ->
            if (k !in DialogueFields.PRIORITY) bad("expected.outcomes has unknown dialogue field \"$k\"")
            str(v)?.takeIf { it in OUTCOMES } ?: bad("expected.outcomes.$k must be one of $OUTCOMES")
        }
        return EvalCase(
            id = id, audio = audio, transcript = transcript, tags = strList(o["tags"]), notes = str(o["notes"]),
            followUpAnswers = answers, images = images,
            expected = EvalExpected(
                mode = mode, fields = fields, emptyFields = empty, askedFields = dialogueList("asked_fields"),
                askedIncludes = dialogueList("asked_includes").orEmpty(), askedExcludes = dialogueList("asked_excludes").orEmpty(),
                outcomes = outcomes, emergency = (expectedObj["emergency"] as? JsonPrimitive)?.booleanOrNull, result = result,
                severityMin = str(expectedObj["severity_min"])?.also { if (it !in Severity.ALLOWED) bad("expected.severity_min must be one of ${Severity.ALLOWED}") },
            ),
        )
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun strList(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty().mapNotNull { str(it) }

    /** Expected value as text: strings as is, numbers/booleans as `true`/`false`/digits, lists joined by `; `. */
    private fun valueText(e: JsonElement): String? = when (e) {
        is JsonNull -> null
        is JsonArray -> e.mapNotNull { str(it) }.joinToString("; ")
        is JsonPrimitive -> e.contentOrNull
        else -> null
    }
}
