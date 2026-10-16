package com.example.test_app.flow

import com.example.test_app.domain.model.ClarificationQuestion
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InputMode
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.service.AudioInput

/** Data carried through the processing loop. See `doc/STATE_MACHINE.md`. */
data class Session(
    val mode: InputMode,
    /** Latest recorded audio (first utterance or latest answer). */
    val audio: AudioInput? = null,
    val media: List<MediaItem> = emptyList(),
    /** The first utterance, once transcribed. */
    val transcript: String? = null,
    /** The latest answer transcript, until the extraction pass has consumed it. */
    val pendingAnswer: String? = null,
    /** Report from the previous pass; null until the first extraction finishes. */
    val report: IncidentReport? = null,
    /** The question the user is answering (empty target list = "add more"; only used by the add-more pass). */
    val question: ClarificationQuestion? = null,
    /** True once the greeting was spoken: a retry after an error must not greet again. */
    val greeted: Boolean = false,
)

/** User-facing error categories. See `doc/STATE_MACHINE.md`. */
enum class FlowError {
    MIC_PERMISSION_DENIED,
    /** The on-device model could not be loaded (Error screen offers "Switch to Mock AI"). */
    MODEL_FAILURE,
    RECORDING_TOO_SHORT,
    RECORDING_FAILED,
    STT_FAILURE,
    EXTRACTION_FAILURE,
    SUBMIT_FAILURE,
}

/** States of the single flow state machine. One per spec state. See `doc/STATE_MACHINE.md`. */
sealed interface FlowState {
    data object Idle : FlowState

    /**
     * The follow-up dialogue (`DialogueManager`) owns the turn: greeting, listening, extraction, questions, read-back.
     * [Session.media] holds the compressed gallery photos attached before the dialogue started (ADR-053). See `doc/DIALOGUE.md`.
     */
    data class Dialogue(val session: Session) : FlowState

    /** "Add more by voice" passes (after the dialogue): transcribe, extract, validate. */
    data class Transcribing(val session: Session) : FlowState
    data class Extracting(val session: Session) : FlowState
    data class Validating(val session: Session) : FlowState
    data class AwaitingClarificationAnswer(val session: Session, val question: ClarificationQuestion) : FlowState

    /** [manual] = true when jumping to the form with missing fields (skip / max rounds / no question). */
    data class FillingForm(val report: IncidentReport, val manual: Boolean) : FlowState
    data class Reviewing(val report: IncidentReport) : FlowState
    data class Submitting(val report: IncidentReport) : FlowState
    data class Submitted(val report: IncidentReport) : FlowState

    /**
     * [retryState] is where Retry goes (null = Idle). [backState] is an optional "continue manually"
     * escape (the form), offered when a report already exists. [detail] is an optional service message.
     */
    data class Error(
        val kind: FlowError,
        val detail: String? = null,
        val retryState: FlowState? = null,
        val backState: FlowState? = null,
    ) : FlowState
}

/** Events accepted by [FlowReducer]. See `doc/STATE_MACHINE.md` for the transition table. */
sealed interface FlowEvent {
    data object StartVoice : FlowEvent

    /** The mic was tapped with gallery photos attached: the narration is listened for as usual, the photos ride along. */
    data class StartVoiceWithPhotos(val media: List<MediaItem>) : FlowEvent

    /** "Skip" with gallery photos attached: no narration, the report is built from the photo description alone. */
    data class StartPhotoOnly(val media: List<MediaItem>) : FlowEvent
    data object PermissionDenied : FlowEvent

    /** User cancelled. Valid from any state except Submitting/Submitted. */
    data object Cancel : FlowEvent

    /** Recording stopped OK (the add-more answer). */
    data class RecordingFinished(val audio: AudioInput) : FlowEvent
    data object RecordingTooShort : FlowEvent
    data class RecordingFailed(val detail: String?) : FlowEvent

    data class TranscriptReady(val text: String) : FlowEvent
    data class SttFailed(val detail: String?) : FlowEvent
    data class ExtractionReady(val report: IncidentReport) : FlowEvent
    /** [model] = the model could not be loaded ([FlowError.MODEL_FAILURE]) rather than failing on this input. */
    data class ExtractionFailed(val detail: String?, val model: Boolean = false) : FlowEvent

    /** [report] already has `validation` set (add-more passes). */
    data class ValidationDone(val report: IncidentReport) : FlowEvent

    /** The dialogue is over (or was skipped); [report] is validated and ready for the form. */
    data class DialogueFinished(val report: IncidentReport) : FlowEvent
    data object SkipToManual : FlowEvent
    data class FormFilled(val report: IncidentReport) : FlowEvent
    data object AddMoreByVoice : FlowEvent
    data class FieldEdited(val report: IncidentReport) : FlowEvent
    data object Submit : FlowEvent
    data class SubmitSucceeded(val report: IncidentReport) : FlowEvent
    data class SubmitFailed(val detail: String?) : FlowEvent
    data object Retry : FlowEvent
    data object GoBack : FlowEvent
    data object ReportAnother : FlowEvent
}

/** Pure transition function. Invalid (state, event) pairs return the state unchanged. */
object FlowReducer {
    val ADD_MORE_QUESTION = ClarificationQuestion(emptyList(), "What would you like to add?")

    fun reduce(state: FlowState, event: FlowEvent): FlowState {
        // Global events
        when (event) {
            is FlowEvent.Cancel -> return when (state) {
                is FlowState.Submitting, is FlowState.Submitted -> state
                else -> FlowState.Idle
            }
            is FlowEvent.ReportAnother -> return if (state is FlowState.Submitted) FlowState.Idle else state
            else -> Unit
        }
        return when (state) {
            FlowState.Idle -> when (event) {
                FlowEvent.StartVoice -> FlowState.Dialogue(Session(InputMode.VOICE))
                is FlowEvent.StartVoiceWithPhotos -> startWithPhotos(event.media, photoOnly = false)
                is FlowEvent.StartPhotoOnly -> startWithPhotos(event.media, photoOnly = true)
                FlowEvent.PermissionDenied -> permissionError()
                else -> state
            }
            is FlowState.Dialogue -> {
                val retry = FlowState.Dialogue(state.session.copy(greeted = true))
                when (event) {
                    is FlowEvent.DialogueFinished -> FlowState.FillingForm(event.report, manual = !event.report.validation.isComplete)
                    is FlowEvent.SttFailed -> FlowState.Error(FlowError.STT_FAILURE, event.detail, retry)
                    is FlowEvent.ExtractionFailed ->
                        FlowState.Error(if (event.model) FlowError.MODEL_FAILURE else FlowError.EXTRACTION_FAILURE, event.detail, retry)
                    is FlowEvent.RecordingFailed -> FlowState.Error(FlowError.RECORDING_FAILED, event.detail, retry)
                    FlowEvent.PermissionDenied -> permissionError()
                    else -> state
                }
            }
            is FlowState.Transcribing -> when (event) {
                is FlowEvent.TranscriptReady -> FlowState.Extracting(state.session.copy(pendingAnswer = event.text))
                is FlowEvent.SttFailed -> failure(FlowError.STT_FAILURE, event.detail, state, state.session)
                else -> state
            }
            is FlowState.Extracting -> when (event) {
                is FlowEvent.ExtractionReady -> FlowState.Validating(
                    state.session.copy(report = event.report, pendingAnswer = null),
                )
                is FlowEvent.ExtractionFailed -> failure(
                    if (event.model) FlowError.MODEL_FAILURE else FlowError.EXTRACTION_FAILURE, event.detail, state, state.session,
                )
                else -> state
            }
            is FlowState.Validating -> when (event) {
                is FlowEvent.ValidationDone -> FlowState.FillingForm(event.report, manual = !event.report.validation.isComplete)
                else -> state
            }
            is FlowState.AwaitingClarificationAnswer -> when (event) {
                is FlowEvent.RecordingFinished -> FlowState.Transcribing(
                    state.session.copy(audio = event.audio, question = state.question),
                )
                FlowEvent.RecordingTooShort -> FlowState.Error(FlowError.RECORDING_TOO_SHORT, null, state, skipState(state.session))
                is FlowEvent.RecordingFailed -> FlowState.Error(FlowError.RECORDING_FAILED, event.detail, state, skipState(state.session))
                FlowEvent.PermissionDenied -> FlowState.Error(FlowError.MIC_PERMISSION_DENIED, null, state, skipState(state.session))
                FlowEvent.SkipToManual -> skipState(state.session) ?: FlowState.Idle
                else -> state
            }
            is FlowState.FillingForm -> when (event) {
                is FlowEvent.FormFilled -> FlowState.Reviewing(event.report)
                else -> state
            }
            is FlowState.Reviewing -> when (event) {
                is FlowEvent.FieldEdited -> FlowState.Reviewing(event.report)
                FlowEvent.AddMoreByVoice -> {
                    val r = state.report
                    FlowState.AwaitingClarificationAnswer(
                        Session(
                            mode = InputMode.fromWire(r.input.mode),
                            media = r.input.media,
                            transcript = r.input.transcript,
                            report = r,
                            question = ADD_MORE_QUESTION,
                        ),
                        ADD_MORE_QUESTION,
                    )
                }
                FlowEvent.Submit -> if (state.report.validation.missingFields.isEmpty()) FlowState.Submitting(state.report) else state
                else -> state
            }
            is FlowState.Submitting -> when (event) {
                is FlowEvent.SubmitSucceeded -> FlowState.Submitted(event.report)
                is FlowEvent.SubmitFailed -> FlowState.Error(
                    FlowError.SUBMIT_FAILURE, event.detail, retryState = state, backState = FlowState.Reviewing(state.report),
                )
                else -> state
            }
            is FlowState.Submitted -> state
            is FlowState.Error -> when (event) {
                FlowEvent.Retry -> state.retryState ?: FlowState.Idle
                FlowEvent.GoBack -> state.backState ?: state
                FlowEvent.StartVoice -> FlowState.Dialogue(Session(InputMode.VOICE))
                is FlowEvent.StartVoiceWithPhotos -> startWithPhotos(event.media, photoOnly = false)
                is FlowEvent.StartPhotoOnly -> startWithPhotos(event.media, photoOnly = true)
                FlowEvent.PermissionDenied -> permissionError()
                else -> state
            }
        }
    }

    /** Photos attached from the gallery start the dialogue; without any photo the event is meaningless and the plain voice report starts. */
    private fun startWithPhotos(media: List<MediaItem>, photoOnly: Boolean): FlowState = FlowState.Dialogue(
        Session(
            mode = when {
                media.isEmpty() -> InputMode.VOICE
                photoOnly -> InputMode.PHOTO_ONLY
                else -> InputMode.VOICE_AND_PHOTO
            },
            media = media,
        ),
    )

    private fun permissionError() = FlowState.Error(FlowError.MIC_PERMISSION_DENIED)

    private fun failure(kind: FlowError, detail: String?, retry: FlowState, session: Session) =
        FlowState.Error(kind, detail, retry, skipState(session))

    /** The "continue manually" escape: only available once a report exists. */
    private fun skipState(session: Session): FlowState? =
        session.report?.let { FlowState.FillingForm(it, manual = true) }
}
