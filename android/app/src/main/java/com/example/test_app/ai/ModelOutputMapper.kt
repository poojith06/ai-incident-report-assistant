package com.example.test_app.ai

import com.example.test_app.dialogue.DialogueFields
import com.example.test_app.dialogue.LanguageDetect
import com.example.test_app.domain.model.Clarification
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InjuryOutcome
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.Severity
import com.example.test_app.domain.model.TimeSource
import com.example.test_app.domain.model.asStringList
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.isEmptyValue
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.listValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.service.ExtractionRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Maps the model's small JSON (keys `incident_type`, `severity`, `location`, `datetime`, `persons_involved`,
 * `actions_taken`, `description`) onto the Incident report (pure Kotlin). Rules (see `doc/DIALOGUE.md`):
 * never invent (blank / "null" / "none" / "unknown" become an empty entry at confidence 0.0); `injury` is false only
 * on an explicit negation in the transcript; emergency keywords raise severity to High (never lower); answers to
 * `injury`, `injured_person` and `injury_type` are mapped deterministically without the LLM ([needsLlm]).
 */
object ModelOutputMapper {
    val INCIDENT_TYPES = listOf(
        "Water Leak", "Fire", "Injury", "Accident", "Equipment Failure", "Electrical Hazard", "Gas Leak", "Spill", "Security", "Other",
    )

    private val EMPTY_WORDS = setOf("", "null", "none", "unknown", "n/a", "na", "nil", "not specified", "unspecified", "not mentioned", "not known")

    private const val CONF_TYPE = 0.8
    private const val CONF_SEVERITY = 0.7
    private const val CONF_LOCATION = 0.85
    private const val CONF_TIME = 0.8
    private const val CONF_TEXT = 0.8
    private const val CONF_DESCRIPTION = 0.85
    private const val CONF_PHOTO_ONLY_MAX = 0.5
    private const val CONF_INJURY = 0.8
    private const val CONF_NEGATED_INJURY = 0.85
    private const val CONF_EMERGENCY_SEVERITY = 0.6
    private const val CONF_USER = 0.9

    // ---- reading the model JSON -----------------------------------------------------------------

    /** Text of [key]; arrays are joined with ", ". Null for missing, JSON null, blank, "null"/"none"/"unknown"... */
    fun textOf(json: JsonObject, key: String): String? {
        val raw = when (val v = json[key]) {
            null, is JsonNull -> null
            is JsonPrimitive -> v.asTextOrNull()
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.asTextOrNull() }.joinToString(", ")
            else -> null
        } ?: return null
        val t = raw.trim()
        return if (t.lowercase().trim('.', '"', ' ') in EMPTY_WORDS) null else t
    }

    /** `incident_type` normalised to [INCIDENT_TYPES] when it matches case-insensitively, else the model's text. */
    fun normalizeType(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return INCIDENT_TYPES.firstOrNull { it.equals(t, ignoreCase = true) } ?: t
    }

    /** Canonical `Low`/`Medium`/`High`/`Critical`, or null for anything else. */
    fun normalizeSeverity(text: String?): String? =
        text?.trim()?.let { t -> Severity.ALLOWED.firstOrNull { it.equals(t, ignoreCase = true) } }

    /** Splits a spoken list on `,` `/` `&` and "and". */
    fun splitPeople(text: String): List<String> =
        text.split(Regex("\\s*(?:,|/|&|\\band\\b)\\s*", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() !in EMPTY_WORDS }

    // ---- transcript rules -----------------------------------------------------------------------

    private val NEGATIONS = listOf(
        Regex("\\b(?:nobody|no\\s*one|none(?:\\s+of\\s+\\w+)?)\\s+(?:\\w+\\s+){0,2}?(?:hurt|injured)\\b"),
        Regex("\\bno\\s+injur(?:y|ies)\\b"),
        Regex("\\b(?:not|wasn't|weren't|isn't|aren't|wasnt|werent|didn't\\s+get)\\s+(?:\\w+\\s+)?(?:hurt|injured)\\b"),
    )
    private val INJURY_WORDS = Regex(
        "\\b(?:hurt|injur(?:ed|y|ies)|bleeding|wounded|burned|burnt|sprained|fractured|unconscious|cut\\s+(?:his|her|my|their|a|the)\\s+\\w+|broke\\s+(?:his|her|my|their)\\s+\\w+)\\b",
    )
    private val EMERGENCY = Regex(
        "\\b(?:fire|smoke|gas\\s+leak|explosion|unconscious|not\\s+breathing|heavy\\s+bleeding|electrocuted|trapped|collapsed|weapon|knife|gun)\\b",
    )
    private val STILL_ACTIVE = Regex("\\bstill\\s+(?:\\w+\\s+){0,2}?(?:leaking|burning|spreading|happening|dripping)\\b")

    private fun norm(text: String) = text.lowercase().replace('\u2019', '\'')

    /** True when the text explicitly says nobody was hurt. */
    fun hasInjuryNegation(text: String): Boolean = norm(text).let { t -> NEGATIONS.any { it.containsMatchIn(t) } }

    /** True when the text mentions someone hurt (negated phrases such as "nobody was hurt" do not count). */
    fun hasInjuryMention(text: String): Boolean {
        var t = norm(text)
        for (n in NEGATIONS) t = n.replace(t, " ")
        return INJURY_WORDS.containsMatchIn(t)
    }

    fun hasEmergencyKeyword(text: String): Boolean = EMERGENCY.containsMatchIn(norm(text))

    // ---- first pass -----------------------------------------------------------------------------

    fun firstPass(json: JsonObject, transcript: String, request: ExtractionRequest, now: OffsetDateTime): IncidentReport {
        val at = now.truncatedTo(ChronoUnit.SECONDS)
        val fields = IncidentField.entries.associate { it.key to FieldEntry(JsonNull, 0.0, FieldSource.AI) }.toMutableMap()

        val type = normalizeType(textOf(json, "incident_type"))
        var severity = normalizeSeverity(textOf(json, "severity"))
        var severityConf = CONF_SEVERITY
        if (hasEmergencyKeyword(transcript) && (severity == null || severity == "Low" || severity == "Medium")) {
            severity = "High"
            severityConf = CONF_EMERGENCY_SEVERITY
        }
        put(fields, IncidentField.INCIDENT_TYPE, type, CONF_TYPE)
        put(fields, IncidentField.SEVERITY_LEVEL, severity, severityConf)
        put(fields, IncidentField.INCIDENT_LOCATION, textOf(json, "location"), CONF_LOCATION)
        SpokenTime.parse(textOf(json, "datetime"), at)?.let { put(fields, IncidentField.DATE_TIME, SpokenTime.format(it), CONF_TIME) }
        textOf(json, "persons_involved")?.let { putPeople(fields, it, CONF_TEXT, FieldSource.AI) }
        put(fields, IncidentField.IMMEDIATE_ACTIONS_TAKEN, textOf(json, "actions_taken"), CONF_TEXT)
        put(fields, IncidentField.INCIDENT_DESCRIPTION, textOf(json, "description"), CONF_DESCRIPTION)

        val positive = hasInjuryMention(transcript)
        val negated = hasInjuryNegation(transcript)
        when {
            positive -> fields[IncidentField.INJURY.key] = FieldEntry(boolValue(true), CONF_INJURY, FieldSource.AI)
            negated -> fields[IncidentField.INJURY.key] = FieldEntry(boolValue(false), CONF_NEGATED_INJURY, FieldSource.AI)
            type == "Injury" -> fields[IncidentField.INJURY.key] = FieldEntry(boolValue(true), CONF_INJURY, FieldSource.AI)
        }
        if (STILL_ACTIVE.containsMatchIn(norm(transcript))) {
            fields[IncidentField.HAZARD_STILL_ACTIVE.key] = FieldEntry(boolValue(true), CONF_EMERGENCY_SEVERITY, FieldSource.AI)
        }

        return IncidentReport(
            reportId = "INC-${at.year}-" + String.format("%06d", at.toEpochSecond() % 1_000_000),
            status = IncidentReport.STATUS_NEEDS_CLARIFICATION,
            createdAt = at.toString(),
            input = ReportInput(mode = request.mode.wire, transcript = transcript, language = LanguageDetect.detect(transcript), media = request.media),
            fields = if (transcript.isBlank() && request.imageHints.isNotEmpty()) capForPhotoOnly(fields) else fields,
            clarification = Clarification(maxRounds = request.config.maxClarificationRounds),
        )
    }

    /**
     * Photo-only report (no narration): every value comes from the photo description, a guess by a vision model, so each AI value is
     * capped at [CONF_PHOTO_ONLY_MAX] (below the validation threshold: Review shows it as low confidence). Source stays `ai`.
     */
    private fun capForPhotoOnly(fields: Map<String, FieldEntry>): Map<String, FieldEntry> =
        fields.filter { (k, e) -> !(k == IncidentField.INCIDENT_LOCATION.key && isGenericPhotoLocation(e.value.asTextOrNull())) }.mapValues { (_, e) ->
            if (e.source == FieldSource.AI && e.confidence > CONF_PHOTO_ONLY_MAX) e.copy(confidence = CONF_PHOTO_ONLY_MAX) else e
        }

    /** Words a vision model gives as a "location" that name no place ("indoor", a part of a room): such a value is dropped (the field stays empty and flagged). */
    internal fun isGenericPhotoLocation(text: String?): Boolean {
        val words = norm(text.orEmpty()).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words.all { it in GENERIC_LOCATION_WORDS }
    }

    private val GENERIC_LOCATION_WORDS = setOf(
        "indoor", "indoors", "interior", "inside", "outside", "area", "space", "setting", "room", "wall", "walls", "ceiling", "floor", "corner", "surface",
        "unknown", "unclear", "a", "an", "the", "of", "in", "or", "and", "near", "an", "building", "structure", "location", "place",
    )

    /**
     * Stores [text] in a structured field. The report is always English: text that still has non-Latin script (any non-Latin script)
     * is NOT stored; the field stays empty with status `unverified` and `raw_answer` = the original, so Review highlights it, the
     * English TTS voice never has to read it, and the planner may still ask for it once.
     */
    private fun put(fields: MutableMap<String, FieldEntry>, field: IncidentField, text: String?, confidence: Double, source: String = FieldSource.AI) {
        if (text.isNullOrBlank()) return
        if (LanguageDetect.hasNonLatin(text)) {
            // never replace a good English value with raw script
            if (fields[field.key]?.value?.isEmptyValue() != false) fields[field.key] = rawScript(text, source)
            return
        }
        fields[field.key] = FieldEntry(textValue(text), confidence, source)
    }

    private fun rawScript(original: String, source: String) =
        FieldEntry(JsonNull, 0.0, source, status = FieldStatus.UNVERIFIED, rawAnswer = original.trim())

    /** `persons_involved`: entries in non-Latin script are dropped (kept as `raw_answer` when nothing readable is left). */
    private fun putPeople(fields: MutableMap<String, FieldEntry>, text: String, confidence: Double, source: String) {
        val all = splitPeople(text)
        val latin = all.filterNot { LanguageDetect.hasNonLatin(it) }
        when {
            latin.isNotEmpty() -> fields[IncidentField.PERSONS_INVOLVED.key] = FieldEntry(listValue(latin), confidence, source)
            all.isNotEmpty() -> fields[IncidentField.PERSONS_INVOLVED.key] = rawScript(text, source)
        }
    }

    // ---- follow-up answers ----------------------------------------------------------------------

    private val DETERMINISTIC = setOf(DialogueFields.INJURED_PERSON, DialogueFields.INJURY_TYPE)

    /** False for the dialogue fields answered by Kotlin rules alone (injured_person, injury_type): no LLM call. */
    fun needsLlm(dialogueField: String?): Boolean = dialogueField !in DETERMINISTIC

    /**
     * Merges one answer into [previous]. [json] is the model's update object (null = unreadable: no change); it is
     * ignored for the deterministic fields. Only fields the answer or the model supplied change; they get source user_voice.
     */
    fun applyUpdate(previous: IncidentReport, json: JsonObject?, dialogueField: String?, answer: String, now: OffsetDateTime): IncidentReport {
        val fields = previous.fields.toMutableMap()
        when (dialogueField) {
            DialogueFields.INJURED_PERSON -> {
                val person = injuredPerson(answer) ?: return previous
                addPeople(fields, listOf(person))
            }
            DialogueFields.INJURY_TYPE -> {
                val type = cleanPhrase(answer) ?: return previous
                fields[IncidentField.INJURY_TYPE.key] = FieldEntry(textValue(type), CONF_USER, FieldSource.USER_VOICE)
            }
            else -> {
                if (json == null) return previous
                applyJson(fields, json, now)
            }
        }
        return previous.copy(fields = fields)
    }

    private fun applyJson(fields: MutableMap<String, FieldEntry>, json: JsonObject, now: OffsetDateTime) {
        val at = now.truncatedTo(ChronoUnit.SECONDS)
        textOf(json, "location")?.let { put(fields, IncidentField.INCIDENT_LOCATION, it, CONF_LOCATION, FieldSource.USER_VOICE) }
        textOf(json, "datetime")?.let { spoken ->
            SpokenTime.parse(spoken, at)?.let { put(fields, IncidentField.DATE_TIME, SpokenTime.format(it), CONF_TIME, FieldSource.USER_VOICE) }
        }
        textOf(json, "persons_involved")?.let { t ->
            val all = splitPeople(t)
            val latin = all.filterNot { LanguageDetect.hasNonLatin(it) }
            if (latin.isNotEmpty()) addPeople(fields, latin) else if (all.isNotEmpty() && fields[IncidentField.PERSONS_INVOLVED.key]?.value?.isEmptyValue() != false) fields[IncidentField.PERSONS_INVOLVED.key] = rawScript(t, FieldSource.USER_VOICE)
        }
        textOf(json, "actions_taken")?.let { put(fields, IncidentField.IMMEDIATE_ACTIONS_TAKEN, it, CONF_TEXT, FieldSource.USER_VOICE) }
        textOf(json, "description")?.let { text ->
            if (LanguageDetect.hasNonLatin(text)) {
                if (fields[IncidentField.INCIDENT_DESCRIPTION.key]?.value?.isEmptyValue() != false) {
                    fields[IncidentField.INCIDENT_DESCRIPTION.key] = rawScript(text, FieldSource.USER_VOICE)
                }
                return@let
            }
            val old = fields[IncidentField.INCIDENT_DESCRIPTION.key]?.value?.asTextOrNull().orEmpty().trim()
            val merged = when {
                old.isEmpty() -> text
                old.contains(text, ignoreCase = true) -> old
                text.contains(old.trimEnd('.', ' '), ignoreCase = true) -> text
                else -> old.trimEnd() + (if (old.last() in ".!?") " " else ". ") + text
            }
            put(fields, IncidentField.INCIDENT_DESCRIPTION, merged, CONF_DESCRIPTION, FieldSource.USER_VOICE)
        }
        normalizeType(textOf(json, "incident_type"))?.let { put(fields, IncidentField.INCIDENT_TYPE, it, CONF_TYPE, FieldSource.USER_VOICE) }
        normalizeSeverity(textOf(json, "severity"))?.let { put(fields, IncidentField.SEVERITY_LEVEL, it, CONF_SEVERITY, FieldSource.USER_VOICE) }
    }

    /** Adds [people] to `persons_involved`, keeping existing entries (an injured entry wins over a plain duplicate). */
    private fun addPeople(fields: MutableMap<String, FieldEntry>, people: List<String>) {
        val key = IncidentField.PERSONS_INVOLVED.key
        var list = fields[key]?.value?.asStringList().orEmpty()
        for (p in people) {
            val bare = p.removeSuffix(DialogueFields.INJURED_SUFFIX).trim()
            if (list.any { it.equals(p, ignoreCase = true) }) continue
            if (!DialogueFields.isInjured(p) && list.any { it.removeSuffix(DialogueFields.INJURED_SUFFIX).trim().equals(bare, ignoreCase = true) }) continue
            list = list.filterNot { !DialogueFields.isInjured(it) && it.trim().equals(bare, ignoreCase = true) } + p
        }
        fields[key] = FieldEntry(listValue(list), CONF_USER, FieldSource.USER_VOICE)
    }

    // ---- deterministic answer parsers ------------------------------------------------------------

    private val FILLER = Regex(
        "^(?:uh+|um+|hmm+|er+|well|so|yeah|yes|okay|ok|it'?s|it\\s+was|it\\s+is|this\\s+is|that\\s+was|that'?s|its|i\\s+(?:have|had|got|think|guess)|i'?m|i|a|an|the|got)\\b[\\s,.:;\\-]*",
        RegexOption.IGNORE_CASE,
    )
    private val FILLER_TOKENS = setOf("uh", "um", "er", "hmm", "yeah", "yes", "it", "it's", "its", "was", "is", "the", "that", "that's", "this", "and", "also", "too", "only", "am", "got", "hurt", "injured", "did")
    private val SELF_TOKENS = setOf("me", "i", "my", "myself", "i'm")

    /** Strips leading fillers ("uh, it's a ..."), trailing punctuation, capitalises the first letter; null if nothing is left. */
    fun cleanPhrase(text: String): String? {
        var s = Prompts.sanitize(text)
        while (true) {
            val next = FILLER.replace(s, "")
            if (next == s) break
            s = next
        }
        s = s.trim().trim('.', ',', ';', '!', '?', '"', '\'', ' ')
        if (s.isEmpty()) return null
        return s.replaceFirstChar { it.uppercase() }
    }

    /** "me / I / my / myself" -> `Reporter (injured)`; otherwise the cleaned words + ` (injured)`; null when nothing usable. */
    fun injuredPerson(answer: String): String? {
        val tokens = norm(answer).replace(Regex("[^a-z' ]"), " ").split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (tokens.any { it in SELF_TOKENS } && tokens.all { it in SELF_TOKENS || it in FILLER_TOKENS }) {
            return "Reporter" + DialogueFields.INJURED_SUFFIX
        }
        val name = (cleanPhrase(answer) ?: return null).removeSuffix(")").removeSuffix("(injured").trim()
        return if (name.isEmpty()) null else name + DialogueFields.INJURED_SUFFIX
    }

    // ---- prompt input ---------------------------------------------------------------------------

    /** CURRENT_FORM for [Prompts.update]: model key names, non-empty values only. The default (now) date_time is not sent. */
    fun currentForm(report: IncidentReport): Map<String, String?> {
        fun text(f: IncidentField): String? = report.fields[f.key]?.value?.let { v ->
            when (v) {
                is JsonArray -> v.asStringList().joinToString(", ")
                else -> v.asTextOrNull()
            }
        }?.takeIf { it.isNotBlank() }
        val form = LinkedHashMap<String, String?>()
        form["incident_type"] = text(IncidentField.INCIDENT_TYPE)
        form["location"] = text(IncidentField.INCIDENT_LOCATION)
        if (report.timeSource != TimeSource.DEFAULT_NOW) form["datetime"] = text(IncidentField.DATE_TIME)
        form["persons_involved"] = text(IncidentField.PERSONS_INVOLVED)
        form["actions_taken"] = text(IncidentField.IMMEDIATE_ACTIONS_TAKEN)
        form["description"] = text(IncidentField.INCIDENT_DESCRIPTION)
        return form.filterValues { it != null }
    }
}
