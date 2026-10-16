package com.example.test_app.flow

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.test_app.AppContainer
import com.example.test_app.ai.AiStatus
import com.example.test_app.dialogue.AsrResult
import com.example.test_app.dialogue.DialogueConfig
import com.example.test_app.dialogue.DialogueLogger
import com.example.test_app.dialogue.DialogueManager
import com.example.test_app.dialogue.DialogueOutcome
import com.example.test_app.dialogue.DialogueState
import com.example.test_app.dialogue.DialogueUiState
import com.example.test_app.dialogue.FormReadout
import com.example.test_app.dialogue.LanguagePack
import com.example.test_app.domain.config.IncidentConfig
import com.example.test_app.domain.config.UserSettings
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InputMode
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.model.withField
import com.example.test_app.domain.service.ExtractionException
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.IncidentJsonCodec
import com.example.test_app.domain.service.IncidentPolicies
import com.example.test_app.domain.service.ModelLoadException
import com.example.test_app.domain.service.SpeechToTextException
import com.example.test_app.domain.service.SttHint
import com.example.test_app.domain.service.TtsUnavailableException
import com.example.test_app.knowledgegraph.AnalysisResult
import com.example.test_app.knowledgegraph.model.RecommendationSet
import com.example.test_app.media.ImageCompressionException
import com.example.test_app.media.PhotoPicks
import com.example.test_app.mock.ADD_MORE_HINT
import com.example.test_app.eval.EvalController
import com.example.test_app.mock.DevSettings
import com.example.test_app.mock.SttChoice
import com.example.test_app.mock.TtsEngine
import com.example.test_app.platform.MicPhase
import com.example.test_app.platform.MicSpeechIn
import com.example.test_app.platform.TtsSpeechOut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** State of the post-incident analysis card on the Success screen (ADR-055). */
sealed interface AnalysisUiState {
    data object None : AnalysisUiState
    data object Running : AnalysisUiState
    class Done(val set: RecommendationSet) : AnalysisUiState
    class Failed(val message: String) : AnalysisUiState
}

/** Which processing step the Process screen highlights. UI-only sub-progress; see `doc/UI_FLOW.md`. */
enum class ProcessingPhase { STT, LLM, JSON, VALIDATION, FORM }

/**
 * What the dialogue screens show: the manager's [ui] state plus whether the app is reading aloud ([speaking]) and what the
 * microphone side is doing ([mic]). See `doc/DIALOGUE.md`.
 */
data class DialogueView(
    val ui: DialogueUiState = DialogueUiState(),
    val speaking: Boolean = false,
    val mic: MicPhase = MicPhase.IDLE,
) {
    /** Greeting / waiting for the first narration / first extraction: shown on the Home frame (ADR-016). */
    val firstPass: Boolean
        get() = ui.state in FIRST_PASS_STATES

    private companion object {
        val FIRST_PASS_STATES = setOf(
            DialogueState.IDLE, DialogueState.GREETING, DialogueState.LISTEN_NARRATION,
            DialogueState.WAITING_FOR_TAP, DialogueState.EXTRACT,
        )
    }
}

/**
 * Runs the flow: owns the [FlowState], feeds events through [FlowReducer], and performs the side effects
 * (dialogue, services, recording) for each state. The voice path is owned by [DialogueManager] (greeting, ask-once
 * follow-ups, read-back); "Add more by voice" keeps the small transcribe/extract/validate loop.
 * See `doc/ARCHITECTURE.md`, `doc/STATE_MACHINE.md` and `doc/DIALOGUE.md`.
 */
class IncidentFlowViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<FlowState>(FlowState.Idle)
    val state: StateFlow<FlowState> = _state

    private val _phase = MutableStateFlow(ProcessingPhase.STT)
    val phase: StateFlow<ProcessingPhase> = _phase

    private val _heard = MutableStateFlow<String?>(null)

    /** Latest transcript (first utterance or answer), shown on the Process screen once STT is done. */
    val heard: StateFlow<String?> = _heard

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    private val _recording = MutableStateFlow(false)

    /** True while the microphone is capturing (add-more pass, or the dialogue listening). */
    val recording: StateFlow<Boolean> = _recording

    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())

    /** Photos attached from the gallery so far (compressed copies); shown as thumbnails above the mic until the report is over. */
    val photos: StateFlow<List<MediaItem>> = _media

    private val _analysis = MutableStateFlow<AnalysisUiState>(AnalysisUiState.None)

    /** The post-incident analysis of the report on the Success screen (ADR-055): none, running, done or failed. */
    val analysis: StateFlow<AnalysisUiState> = _analysis

    private var analysisJob: Job? = null

    private val _importingPhotos = MutableStateFlow(false)

    /** True while picked photos are being compressed. */
    val importingPhotos: StateFlow<Boolean> = _importingPhotos

    /** Photos can be described by a model (Gemma, or the mock): only then "continue with photos only" makes sense. */
    val canDescribePhotos: Boolean get() = container.photoAnalysis() != null

    private val _notice = MutableStateFlow<String?>(null)

    /** Non-blocking message (for example "voice read-out unavailable"). */
    val notice: StateFlow<String?> = _notice

    private val _history = MutableStateFlow<List<IncidentReport>>(emptyList())
    val history: StateFlow<List<IncidentReport>> = _history

    private val _readoutSpeaking = MutableStateFlow(false)

    /** True while the form read-out (Review) or the confirmation (Success) is being spoken; drives the speaker button. */
    val readoutSpeaking: StateFlow<Boolean> = _readoutSpeaking

    private val _dialogue = MutableStateFlow(DialogueView())

    /** State of the running follow-up dialogue (meaningful in [FlowState.Dialogue]). */
    val dialogue: StateFlow<DialogueView> = _dialogue

    val audioLevel: StateFlow<Float> = container.audioRecorder.level
    val devSettings: StateFlow<DevSettings> = container.devSettings.settings

    /** Model loading state ("Loading models..." on Home). */
    val aiStatus: StateFlow<AiStatus> = container.aiStatus

    /** Developer-menu evaluation runs ("Run eval set", `doc/TESTING.md` Level 4). */
    val eval = EvalController(viewModelScope, container::runEval)

    /** True when a real speech-to-text model exists, so the eval set can also be run from audio. */
    val hasRealStt: Boolean get() = container.hasRealStt
    val mediaDir: File get() = container.mediaDir

    private var effectJob: Job? = null
    private var speechJob: Job? = null
    private var readoutId = 0

    /** True from the end of a dialogue (its read-back statement was spoken) until the form is filled: no second read-out. */
    private var dialogueReadBack = false
    private var tickerJob: Job? = null
    private var observerJob: Job? = null
    private var stopping = false

    private var manager: DialogueManager? = null
    private var micIn: MicSpeechIn? = null
    private var speechOut: TtsSpeechOut? = null

    /** The user's Settings-screen preferences. */
    val userSettings: StateFlow<UserSettings> = container.userSettings.settings

    init {
        refreshHistory()
        // the speech rate setting applies to the TTS at once (and at start)
        viewModelScope.launch {
            container.userSettings.settings.collect { container.textToSpeech.setSpeechRate(it.speechRate.value) }
        }
    }

    /** The names offered by "Spoken language" (`assets/dialogue/languages.json`). */
    val spokenLanguages: List<String> get() = container.spokenLanguages

    /** Settings screen: change a user preference (persisted). */
    fun updateUserSettings(transform: (UserSettings) -> UserSettings) = container.userSettings.update(transform)

    /** Settings screen "Delete all past reports": removes every stored report, refreshes the list and returns how many were deleted. */
    fun deleteAllReports(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val ids = if (container.adminPrefs.alsoDeleteKgFiles) container.repository.list().map { it.reportId } else emptyList()
            val n = container.repository.deleteAll()
            if (ids.isNotEmpty()) runCatching { container.adminBackend.deleteKgFilesFor(ids) } // the analysis files of the deleted reports go too (admin setting)
            _history.value = container.repository.list()
            onDone(n)
        }
    }

    /** True while the flow is idle on Home: the only moment Settings may be opened (not while recording or processing). */
    val canOpenSettings: Boolean get() = _state.value is FlowState.Idle

    private fun config() = container.incidentConfig()

    // ---- public API used by the UI ----------------------------------------------------------------

    fun dispatch(event: FlowEvent) {
        val old = _state.value
        val new = FlowReducer.reduce(old, event)
        if (new == old) return
        _state.value = new
        if (event is FlowEvent.Cancel) discard(old)
        enter(new)
        // Read-outs are triggered by the event, not by entering the state: Reviewing is re-entered on every edit, and the
        // line must be spoken once per Review entry (FormFilled) and once after submit (SubmitSucceeded).
        val pack = packFor(readoutReport(new))
        ReadoutPolicy.textFor(event, new, dialogueReadBack, pack)?.let { readOut(it, pack.code, force = false) }
        if (event is FlowEvent.FormFilled) dialogueReadBack = false
    }

    /** Developer menu changes; switching to Real AI starts the (one-time) model load. */
    fun updateDevSettings(transform: (DevSettings) -> DevSettings) {
        container.devSettings.update(transform)
        container.ensureModelsLoading()
    }

    /** Error screen "Try again" after a model failure: retries the one-time load. */
    fun retryModelLoad() = container.ensureModelsLoading()

    /** Error screen "Switch to Mock AI": the demo path that always works. */
    fun switchToMockAi() {
        updateDevSettings { it.copy(useRealAi = false) }
        dispatch(FlowEvent.Cancel)
    }

    /** True when Whisper CPU is the speech-to-text in use (the Error screen then offers "Switch to Mock speech"). */
    val usesWhisperStt: Boolean get() = container.effectiveStt() == SttChoice.WHISPER_CPU

    /** True when a real speech-to-text (Whisper CPU or Gemma audio) is in use: the Error screen then offers "Switch to Mock speech". */
    val usesRealStt: Boolean get() = container.effectiveStt() != SttChoice.MOCK

    /** True when the whisper model file is on the device (the dev menu shows the default rule's outcome). */
    val whisperModelPresent: Boolean get() = container.whisperModelPresent()

    /** True when the Gemma GGUF and mmproj are on the device. */
    val gemmaModelPresent: Boolean get() = container.gemmaModelPresent()

    /** The speech-to-text in use now (the dev menu marks it). */
    val effectiveStt: SttChoice get() = container.effectiveStt()

    /** The speech output engine in use now (the dev menu marks it). */
    val effectiveTts: TtsEngine get() = container.effectiveTts()

    /** True when the Sherpa voice model files are on the device. */
    val sherpaModelsPresent: Boolean get() = container.sherpaModelsPresent()

    /** Error screen "Switch to Mock speech": the typed/scripted transcripts always work. */
    fun switchToMockStt() {
        updateDevSettings { it.copy(sttChoice = SttChoice.MOCK) }
        dispatch(FlowEvent.Cancel)
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * The system photo picker returned [uris] (possibly empty = cancelled: nothing changes). Only on the idle Home. Every photo is
     * compressed first (<= 512 px JPEG, `media/ImageCompressor`); at most [PhotoPicks.MAX_PHOTOS] are kept per report; a photo that
     * cannot be read is skipped with a banner message (never a crash).
     */
    fun onPhotosPicked(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value !is FlowState.Idle) return
        val decision = PhotoPicks.accept(_media.value.size, uris.size)
        val chosen = uris.take(decision.accepted)
        _notice.value = PhotoPicks.droppedNotice(decision)
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            _importingPhotos.value = true
            val failures = mutableListOf<String>()
            for (uri in chosen) {
                try {
                    val r = withContext(Dispatchers.IO) { container.imageCompressor.compress(uri) }
                    val item = MediaItem(
                        id = "media_%02d".format(_media.value.size + 1),
                        localUri = Uri.fromFile(r.file).toString(),
                        capturedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                    )
                    if (_state.value is FlowState.Idle) _media.update { it + item } else r.file.delete()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImageCompressionException) {
                    failures += e.message.orEmpty()
                } catch (e: Exception) {
                    failures += "I couldn't add that photo."
                }
            }
            _importingPhotos.value = false
            if (failures.isNotEmpty()) _notice.value = failures.distinct().joinToString(" ")
        }
    }

    /** The picker itself failed to start (no activity for it): a clear banner message, nothing attached. */
    fun onPickerFailed(detail: String?) {
        _notice.value = "I couldn't open the gallery" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }

    /** "Remove photos" on the idle Home: deletes every attached photo file. */
    fun clearPhotos() {
        if (_state.value !is FlowState.Idle) return
        _media.value.forEach { runCatching { File(java.net.URI(it.localUri)).delete() } }
        _media.value = emptyList()
    }

    /** The mic on Home (microphone permission already granted): the dialogue listens as usual and carries the attached photos, if any. */
    fun startVoice() = dispatch(if (_media.value.isEmpty()) FlowEvent.StartVoice else FlowEvent.StartVoiceWithPhotos(_media.value))

    /** "Continue with photos only": no narration; needs a model that can describe photos ([canDescribePhotos]). */
    fun startPhotoOnly() {
        if (_media.value.isEmpty() || !canDescribePhotos) return
        dispatch(FlowEvent.StartPhotoOnly(_media.value))
    }

    /** Stop button of the add-more pass. */
    fun stopRecording() {
        if (stopping || !container.audioRecorder.isRecording) return
        stopping = true
        viewModelScope.launch {
            val audio = container.audioRecorder.stop()
            stopTicker()
            _recording.value = false
            stopping = false
            when {
                audio == null -> dispatch(FlowEvent.RecordingFailed(null))
                audio.durationMs < config().minRecordingMs -> {
                    audio.file.delete()
                    dispatch(FlowEvent.RecordingTooShort)
                }
                else -> dispatch(FlowEvent.RecordingFinished(audio))
            }
        }
    }

    /**
     * The big mic button. In the dialogue it means: "I'm ready" while the hint is shown, "skip the spoken line" while the app
     * speaks, "I'm done" while it listens. In the add-more pass it starts/stops the recorder. The caller has already
     * checked the mic permission.
     */
    fun onMicTapped() {
        when (_state.value) {
            is FlowState.Dialogue -> {
                val v = _dialogue.value
                when {
                    v.ui.state == DialogueState.WAITING_FOR_TAP || v.ui.state == DialogueState.WAITING_FOR_ANSWER_TAP -> manager?.onMicTap()
                    v.speaking -> speechOut?.skip()
                    v.mic == MicPhase.LISTENING -> micIn?.stopNow()
                    else -> Unit
                }
            }
            is FlowState.AwaitingClarificationAnswer ->
                if (container.audioRecorder.isRecording) stopRecording() else startRecorder()
            else -> Unit
        }
    }

    /** "Skip and fill manually" during the dialogue: go to the form with what is known so far. */
    fun skipDialogue() {
        val s = _state.value as? FlowState.Dialogue ?: return
        val report = manager?.currentReportOrNull()
        effectJob?.cancel()
        stopSpeech()
        dialogueReadBack = false
        if (report == null) dispatch(FlowEvent.Cancel) else finishDialogue(report, s.session)
    }

    /** Speaker button on Review: stops the read-out when it is speaking, otherwise reads the CURRENT form aloud again. */
    fun toggleReadout() {
        if (_readoutSpeaking.value) {
            stopSpeech()
            return
        }
        val report = (_state.value as? FlowState.Reviewing)?.report ?: return
        val pack = packFor(report)
        readOut(FormReadout.review(report, pack), pack.code, force = true)
    }

    /** Edits one field on the Review form; revalidates immediately. An edit clears the field's dialogue status. */
    fun editField(key: String, value: JsonElement) {
        val s = _state.value as? FlowState.Reviewing ?: return
        stopSpeech() // the user is editing: the read-out of the old values stops
        val edited = s.report.withField(key, FieldEntry(value, 1.0, FieldSource.USER_EDIT))
        val validated = container.validation.validate(IncidentPolicies.apply(edited, cues = container.dialogueAssets.severityCues), config())
        dispatch(FlowEvent.FieldEdited(validated.copy(clarification = edited.clarification)))
    }

    /** Anonymous toggle on the Review form: hides the reporter in the submitted report. */
    fun setAnonymous(anonymous: Boolean) {
        val s = _state.value as? FlowState.Reviewing ?: return
        val device = s.report.device ?: container.deviceContext.snapshot()
        dispatch(FlowEvent.FieldEdited(s.report.copy(device = device.copy(anonymous = anonymous))))
    }

    fun refreshHistory() {
        viewModelScope.launch { _history.value = container.repository.list() }
    }

    override fun onCleared() {
        stopSpeech()
        container.textToSpeech.shutdown()
        viewModelScope.launch { container.audioRecorder.cancel() }
    }

    // ---- effects ----------------------------------------------------------------------------------

    private fun enter(state: FlowState) {
        effectJob?.cancel()
        // Leaving a listening state without stopping (for example "Skip and fill manually" while an answer is being
        // recorded) must release the microphone. The dialogue's own recorder is released by cancelling its job.
        if (container.audioRecorder.isRecording && !stopping &&
            state !is FlowState.AwaitingClarificationAnswer && state !is FlowState.Dialogue
        ) {
            stopTicker()
            _recording.value = false
            viewModelScope.launch { container.audioRecorder.cancel() }
        }
        if (state !is FlowState.Dialogue) stopDialogueObserver()
        if (state !is FlowState.Submitted) { analysisJob?.cancel(); _analysis.value = AnalysisUiState.None }
        // Any TTS ends when the flow leaves Review (submit, error, cancel, add more, recording...). Reviewing itself is re-entered on
        // edits and must keep (or already has stopped) its read-out; Submitted keeps the confirmation that dispatch() starts.
        if (state !is FlowState.Reviewing && state !is FlowState.Submitted) stopSpeech()
        when (state) {
            FlowState.Idle -> {
                stopSpeech(); _media.value = emptyList(); _heard.value = null
                manager = null; micIn = null; speechOut = null
                refreshHistory()
            }
            is FlowState.Dialogue -> {
                dialogueReadBack = false
                stopSpeech()
                container.devSettings.resetFailures()
                effectJob = viewModelScope.launch { runDialogue(state.session) }
            }
            is FlowState.Transcribing -> effectJob = viewModelScope.launch { transcribe(state.session) }
            is FlowState.Extracting -> effectJob = viewModelScope.launch { extract(state.session) }
            is FlowState.Validating -> effectJob = viewModelScope.launch { validate(state.session) }
            // The add-more answer starts recording by itself: no button press needed.
            is FlowState.AwaitingClarificationAnswer -> { stopSpeech(); startRecorder() }
            is FlowState.FillingForm -> effectJob = viewModelScope.launch { fillForm(state) }
            is FlowState.Submitting -> effectJob = viewModelScope.launch { submit(state.report) }
            is FlowState.Reviewing, is FlowState.Submitted, is FlowState.Error -> Unit
        }
    }

    // ---- dialogue ---------------------------------------------------------------------------------

    private fun newDialogue(): DialogueManager {
        val mic = MicSpeechIn(container.audioRecorder, container.speechToText, viewModelScope, config().minRecordingMs)
        val out = TtsSpeechOut(container.textToSpeech, onUnavailable = { _notice.value = TTS_NOTICE })
        val m = DialogueManager(
            assets = container.dialogueAssets,
            speechIn = mic,
            speechOut = out,
            extraction = container.extraction,
            config = container.userSettings.current.let { u ->
                DialogueConfig(
                    useLlmRephrase = container.devSettings.current.useLlmRephrase,
                    followUpsEnabled = u.followUpsEnabled, handsFreeAnswers = u.handsFreeAnswers,
                    language = u.forcedLanguage ?: "en",
                )
            },
            rephraser = container.rephraser(),
            toolExecutor = container.toolExecutor,
            logger = DialogueLogger(container.dialogueLogDir),
            gps = { container.deviceContext.snapshot().gps },
            incidentConfig = config(),
            imageAnalysis = container.photoAnalysis(),
        )
        micIn = mic
        speechOut = out
        manager = m
        _dialogue.value = DialogueView()
        observeDialogue(m, mic, out)
        return m
    }

    private fun observeDialogue(m: DialogueManager, mic: MicSpeechIn, out: TtsSpeechOut) {
        stopDialogueObserver()
        observerJob = viewModelScope.launch {
            combine(m.uiState, out.speaking, mic.phase) { ui, speaking, phase -> DialogueView(ui, speaking, phase) }
                .collect { v ->
                    _dialogue.value = v
                    _recording.value = v.mic == MicPhase.LISTENING
                    if (v.mic == MicPhase.LISTENING) startElapsedTicker() else stopTicker()
                    when {
                        v.mic == MicPhase.TRANSCRIBING -> _phase.value = ProcessingPhase.STT
                        v.ui.state == DialogueState.EXTRACT -> _phase.value = ProcessingPhase.LLM
                    }
                    v.ui.heard?.let { _heard.value = it }
                }
        }
    }

    private fun stopDialogueObserver() {
        observerJob?.cancel()
        observerJob = null
    }

    private suspend fun runDialogue(s: Session) {
        val m = newDialogue()
        try {
            // Photo only: no narration, an empty first transcript; the report is built from the photo description (ADR-053).
            val narration = if (s.mode == InputMode.PHOTO_ONLY) AsrResult("", 1f, "") else null
            when (val outcome = m.run(narration, s.mode, s.media, greet = !s.greeted)) {
                is DialogueOutcome.Review -> {
                    dialogueReadBack = true
                    finishDialogue(outcome.report, s)
                }
                DialogueOutcome.NoNarration -> {
                    _notice.value = "I didn't hear anything. Tap the mic and describe what happened."
                    dispatch(FlowEvent.Cancel)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelLoadException) {
            dispatch(FlowEvent.ExtractionFailed(e.message, model = true))
        } catch (e: ExtractionException) {
            dispatch(FlowEvent.ExtractionFailed(e.message))
        } catch (e: SpeechToTextException) {
            dispatch(FlowEvent.SttFailed(e.message))
        } catch (e: Exception) {
            dispatch(FlowEvent.RecordingFailed(e.message))
        }
    }

    /** Adds device info, runs the app-side policies and validation, then hands the report to the form. Ends with a dispatch. */
    private fun finishDialogue(report: IncidentReport, s: Session) {
        s.audio?.file?.delete()
        val prepared = IncidentPolicies.apply(
            report.copy(device = report.device ?: container.deviceContext.snapshot()), cues = container.dialogueAssets.severityCues,
        )
        dispatch(FlowEvent.DialogueFinished(container.validation.validate(prepared, config())))
    }

    // ---- recording (add-more pass) --------------------------------------------------

    private fun startRecorder() {
        stopSpeech() // the microphone and the TTS never run at the same time
        if (container.audioRecorder.isRecording) return
        try {
            container.audioRecorder.start(viewModelScope)
            _recording.value = true
            startTicker()
        } catch (e: Exception) {
            dispatch(FlowEvent.RecordingFailed(e.message))
        }
    }

    private fun startElapsedTicker() {
        if (tickerJob?.isActive == true) return
        val start = System.currentTimeMillis()
        _elapsedMs.value = 0
        tickerJob = viewModelScope.launch {
            while (true) {
                _elapsedMs.value = System.currentTimeMillis() - start
                delay(200)
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        val start = System.currentTimeMillis()
        _elapsedMs.value = 0
        var heardSpeech = false
        var silentSince = 0L
        tickerJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                _elapsedMs.value = now - start
                // Hands-free add-more: stop after a pause that follows speech. Tapping stop always works too.
                if (_state.value is FlowState.AwaitingClarificationAnswer) {
                    val level = container.audioRecorder.level.value
                    if (level >= SPEECH_LEVEL) { heardSpeech = true; silentSince = 0L }
                    else if (heardSpeech && level < SILENCE_LEVEL) {
                        if (silentSince == 0L) silentSince = now
                        if (now - silentSince >= SILENCE_STOP_MS && now - start >= MIN_AUTO_STOP_MS) { stopRecording(); break }
                    }
                }
                delay(200)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    // ---- add-more pass ----------------------------------------------------------------------------

    private suspend fun transcribe(s: Session) {
        _phase.value = ProcessingPhase.STT
        _heard.value = null
        val audio = s.audio ?: return dispatch(FlowEvent.SttFailed(null))
        try {
            val t = container.speechToText.transcribe(audio, SttHint(ADD_MORE_HINT))
            audio.file.delete()
            _heard.value = t.text
            dispatch(FlowEvent.TranscriptReady(t.text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpeechToTextException) {
            dispatch(FlowEvent.SttFailed(e.message))
        } catch (e: Exception) {
            dispatch(FlowEvent.SttFailed(null))
        }
    }

    private suspend fun extract(s: Session) {
        _phase.value = ProcessingPhase.LLM
        try {
            val raw = container.extraction.extract(
                ExtractionRequest(
                    mode = s.mode,
                    originalTranscript = s.transcript.orEmpty(),
                    previousReport = s.report,
                    answerTranscript = s.pendingAnswer,
                    answeredQuestion = s.question,
                    imageHints = emptyList(),
                    media = s.media,
                    config = config(),
                ),
            )
            _phase.value = ProcessingPhase.JSON
            delay(container.devSettings.current.jsonDelayMs)
            dispatch(FlowEvent.ExtractionReady(IncidentJsonCodec.decode(raw)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelLoadException) {
            dispatch(FlowEvent.ExtractionFailed(e.message, model = true))
        } catch (e: ExtractionException) {
            dispatch(FlowEvent.ExtractionFailed(e.message))
        } catch (e: Exception) {
            dispatch(FlowEvent.ExtractionFailed(null))
        }
    }

    private suspend fun validate(s: Session) {
        _phase.value = ProcessingPhase.VALIDATION
        delay(container.devSettings.current.validationDelayMs)
        val raw = s.report ?: return
        val prepared = IncidentPolicies.apply(
            raw.copy(device = raw.device ?: container.deviceContext.snapshot()), cues = container.dialogueAssets.severityCues,
        )
        dispatch(FlowEvent.ValidationDone(container.validation.validate(prepared, config())))
    }

    private suspend fun fillForm(s: FlowState.FillingForm) {
        _phase.value = ProcessingPhase.FORM
        // Nothing is spoken here: the dialogue's read-back already told the user what was logged (DialogueManager is
        // the only thing that decides what is spoken).
        val filled = container.toolExecutor.fillIncidentForm(s.report)
        dispatch(FlowEvent.FormFilled(filled))
    }

    private suspend fun submit(report: IncidentReport) {
        try {
            val saved = container.toolExecutor.submitIncidentReport(report)
            // The confirmation line is spoken by dispatch() on SubmitSucceeded (FormReadout.submitted).
            dispatch(FlowEvent.SubmitSucceeded(saved))
            startAnalysis(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(FlowEvent.SubmitFailed(e.message))
        }
    }

    /**
     * Post-incident analysis (ADR-055): runs off the main thread AFTER the submission has succeeded and cannot change it; any failure is
     * only a state shown on the Success screen. Skipped when the Settings switch is off.
     */
    private fun startAnalysis(saved: IncidentReport) {
        analysisJob?.cancel()
        if (!container.userSettings.current.postIncidentAnalysis) { _analysis.value = AnalysisUiState.None; return }
        _analysis.value = AnalysisUiState.Running
        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            val result = try {
                container.knowledgeGraph.analyze(saved, container.repository.list())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnalysisResult.Failed(e.message ?: "analysis failed")
            }
            _analysis.value = when (result) {
                is AnalysisResult.Done -> AnalysisUiState.Done(result.set)
                is AnalysisResult.Failed -> AnalysisUiState.Failed(result.message)
            }
        }
    }

    private fun stopSpeech() {
        readoutId++
        _readoutSpeaking.value = false
        speechJob?.cancel()
        speechJob = null
        container.textToSpeech.stop()
    }

    /**
     * Speaks [text] through the offline Android TTS (fire-and-forget; a TTS failure only shows the banner). Skipped when
     * "Read results aloud" is off ([force] = the speaker button) or while the microphone records.
     */
    private fun readOut(text: String, language: String, force: Boolean) {
        if (!force && !container.userSettings.current.readResultsAloud) return
        if (container.audioRecorder.isRecording) return
        stopSpeech()
        val id = readoutId
        speechJob = viewModelScope.launch {
            _readoutSpeaking.value = true
            try {
                container.textToSpeech.speak(text, language)
            } catch (e: TtsUnavailableException) {
                _notice.value = TTS_NOTICE
            } finally {
                if (id == readoutId) _readoutSpeaking.value = false
            }
        }
    }

    /** The language pack of a report's read-out: the language of the session (`input.language`), else the forced spoken-language setting, else English. */
    private fun packFor(report: IncidentReport?): LanguagePack =
        container.dialogueAssets.pack(report?.input?.language?.takeIf { it.isNotBlank() } ?: container.userSettings.current.forcedLanguage)

    private fun readoutReport(state: FlowState): IncidentReport? = (state as? FlowState.Reviewing)?.report ?: (state as? FlowState.Submitted)?.report

    /** Cleans up files of an abandoned session. Never touches submitted reports. */
    private fun discard(old: FlowState) {
        viewModelScope.launch { container.audioRecorder.cancel() }
        stopTicker()
        _recording.value = false
        stopping = false
        val media = when (old) {
            is FlowState.Dialogue -> { old.session.audio?.file?.delete(); old.session.media }
            is FlowState.Transcribing -> old.session.media
            is FlowState.Extracting -> old.session.media
            is FlowState.Validating -> old.session.media
            is FlowState.AwaitingClarificationAnswer -> old.session.media
            is FlowState.FillingForm -> old.report.input.media
            is FlowState.Reviewing -> old.report.input.media
            else -> emptyList()
        }
        media.forEach { runCatching { File(java.net.URI(it.localUri)).delete() } }
        _media.value = emptyList()
    }

    private companion object {
        const val TTS_NOTICE = "Voice read-out isn't available on this device. Please read the text on screen."
        const val SPEECH_LEVEL = 0.10f
        const val SILENCE_LEVEL = 0.06f
        const val SILENCE_STOP_MS = 2000L
        const val MIN_AUTO_STOP_MS = 1500L
    }
}
