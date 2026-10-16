package com.example.test_app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.ai.AiStatus
import com.example.test_app.dialogue.DialogueState
import com.example.test_app.domain.config.EmergencyContacts
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.flow.DialogueView
import com.example.test_app.flow.FlowError
import com.example.test_app.flow.FlowEvent
import com.example.test_app.flow.FlowState
import com.example.test_app.flow.IncidentFlowViewModel
import com.example.test_app.media.PhotoPicks
import com.example.test_app.mock.SttChoice
import com.example.test_app.platform.MicPhase
import com.example.test_app.ui.admin.AdminDashboardScreen
import com.example.test_app.ui.admin.AdminPinDialog
import com.example.test_app.ui.clarification.ClarificationScreen
import com.example.test_app.ui.components.SpokenLanguageSheet
import com.example.test_app.ui.dialogue.DialogueScreen
import com.example.test_app.ui.error.ErrorScreen
import com.example.test_app.ui.home.DevMenuSheet
import com.example.test_app.ui.home.HistoryScreen
import com.example.test_app.ui.home.HomeMode
import com.example.test_app.ui.home.HomeScreen
import com.example.test_app.ui.processing.ProcessingScreen
import com.example.test_app.ui.review.ReviewScreen
import com.example.test_app.ui.settings.AboutInfo
import com.example.test_app.ui.settings.SettingsScreen
import com.example.test_app.ui.success.SuccessScreen
import com.example.test_app.ui.theme.AppTheme

private enum class Screen { HOME, HISTORY, SETTINGS, ADMIN, PROCESSING, ADD_MORE, DIALOGUE, REVIEW, SUCCESS, ERROR }

/**
 * Root composable. Maps the single [FlowState] to a screen and owns runtime-permission requests.
 * No other navigation framework is used (`doc/DECISIONS.md` ADR-003). See `doc/UI_FLOW.md`.
 */
@Composable
fun AppRoot(vm: IncidentFlowViewModel, admin: AdminViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val level by vm.audioLevel.collectAsStateWithLifecycle()
    val elapsed by vm.elapsedMs.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val importingPhotos by vm.importingPhotos.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val heard by vm.heard.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val dev by vm.devSettings.collectAsStateWithLifecycle()
    val dialogue by vm.dialogue.collectAsStateWithLifecycle()
    val aiStatus by vm.aiStatus.collectAsStateWithLifecycle()
    val readoutSpeaking by vm.readoutSpeaking.collectAsStateWithLifecycle()
    val evalState by vm.eval.state.collectAsStateWithLifecycle()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showDev by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var adminOpen by rememberSaveable { mutableStateOf(false) }
    var showAdminPin by rememberSaveable { mutableStateOf(false) }
    val adminState by admin.state.collectAsStateWithLifecycle()
    val userSettings by vm.userSettings.collectAsStateWithLifecycle()

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val s = vm.state.value
        when {
            !ok -> vm.dispatch(FlowEvent.PermissionDenied)
            s is FlowState.Error && s.retryState != null -> vm.dispatch(FlowEvent.Retry)
            s is FlowState.AwaitingClarificationAnswer -> vm.onMicTapped()
            else -> vm.startVoice()
        }
    }

    // Gallery: the system photo picker (no storage permission). Cancel returns an empty list = nothing changes.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoPicks.MAX_PHOTOS)) { uris ->
        vm.onPhotosPicked(uris)
    }
    // API < 33 without the photo-picker backport: the plain "get content" chooser (cannot limit the count; the view model cuts it)
    val legacyPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> vm.onPhotosPicked(uris) }

    fun openGallery() {
        try {
            if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } else {
                legacyPicker.launch("image/*")
            }
        } catch (e: Exception) { // no activity can handle the request, or the launcher is in a bad state: banner, no crash
            vm.onPickerFailed(e.message)
        }
    }

    fun startVoice() {
        if (granted(Manifest.permission.RECORD_AUDIO)) vm.startVoice()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun addMoreMic() {
        if (recording || granted(Manifest.permission.RECORD_AUDIO)) vm.onMicTapped()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Voice path: Idle and the dialogue's first pass (greeting, listening, first extraction) share one Home frame (the mic
    // turns into stop, then a spinner, in place). Once the first narration is understood the dialogue screen takes over.
    val screen = when (val s = state) {
        FlowState.Idle -> if (adminOpen && adminState.unlocked) Screen.ADMIN else if (showSettings) Screen.SETTINGS else if (showHistory) Screen.HISTORY else Screen.HOME
        is FlowState.Dialogue -> if (dialogue.firstPass) Screen.HOME else Screen.DIALOGUE
        is FlowState.Transcribing, is FlowState.Extracting, is FlowState.Validating, is FlowState.FillingForm -> Screen.PROCESSING
        is FlowState.AwaitingClarificationAnswer -> Screen.ADD_MORE
        is FlowState.Reviewing, is FlowState.Submitting -> Screen.REVIEW
        is FlowState.Submitted -> Screen.SUCCESS
        is FlowState.Error -> Screen.ERROR
    }
    // "Spoken language" only reaches the Gemma speech model; Whisper small.en listens in English, the mock ignores the audio
    val languageUsable = vm.effectiveStt == SttChoice.GEMMA_AUDIO
    val languageNote = when {
        languageUsable -> null
        vm.effectiveStt == SttChoice.WHISPER_CPU -> "Whisper small.en listens in English only"
        else -> "Only the Gemma speech model uses this setting"
    }
    val homeMode = homeMode(state, dialogue)
    val homeCaption = when {
        state !is FlowState.Dialogue -> null
        dialogue.ui.showMicHint -> "Tap the mic when you're ready."
        homeMode == HomeMode.SPEAKING -> dialogue.ui.lastSpoken
        else -> null
    }

    BackHandler(enabled = screen != Screen.HOME || state !is FlowState.Idle) {
        when (screen) {
            Screen.HISTORY -> showHistory = false
            Screen.SETTINGS -> showSettings = false
            Screen.ADMIN -> { admin.lock(); adminOpen = false }
            Screen.REVIEW -> Unit // use the explicit discard button
            Screen.SUCCESS -> vm.dispatch(FlowEvent.ReportAnother)
            else -> vm.dispatch(FlowEvent.Cancel)
        }
    }

    Crossfade(
        targetState = screen,
        animationSpec = tween(if (AppTheme.reduceMotion) 0 else AppTheme.motion.medium),
        label = "screen",
    ) { target ->
        when (target) {
            Screen.HOME -> HomeScreen(
                mode = homeMode, reportCount = history.size, notice = notice, caption = homeCaption, aiStatus = aiStatus,
                elapsedMs = elapsed, level = level, phase = phase, transcript = heard, onDismissNotice = vm::dismissNotice,
                onMic = { if (state is FlowState.Idle) { if (!importingPhotos) startVoice() } else vm.onMicTapped() },
                onStop = vm::onMicTapped, onCancel = { vm.dispatch(FlowEvent.Cancel) },
                spokenLanguage = if (languageUsable) userSettings.spokenLanguage else null, onLanguage = { showLanguage = true },
                photos = photos, importingPhotos = importingPhotos, canDescribePhotos = vm.canDescribePhotos,
                onGallery = ::openGallery, onClearPhotos = vm::clearPhotos, onPhotoOnly = vm::startPhotoOnly,
                onHistory = { vm.refreshHistory(); showHistory = true }, onOpenDevMenu = { showDev = true },
                // the gear is only there on the idle Home: never while recording or processing
                onSettings = if (state is FlowState.Idle) ({ vm.refreshHistory(); showSettings = true }) else null,
                onAdmin = if (state is FlowState.Idle) ({ showAdminPin = true }) else null,
            )
            Screen.SETTINGS -> {
                val ai = when {
                    !dev.useRealAi -> "Mock AI" to "Not needed (mock)"
                    else -> "${dev.aiBackend.label} (Real AI)" to when (val a = aiStatus) {
                        AiStatus.Loading -> "Loading..."
                        AiStatus.Ready -> "Loaded"
                        is AiStatus.Failed -> "Failed: ${a.message}"
                        AiStatus.Mock -> "Not needed (mock)"
                    }
                }
                val version = remember(context) {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
                }
                val photos = when {
                    !dev.useRealAi -> "Described by the mock (no model)"
                    dev.aiBackend == com.example.test_app.mock.AiBackend.GEMMA -> "Described by Gemma (shrunk to 512 px)"
                    else -> "Attached only (this model cannot see photos)"
                }
                SettingsScreen(
                    spokenLanguage = userSettings.spokenLanguage, languageEnabled = languageUsable, languageNote = languageNote,
                    onSpokenLanguage = { showLanguage = true },
                    settings = userSettings, onChange = vm::updateUserSettings, contacts = EmergencyContacts.DUMMY,
                    reportCount = history.size, onDeleteAllReports = { vm.deleteAllReports() },
                    about = AboutInfo(appVersion = version, schemaVersion = IncidentReport.SCHEMA_VERSION, aiBackend = ai.first, modelsState = ai.second, photos = photos),
                    onBack = { showSettings = false },
                )
            }
            Screen.HISTORY -> HistoryScreen(history, onBack = { showHistory = false })
            Screen.ADMIN -> AdminDashboardScreen(adminState, admin, onExit = { adminOpen = false })
            Screen.PROCESSING -> ProcessingScreen(
                phase = phase, transcript = heard, roundLabel = "Processing",
                onCancel = { vm.dispatch(FlowEvent.Cancel) },
            )
            Screen.DIALOGUE -> DialogueScreen(
                view = dialogue, recording = recording, level = level, elapsedMs = elapsed,
                notice = notice, onDismissNotice = vm::dismissNotice,
                onMic = vm::onMicTapped, onSkip = vm::skipDialogue, onCancel = { vm.dispatch(FlowEvent.Cancel) },
            )
            Screen.ADD_MORE -> {
                val question = (state as? FlowState.AwaitingClarificationAnswer)?.question ?: return@Crossfade
                ClarificationScreen(
                    question = question, recording = recording, level = level, elapsedMs = elapsed,
                    notice = notice, onDismissNotice = vm::dismissNotice,
                    onMic = ::addMoreMic,
                    onSkip = { vm.dispatch(FlowEvent.SkipToManual) },
                    onCancel = { vm.dispatch(FlowEvent.Cancel) },
                )
            }
            Screen.REVIEW -> {
                val report = (state as? FlowState.Reviewing)?.report ?: (state as? FlowState.Submitting)?.report ?: return@Crossfade
                ReviewScreen(
                    report = report, submitting = state is FlowState.Submitting, speaking = readoutSpeaking,
                    notice = notice, onDismissNotice = vm::dismissNotice,
                    onEdit = vm::editField, onReadAloud = vm::toggleReadout, onAnonymous = vm::setAnonymous,
                    onAddMore = { vm.dispatch(FlowEvent.AddMoreByVoice) },
                    onSubmit = { vm.dispatch(FlowEvent.Submit) },
                    onDiscard = { vm.dispatch(FlowEvent.Cancel) },
                )
            }
            Screen.SUCCESS -> (state as? FlowState.Submitted)?.let { SuccessScreen(it.report, analysis) { vm.dispatch(FlowEvent.ReportAnother) } }
            Screen.ERROR -> (state as? FlowState.Error)?.let { err ->
                ErrorScreen(
                    error = err,
                    onRetry = {
                        when {
                            err.kind == FlowError.MIC_PERMISSION_DENIED ->
                                if (err.retryState != null && granted(Manifest.permission.RECORD_AUDIO)) vm.dispatch(FlowEvent.Retry) else startVoice()
                            else -> {
                                vm.retryModelLoad() // re-arms the model load when Real AI failed to load
                                vm.dispatch(FlowEvent.Retry)
                            }
                        }
                    },
                    onBackToForm = { vm.dispatch(FlowEvent.GoBack) },
                    onCancel = { vm.dispatch(FlowEvent.Cancel) },
                    onSwitchToMock = if (dev.useRealAi) vm::switchToMockAi else null,
                    onSwitchToMockStt = if (vm.usesRealStt) vm::switchToMockStt else null,
                )
            }
        }
    }

    if (showAdminPin) {
        AdminPinDialog(
            onCheck = admin::unlock, onSuccess = { showAdminPin = false; adminOpen = true }, onDismiss = { showAdminPin = false },
        )
    }

    if (showLanguage) {
        SpokenLanguageSheet(
            languages = vm.spokenLanguages, selected = userSettings.spokenLanguage,
            onSelect = { name -> vm.updateUserSettings { it.copy(spokenLanguage = name) } }, onDismiss = { showLanguage = false },
        )
    }

    if (showDev) {
        DevMenuSheet(
            settings = dev, onChange = vm::updateDevSettings, onDismiss = { showDev = false },
            eval = evalState, audioEvalAvailable = vm.hasRealStt, whisperModelPresent = vm.whisperModelPresent, gemmaModelPresent = vm.gemmaModelPresent,
            effectiveStt = vm.effectiveStt, effectiveTts = vm.effectiveTts, sherpaModelsPresent = vm.sherpaModelsPresent, onRunEval = vm.eval::start,
        )
    }
}

/** Which Home mode the current flow state needs (only meaningful when the screen is HOME). */
private fun homeMode(state: FlowState, dialogue: DialogueView): HomeMode = when (state) {
    FlowState.Idle -> HomeMode.IDLE
    is FlowState.Dialogue -> when {
        dialogue.ui.state == DialogueState.WAITING_FOR_TAP -> HomeMode.IDLE
        dialogue.mic == MicPhase.LISTENING -> HomeMode.RECORDING
        dialogue.mic == MicPhase.TRANSCRIBING || dialogue.ui.state == DialogueState.EXTRACT -> HomeMode.PROCESSING
        else -> HomeMode.SPEAKING
    }
    else -> HomeMode.PROCESSING
}
