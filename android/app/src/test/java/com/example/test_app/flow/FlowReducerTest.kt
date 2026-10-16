package com.example.test_app.flow

import com.example.test_app.domain.model.Clarification
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InputMode
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.Validation
import com.example.test_app.domain.service.AudioInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FlowReducerTest {
    private val audio = AudioInput(File("x.wav"), 2000)

    private fun report(missing: List<String> = emptyList()) = IncidentReport(
        reportId = "INC-1", status = "x", createdAt = "t", input = ReportInput("voice", "t"), fields = emptyMap(),
        validation = Validation(missingFields = missing, isComplete = missing.isEmpty()),
        clarification = Clarification(),
    )

    private fun run(start: FlowState, vararg events: FlowEvent) = events.fold(start) { s, e -> FlowReducer.reduce(s, e) }

    @Test fun voiceStartsTheDialogueDirectly() {
        assertEquals(FlowState.Dialogue(Session(InputMode.VOICE)), FlowReducer.reduce(FlowState.Idle, FlowEvent.StartVoice))
    }

    @Test fun voiceHappyPathEndsInReviewing() {
        val s = run(
            FlowState.Idle,
            FlowEvent.StartVoice, FlowEvent.DialogueFinished(report()), FlowEvent.FormFilled(report()),
        )
        assertTrue(s is FlowState.Reviewing)
    }

    @Test fun dialogueFinishedWithMissingFieldsFillsTheFormInManualMode() {
        val r = report(missing = listOf("incident_location"))
        val s = run(FlowState.Idle, FlowEvent.StartVoice, FlowEvent.DialogueFinished(r))
        assertEquals(FlowState.FillingForm(r, manual = true), s)
    }

    @Test fun dialogueFinishedWithCompleteReportIsNotManual() {
        val r = report()
        assertEquals(FlowState.FillingForm(r, manual = false), run(FlowState.Idle, FlowEvent.StartVoice, FlowEvent.DialogueFinished(r)))
    }

    private val photos = listOf(
        MediaItem("media_01", "image", "file:///media/a.jpg", "2026-10-11T10:00:00+05:30"),
        MediaItem("media_02", "image", "file:///media/b.jpg", "2026-10-11T10:00:01+05:30"),
    )

    @Test fun galleryPhotosAreCarriedIntoTheVoiceDialogue() {
        val s = FlowReducer.reduce(FlowState.Idle, FlowEvent.StartVoiceWithPhotos(photos))
        assertEquals(FlowState.Dialogue(Session(InputMode.VOICE_AND_PHOTO, media = photos)), s)
    }

    @Test fun skipWithPhotosStartsAPhotoOnlyDialogue() {
        val s = FlowReducer.reduce(FlowState.Idle, FlowEvent.StartPhotoOnly(photos))
        assertEquals(FlowState.Dialogue(Session(InputMode.PHOTO_ONLY, media = photos)), s)
    }

    @Test fun startingWithNoPhotosIsJustAVoiceReport() {
        assertEquals(FlowState.Dialogue(Session(InputMode.VOICE)), FlowReducer.reduce(FlowState.Idle, FlowEvent.StartVoiceWithPhotos(emptyList())))
        assertEquals(FlowState.Dialogue(Session(InputMode.VOICE)), FlowReducer.reduce(FlowState.Idle, FlowEvent.StartPhotoOnly(emptyList())))
    }

    @Test fun photosCanStartTheDialogueFromAnErrorStateToo() {
        val err = FlowReducer.reduce(FlowState.Idle, FlowEvent.PermissionDenied)
        assertEquals(FlowState.Dialogue(Session(InputMode.VOICE_AND_PHOTO, media = photos)), FlowReducer.reduce(err, FlowEvent.StartVoiceWithPhotos(photos)))
    }

    @Test fun photosOnlyStartFromIdleOrError() {
        val d = FlowState.Dialogue(Session(InputMode.VOICE))
        assertEquals(d, FlowReducer.reduce(d, FlowEvent.StartVoiceWithPhotos(photos)))
        assertEquals(d, FlowReducer.reduce(d, FlowEvent.StartPhotoOnly(photos)))
    }

    @Test fun cancelAfterPickingPhotosGoesHome() {
        assertEquals(FlowState.Idle, run(FlowState.Idle, FlowEvent.StartVoiceWithPhotos(photos), FlowEvent.Cancel))
    }

    @Test fun inputModeReadsTheWireValueAndTheRetiredCameraValue() {
        assertEquals(InputMode.VOICE, InputMode.fromWire("voice"))
        assertEquals(InputMode.VOICE_AND_PHOTO, InputMode.fromWire("voice_and_photo"))
        assertEquals(InputMode.PHOTO_ONLY, InputMode.fromWire("photo_only"))
        assertEquals("a report saved by an older build", InputMode.VOICE_AND_PHOTO, InputMode.fromWire("voice_and_camera"))
        assertEquals(InputMode.VOICE, InputMode.fromWire("something else"))
    }

    @Test fun addMoreKeepsThePhotoMode() {
        val r = report().copy(input = ReportInput("voice_and_photo", "t", media = photos))
        val s = FlowReducer.reduce(FlowState.Reviewing(r), FlowEvent.AddMoreByVoice) as FlowState.AwaitingClarificationAnswer
        assertEquals(InputMode.VOICE_AND_PHOTO, s.session.mode)
        assertEquals(photos, s.session.media)
    }

    @Test fun dialogueFailuresRetryWithoutGreetingAgain() {
        val d = FlowState.Dialogue(Session(InputMode.VOICE))
        val retry = FlowState.Dialogue(Session(InputMode.VOICE, greeted = true))
        val extraction = FlowReducer.reduce(d, FlowEvent.ExtractionFailed("boom")) as FlowState.Error
        assertEquals(FlowError.EXTRACTION_FAILURE, extraction.kind)
        assertEquals(retry, FlowReducer.reduce(extraction, FlowEvent.Retry))
        val stt = FlowReducer.reduce(d, FlowEvent.SttFailed(null)) as FlowState.Error
        assertEquals(FlowError.STT_FAILURE, stt.kind)
        assertEquals(retry, FlowReducer.reduce(stt, FlowEvent.Retry))
        val rec = FlowReducer.reduce(d, FlowEvent.RecordingFailed("mic")) as FlowState.Error
        assertEquals(FlowError.RECORDING_FAILED, rec.kind)
        assertEquals(retry, FlowReducer.reduce(rec, FlowEvent.Retry))
    }

    @Test fun modelFailureHasItsOwnErrorKind() {
        val s = FlowReducer.reduce(FlowState.Dialogue(Session(InputMode.VOICE)), FlowEvent.ExtractionFailed("no model", model = true))
        assertEquals(FlowError.MODEL_FAILURE, (s as FlowState.Error).kind)
    }

    @Test fun dialogueIgnoresUnrelatedEvents() {
        val d = FlowState.Dialogue(Session(InputMode.VOICE))
        assertEquals(d, FlowReducer.reduce(d, FlowEvent.FormFilled(report())))
        assertEquals(d, FlowReducer.reduce(d, FlowEvent.StartVoice))
    }

    @Test fun cancelReturnsHomeFromAnywhereButNotWhileSubmitting() {
        assertEquals(FlowState.Idle, run(FlowState.Idle, FlowEvent.StartVoice, FlowEvent.Cancel))
        val submitting = FlowState.Submitting(report())
        assertEquals(submitting, FlowReducer.reduce(submitting, FlowEvent.Cancel))
    }

    @Test fun permissionDeniedShowsTheMicrophoneError() {
        val s = FlowReducer.reduce(FlowState.Idle, FlowEvent.PermissionDenied)
        assertEquals(FlowError.MIC_PERMISSION_DENIED, (s as FlowState.Error).kind)
    }

    @Test fun errorStateCanStartAgain() {
        val err = FlowReducer.reduce(FlowState.Idle, FlowEvent.PermissionDenied)
        assertEquals(FlowState.Dialogue(Session(InputMode.VOICE)), FlowReducer.reduce(err, FlowEvent.StartVoice))
    }

    // ---- add more by voice (the only remaining transcribe/extract/validate loop) ----

    @Test fun addMoreByVoiceCarriesCurrentReportAsContext() {
        val r = report()
        val s = FlowReducer.reduce(FlowState.Reviewing(r), FlowEvent.AddMoreByVoice) as FlowState.AwaitingClarificationAnswer
        assertEquals(r, s.session.report)
        assertTrue(s.question.targetFields.isEmpty())
    }

    @Test fun addMorePassReturnsToTheFormWithoutAskingQuestions() {
        val r = report(missing = listOf("incident_location"))
        var s: FlowState = run(FlowState.Reviewing(r), FlowEvent.AddMoreByVoice, FlowEvent.RecordingFinished(audio))
        assertTrue(s is FlowState.Transcribing)
        s = run(s, FlowEvent.TranscriptReady("also the projector"))
        val session = (s as FlowState.Extracting).session
        assertEquals("also the projector", session.pendingAnswer)
        assertEquals(FlowReducer.ADD_MORE_QUESTION, session.question)
        assertEquals(r, session.report)
        s = run(s, FlowEvent.ExtractionReady(r))
        assertTrue(s is FlowState.Validating)
        assertEquals(FlowState.FillingForm(r, manual = true), run(s, FlowEvent.ValidationDone(r)))
    }

    @Test fun skipDuringAddMoreJumpsToManualForm() {
        val r = report()
        val s = run(FlowState.Reviewing(r), FlowEvent.AddMoreByVoice, FlowEvent.SkipToManual)
        assertEquals(FlowState.FillingForm(r, manual = true), s)
    }

    @Test fun sttFailureInAddMoreRetriesWithSameAudio() {
        val t = run(FlowState.Reviewing(report()), FlowEvent.AddMoreByVoice, FlowEvent.RecordingFinished(audio))
        val err = FlowReducer.reduce(t, FlowEvent.SttFailed("boom"))
        assertTrue(err is FlowState.Error && err.kind == FlowError.STT_FAILURE && err.backState != null)
        assertEquals(t, FlowReducer.reduce(err, FlowEvent.Retry))
    }

    @Test fun extractionFailureAfterAReportExistsOffersManualForm() {
        val r = report(missing = listOf("injury_outcome"))
        val extracting = FlowState.Extracting(Session(InputMode.VOICE, report = r))
        val err = FlowReducer.reduce(extracting, FlowEvent.ExtractionFailed(null)) as FlowState.Error
        assertEquals(FlowState.FillingForm(r, manual = true), err.backState)
        val model = FlowReducer.reduce(extracting, FlowEvent.ExtractionFailed("x", model = true)) as FlowState.Error
        assertEquals(FlowError.MODEL_FAILURE, model.kind)
    }

    @Test fun submitBlockedWhileRequiredFieldsMissing() {
        val blocked = FlowState.Reviewing(report(missing = listOf("incident_location")))
        assertEquals(blocked, FlowReducer.reduce(blocked, FlowEvent.Submit))
        val ok = FlowState.Reviewing(report())
        assertTrue(FlowReducer.reduce(ok, FlowEvent.Submit) is FlowState.Submitting)
    }

    @Test fun submitSuccessThenReportAnotherReturnsHome() {
        val r = report()
        val s = run(FlowState.Reviewing(r), FlowEvent.Submit, FlowEvent.SubmitSucceeded(r), FlowEvent.ReportAnother)
        assertEquals(FlowState.Idle, s)
    }
}
