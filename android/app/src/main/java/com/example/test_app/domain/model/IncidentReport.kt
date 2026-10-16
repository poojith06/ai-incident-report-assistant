package com.example.test_app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Root of the Incident JSON contract. Mirrors `doc/DATA_SCHEMA.md` field for field.
 * `fields` is a map keyed by [IncidentField.key] so the wire format stays a plain JSON object.
 */
@Serializable
data class IncidentReport(
    @SerialName("schema_version") val schemaVersion: String = SCHEMA_VERSION,
    @SerialName("report_id") val reportId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    val input: ReportInput,
    val fields: Map<String, FieldEntry>,
    val device: DeviceInfo? = null,
    val validation: Validation = Validation(),
    val clarification: Clarification = Clarification(),
    @SerialName("tool_call") val toolCall: ToolCall? = null,
    /** [LocationSource] of `incident_location`; null until the dialogue (or a user edit) sets it. */
    @SerialName("location_source") val locationSource: String? = null,
    /** [TimeSource] of `date_time`; null until the dialogue (or a user edit) sets it. */
    @SerialName("time_source") val timeSource: String? = null,
    /** Follow-up dialogue summary (mode, asked fields, emergency flag). See `doc/DIALOGUE.md`. */
    val dialogue: DialogueInfo = DialogueInfo(),
) {
    companion object {
        const val SCHEMA_VERSION = "1.1"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_NEEDS_CLARIFICATION = "needs_clarification"
        const val STATUS_SUBMITTED = "submitted"
    }
}

/** What the user provided: transcript, language, captured media. See `doc/DATA_SCHEMA.md`. */
@Serializable
data class ReportInput(
    /** [InputMode.wire] value. */
    val mode: String,
    val transcript: String,
    val language: String = "en",
    val media: List<MediaItem> = emptyList(),
)

/** One attached photo: the compressed (<= 512 px JPEG) copy of a gallery pick, stored in `files/media/`. */
@Serializable
data class MediaItem(
    val id: String,
    val type: String = "image",
    @SerialName("local_uri") val localUri: String,
    @SerialName("captured_at") val capturedAt: String,
)

/**
 * One form field. [value] is heterogeneous by field (string, string array, witness array, or null);
 * use the helpers in `FieldValues.kt` to read/write it.
 */
@Serializable
data class FieldEntry(
    val value: JsonElement = JsonNull,
    val confidence: Double = 0.0,
    /** One of [FieldSource]. */
    val source: String = FieldSource.AI,
    /** One of [FieldStatus]; null means "derive from [value]" (see [effectiveStatus]). */
    val status: String? = null,
    /** The user's unparsed answer, kept when the status is [FieldStatus.UNVERIFIED]. */
    @SerialName("raw_answer") val rawAnswer: String? = null,
) {
    /** [status] when set, otherwise `filled` / `empty` from the value. */
    val effectiveStatus: String
        get() = status ?: if (value.isEmptyValue()) FieldStatus.EMPTY else FieldStatus.FILLED
}

/** Allowed values of [FieldEntry.status] (follow-up dialogue outcome, see `doc/DATA_SCHEMA.md`). */
object FieldStatus {
    const val FILLED = "filled"
    const val UNKNOWN_REPORTER_UNSURE = "unknown_reporter_unsure"
    const val NO_RESPONSE = "no_response"
    const val UNVERIFIED = "unverified"
    const val MISSING_FLAGGED = "missing_flagged"
    const val EMPTY = "empty"

    /** Statuses the Review screen highlights so the user can fix the field by tapping it. */
    val NEEDS_ATTENTION = setOf(UNKNOWN_REPORTER_UNSURE, NO_RESPONSE, UNVERIFIED, MISSING_FLAGGED)
}

/** Allowed values of [IncidentReport.locationSource]. */
object LocationSource {
    const val SPOKEN = "spoken"
    const val GPS = "gps"
}

/** Allowed values of [IncidentReport.timeSource]. */
object TimeSource {
    const val SPOKEN = "spoken"
    const val DEFAULT_NOW = "default_now"
}

/** The `dialogue` object of the report: tone mode (`PLAIN`/`CARE`/`URGENT`), fields asked, emergency flag. */
@Serializable
data class DialogueInfo(
    val mode: String = "PLAIN",
    @SerialName("asked_fields") val askedFields: List<String> = emptyList(),
    val emergency: Boolean = false,
)

/** Allowed values of [FieldEntry.source]. */
object FieldSource {
    const val AI = "ai"
    const val USER_VOICE = "user_voice"
    const val USER_EDIT = "user_edit"
}

/** Output of `ValidationService`. */
@Serializable
data class Validation(
    @SerialName("mandatory_fields") val mandatoryFields: List<String> = emptyList(),
    @SerialName("missing_fields") val missingFields: List<String> = emptyList(),
    @SerialName("low_confidence_fields") val lowConfidenceFields: List<String> = emptyList(),
    @SerialName("is_complete") val isComplete: Boolean = false,
    /** Non-mandatory fields the situation says we should ask about once (for example "who was hurt"). */
    @SerialName("follow_up_fields") val followUpFields: List<String> = emptyList(),
) {
    /** Everything still worth asking the user about: missing, low confidence, then situational follow-ups. */
    val attentionFields: List<String> get() = (missingFields + lowConfidenceFields + followUpFields).distinct()
}

/** State of the follow-up question loop. */
@Serializable
data class Clarification(
    @SerialName("rounds_used") val roundsUsed: Int = 0,
    @SerialName("max_rounds") val maxRounds: Int = 3,
    @SerialName("next_question") val nextQuestion: ClarificationQuestion? = null,
    val history: List<ClarificationTurn> = emptyList(),
)

/** A follow-up question about one or more fields. */
@Serializable
data class ClarificationQuestion(
    @SerialName("target_fields") val targetFields: List<String>,
    val text: String,
)

/** One completed question/answer exchange. */
@Serializable
data class ClarificationTurn(
    val question: String,
    val answer: String,
    @SerialName("asked_fields") val askedFields: List<String> = emptyList(),
    @SerialName("updated_fields") val updatedFields: List<String>,
)

/**
 * Data supplied by the device, never by the user's narration: who is reporting, when the report was made,
 * and where (GPS, null when unavailable). [anonymous] hides the reporter. See `doc/DATA_SCHEMA.md`.
 */
@Serializable
data class DeviceInfo(
    val reporter: String? = null,
    @SerialName("reported_at") val reportedAt: String,
    val gps: Gps? = null,
    val anonymous: Boolean = false,
)

/** GPS fix. */
@Serializable
data class Gps(val latitude: Double, val longitude: Double)

/** Records which tool call produced/consumes the fields. */
@Serializable
data class ToolCall(
    val name: String,
    @SerialName("arguments_ref") val argumentsRef: String,
)

/** A witness entry (element of `witness_information.value`). */
@Serializable
data class Witness(
    val name: String,
    val role: String? = null,
    val contact: String? = null,
)

/** How the user is reporting. [wire] is the JSON `input.mode` value. */
enum class InputMode(val wire: String) {
    VOICE("voice"),

    /** Voice plus photos picked from the gallery (ADR-053; reports saved by older builds say `voice_and_camera`, read as [VOICE_AND_PHOTO]). */
    VOICE_AND_PHOTO("voice_and_photo"),

    /** Photos only: the report is built from the photo description, no narration. */
    PHOTO_ONLY("photo_only");

    companion object {
        /** The mode with this wire value; the retired `voice_and_camera` is [VOICE_AND_PHOTO], anything unknown is [VOICE]. */
        fun fromWire(wire: String): InputMode = entries.firstOrNull { it.wire == wire }
            ?: if (wire == "voice_and_camera") VOICE_AND_PHOTO else VOICE
    }
}
