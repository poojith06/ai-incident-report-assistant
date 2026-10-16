package com.example.test_app.domain.service

import com.example.test_app.domain.config.IncidentConfig
import com.example.test_app.domain.model.ClarificationQuestion
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InputMode
import com.example.test_app.domain.model.MediaItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** A recorded audio clip (16 kHz mono PCM16 WAV). */
data class AudioInput(val file: File, val durationMs: Long)

/**
 * Result of speech-to-text. [confidence] is 0..1 (1 when the engine does not report one). [language] is the language the engine
 * reported, free-form (the model's own name lowercased, e.g. "tamil", "assamese", or `en`); blank = the engine did not say (the
 * dialogue then falls back to the script of the text, `LanguageDetect.detect`).
 */
data class Transcript(val text: String, val language: String = "", val confidence: Float = 1f)

/**
 * Extra context for STT. Real models MUST ignore it; mocks use [expectedField] to pick a scripted answer.
 * [expectedField] is null for the first report utterance, `"__add_more__"` for "add more by voice".
 */
data class SttHint(val expectedField: String? = null)

/** Thrown by [SpeechToTextService] when transcription fails. Message is shown to the user. */
class SpeechToTextException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown by [IncidentExtractionService] when the model fails. Message is shown to the user. */
open class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The on-device model could not be loaded or is unavailable (shown with "Switch to Mock AI"). */
class ModelLoadException(message: String, cause: Throwable? = null) : ExtractionException(message, cause)

/** Thrown by [TextToSpeechService] when speech output is unavailable. */
class TtsUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Audio to transcript. See `doc/SERVICES_AND_MOCKS.md`. */
interface SpeechToTextService {
    @Throws(SpeechToTextException::class)
    suspend fun transcribe(audio: AudioInput, hint: SttHint = SttHint()): Transcript
}

/** Descriptive hints for one image. */
data class ImageHint(val mediaId: String, val tags: List<String>, val description: String)

/** Image to descriptive hints. Optional. See `doc/SERVICES_AND_MOCKS.md`. */
interface ImageAnalysisService {
    suspend fun analyze(media: MediaItem): ImageHint
}

/** Everything the extraction model gets. See `doc/SERVICES_AND_MOCKS.md`. */
data class ExtractionRequest(
    val mode: InputMode,
    /** The first utterance. */
    val originalTranscript: String,
    /** Report from the previous pass (null on the first pass). */
    val previousReport: IncidentReport?,
    /** The user's latest spoken answer (null on the first pass). */
    val answerTranscript: String?,
    /** The question that was asked, if the answer responds to one. */
    val answeredQuestion: ClarificationQuestion?,
    val imageHints: List<ImageHint>,
    val media: List<MediaItem>,
    val config: IncidentConfig,
)

/**
 * Transcript + context to Incident JSON. Returns the RAW JSON string exactly as an LLM would emit it;
 * the app parses it with `IncidentJsonCodec`. See `doc/SERVICES_AND_MOCKS.md`.
 */
interface IncidentExtractionService {
    @Throws(ExtractionException::class)
    suspend fun extract(request: ExtractionRequest): String
}

/** Checks mandatory fields and confidence. REAL logic (not mocked). */
interface ValidationService {
    /** Returns [report] with `validation` and `status` recomputed. Pure and synchronous. */
    fun validate(report: IncidentReport, config: IncidentConfig): IncidentReport
}

/**
 * The follow-up planner's suggestion that rides along with a tool result (Spec v2 section 9).
 * [nextField] is null when nothing is left to ask; it is never a field that was already asked.
 */
data class PlannerHint(
    /** Dialogue fields still to ask (not filled, not asked, condition true). */
    val missing: List<String>,
    val nextField: String?,
    /** `PLAIN` / `CARE` / `URGENT`. */
    val mode: String,
    /** The safe default question for [nextField]; null when [nextField] is null. */
    val template: String?,
) {
    /** The JSON shape of Spec v2 section 9 (`ok` is added by [ToolResult]). */
    fun toJsonFields(): Map<String, JsonElement> = mapOf(
        "missing" to JsonArray(missing.map { JsonPrimitive(it) }),
        "next_field" to (nextField?.let { JsonPrimitive(it) } ?: JsonNull),
        "mode" to JsonPrimitive(mode),
        "template" to (template?.let { JsonPrimitive(it) } ?: JsonNull),
    )
}

/** Result of `fill_incident_form`: the filled report plus the planner hint (`{ok, missing, next_field, mode, template}`). */
data class ToolResult(val ok: Boolean, val report: IncidentReport, val hint: PlannerHint?) {
    fun toJson(): JsonObject = JsonObject(mapOf<String, JsonElement>("ok" to JsonPrimitive(ok)) + (hint?.toJsonFields() ?: emptyMap()))
}

/** Executes the app's tool calls. See `doc/SERVICES_AND_MOCKS.md`. */
interface ToolExecutor {
    /** `fill_incident_form(report)`: returns the report with `tool_call` set, ready for the form. */
    suspend fun fillIncidentForm(report: IncidentReport): IncidentReport

    /**
     * `fill_incident_form(report)` with the planner hint attached to the result (Spec v2 section 9). The dialogue
     * owns `askedFields`, so it computes the hint and the executor returns it with the filled report.
     */
    suspend fun fillIncidentFormWithHint(report: IncidentReport, hint: PlannerHint): ToolResult =
        ToolResult(ok = true, report = fillIncidentForm(report), hint = hint)

    /** `submit_incident_report(report)`: marks submitted, persists locally, returns the saved report. */
    suspend fun submitIncidentReport(report: IncidentReport): IncidentReport
}

/** Speaks text. See `doc/SERVICES_AND_MOCKS.md`. */
interface TextToSpeechService {
    /** Suspends until speech finishes. Throws [TtsUnavailableException] if no TTS is available. */
    @Throws(TtsUnavailableException::class)
    suspend fun speak(text: String)

    /**
     * Same as [speak] with the language the text is in (the speech model's name for it, e.g. "telugu", or `en`; null = unknown). Engines that
     * choose a voice by language use it; the default ignores it.
     */
    @Throws(TtsUnavailableException::class)
    suspend fun speak(text: String, language: String?) = speak(text)

    fun stop()
    fun shutdown()

    /** Speech rate for the next utterances (1.0 = normal); the user setting "Speech rate". Engines without a rate control may ignore it. */
    fun setSpeechRate(rate: Float) = Unit
}

/** Local report storage. */
interface ReportRepository {
    suspend fun save(report: IncidentReport)

    /** Newest first. */
    suspend fun list(): List<IncidentReport>

    /** Deletes every stored report ("Delete all past reports" in Settings) and returns how many were removed. */
    suspend fun deleteAll(): Int

    /** Deletes one stored report (the admin dashboard removes its demo reports with it); false when there was none. */
    suspend fun delete(reportId: String): Boolean
}
