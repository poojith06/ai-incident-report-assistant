package com.example.test_app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The kind of value a field holds; drives parsing and the editor used on the Review form. */
enum class FieldKind { TEXT, LONG_TEXT, SEVERITY, INJURY, DATE_TIME, YES_NO, STRING_LIST, WITNESSES }

/** How a field is obtained from the user. Drives Review-form grouping. See `doc/DATA_SCHEMA.md`. */
enum class FieldGroup(val title: String) {
    /** Required; asked only if missing. */
    REQUIRED("Required details"),
    /** Captured when mentioned; asked only in specific situations. */
    SITUATIONAL("Situation details"),
    /** Optional; never asked, editable on review. */
    OPTIONAL("Optional details"),
    /** Filled by the app; never asked, editable on review. */
    APP_FILLED("Filled by the app"),
}

/**
 * Catalog of the 14 incident fields (the team's "recommended field set" plus the dialogue's `injury` and `injury_type`). [key] is the JSON key.
 * Declaration order = Review-form order. See `doc/DATA_SCHEMA.md`.
 */
enum class IncidentField(val key: String, val label: String, val kind: FieldKind, val group: FieldGroup) {
    INCIDENT_DESCRIPTION("incident_description", "What happened", FieldKind.LONG_TEXT, FieldGroup.REQUIRED),
    INCIDENT_LOCATION("incident_location", "Location", FieldKind.TEXT, FieldGroup.REQUIRED),
    DATE_TIME("date_time", "Date and time", FieldKind.DATE_TIME, FieldGroup.REQUIRED),
    IMMEDIATE_ACTIONS_TAKEN("immediate_actions_taken", "Immediate actions taken", FieldKind.LONG_TEXT, FieldGroup.SITUATIONAL),
    INJURY_OUTCOME("injury_outcome", "Injury / outcome", FieldKind.INJURY, FieldGroup.SITUATIONAL),
    INJURY("injury", "Anyone hurt", FieldKind.YES_NO, FieldGroup.SITUATIONAL),
    INJURY_TYPE("injury_type", "Type of injury", FieldKind.TEXT, FieldGroup.SITUATIONAL),
    PERSONS_INVOLVED("persons_involved", "Persons involved", FieldKind.STRING_LIST, FieldGroup.SITUATIONAL),
    HAZARD_STILL_ACTIVE("hazard_still_active", "Hazard still active", FieldKind.YES_NO, FieldGroup.SITUATIONAL),
    EQUIPMENT_ASSET_INVOLVED("equipment_asset_involved", "Equipment / asset", FieldKind.STRING_LIST, FieldGroup.OPTIONAL),
    WITNESS_INFORMATION("witness_information", "Witnesses", FieldKind.WITNESSES, FieldGroup.OPTIONAL),
    INCIDENT_TYPE("incident_type", "Incident type", FieldKind.TEXT, FieldGroup.APP_FILLED),
    SEVERITY_LEVEL("severity_level", "Severity", FieldKind.SEVERITY, FieldGroup.APP_FILLED),
    RECOMMENDED_FOLLOW_UP_ACTION("recommended_follow_up_action", "Recommended follow-up", FieldKind.LONG_TEXT, FieldGroup.APP_FILLED);

    companion object {
        fun fromKey(key: String): IncidentField? = entries.firstOrNull { it.key == key }
        fun label(key: String): String = fromKey(key)?.label ?: key
    }
}

/** Allowed `severity_level` values. */
object Severity {
    val ALLOWED = listOf("Low", "Medium", "High", "Critical")
}

/** Allowed `injury_outcome` values. */
object InjuryOutcome {
    const val NONE = "No injury"
    const val MINOR = "Minor injury"
    const val SERIOUS = "Serious injury"
    const val FATAL = "Fatal"
    val ALLOWED = listOf(NONE, MINOR, SERIOUS, FATAL)

    fun isInjury(value: String?): Boolean = value != null && value != NONE
}

private val witnessJson = Json { ignoreUnknownKeys = true }

/** True if the value is null, blank string, or empty array (the "missing" definition in the schema). */
fun JsonElement.isEmptyValue(): Boolean = when (this) {
    is JsonNull -> true
    is JsonArray -> isEmpty()
    is JsonPrimitive -> contentOrNull?.isBlank() ?: true
    else -> false
}

/** Text value, or null when the element is not a non-null primitive. */
fun JsonElement.asTextOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Boolean value (accepts JSON booleans and the strings "true"/"false"), or null. */
fun JsonElement.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.let {
    it.booleanOrNull ?: it.contentOrNull?.lowercase()?.let { s -> if (s == "yes") true else if (s == "no") false else null }
}

/** String list value (primitive elements only); empty for anything else. */
fun JsonElement.asStringList(): List<String> =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

/** Witness list value; empty for anything else. */
fun JsonElement.asWitnesses(): List<Witness> =
    (this as? JsonArray)?.mapNotNull {
        runCatching { witnessJson.decodeFromJsonElement(Witness.serializer(), it) }.getOrNull()
    } ?: emptyList()

fun textValue(text: String?): JsonElement = if (text.isNullOrBlank()) JsonNull else JsonPrimitive(text)

fun boolValue(value: Boolean?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

fun listValue(items: List<String>): JsonElement = JsonArray(items.map { JsonPrimitive(it) })

fun witnessValue(items: List<Witness>): JsonElement =
    JsonArray(items.map { witnessJson.encodeToJsonElement(Witness.serializer(), it) })

/** Human-readable one-line rendering of any field value, for summaries. */
fun FieldEntry.displayText(field: IncidentField): String = when (field.kind) {
    FieldKind.STRING_LIST -> value.asStringList().joinToString(", ")
    FieldKind.WITNESSES -> value.asWitnesses().joinToString("; ") {
        listOfNotNull(it.name, it.role, it.contact).joinToString(", ")
    }
    FieldKind.YES_NO -> value.asBooleanOrNull()?.let { if (it) "Yes" else "No" }.orEmpty()
    else -> value.asTextOrNull().orEmpty()
}

/** Convenience: the entry for [field], or an empty AI entry. */
fun IncidentReport.entry(field: IncidentField): FieldEntry =
    fields[field.key] ?: FieldEntry()

/** Returns a copy with [key] replaced. */
fun IncidentReport.withField(key: String, entry: FieldEntry): IncidentReport =
    copy(fields = fields + (key to entry))
