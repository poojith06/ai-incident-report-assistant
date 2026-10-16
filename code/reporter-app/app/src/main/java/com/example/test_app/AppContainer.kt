package com.example.test_app

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.test_app.ai.AiStatus
import com.example.test_app.ai.LlmIncidentExtractionService
import com.example.test_app.ai.LlmRephraser
import com.example.test_app.ai.genie.GenieLlmEngine
import com.example.test_app.ai.llama.GemmaImageAnalysis
import com.example.test_app.admin.AdminBackend
import com.example.test_app.admin.AdminPrefs
import com.example.test_app.admin.AnalysisSettings
import com.example.test_app.knowledgegraph.KnowledgeGraphService
import com.example.test_app.knowledgegraph.admin.AdminGraphService
import com.example.test_app.knowledgegraph.repository.AnalysisOutbox
import com.example.test_app.knowledgegraph.repository.FileAnalysisOutbox
import com.example.test_app.knowledgegraph.repository.FileAuditStore
import com.example.test_app.knowledgegraph.repository.FileGraphRepository
import com.example.test_app.knowledgegraph.repository.GraphRepository
import com.example.test_app.media.ImageCompressor
import com.example.test_app.ai.llama.GemmaNarrationExtractor
import com.example.test_app.ai.llama.GemmaSpeechToText
import com.example.test_app.ai.llama.LlamaGemmaConfig
import com.example.test_app.ai.llama.LlamaGemmaEngine
import com.example.test_app.ai.tts.RasaVoiceChoice
import com.example.test_app.ai.tts.SherpaModelFiles
import com.example.test_app.ai.tts.SherpaTtsService
import com.example.test_app.ai.whisper.WhisperCppSpeechToText
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.service.ImageHint
import com.example.test_app.mock.AiBackend
import com.example.test_app.dialogue.DialogueConfig
import com.example.test_app.dialogue.Rephraser
import com.example.test_app.domain.config.IncidentConfig
import com.example.test_app.domain.config.SpeechRate
import com.example.test_app.domain.config.SpokenLanguages
import com.example.test_app.domain.config.UserSettings
import com.example.test_app.domain.config.UserSettingsStore
import com.example.test_app.eval.EvalCases
import com.example.test_app.eval.EvalFinished
import com.example.test_app.eval.EvalInput
import com.example.test_app.eval.EvalMeta
import com.example.test_app.eval.EvalProgress
import com.example.test_app.eval.EvalReport
import com.example.test_app.eval.EvalRunner
import com.example.test_app.domain.service.DefaultValidationService
import com.example.test_app.domain.service.ImageAnalysisService
import com.example.test_app.domain.service.IncidentExtractionService
import com.example.test_app.domain.service.ReportRepository
import com.example.test_app.domain.service.AudioInput
import com.example.test_app.domain.service.SpeechToTextService
import com.example.test_app.domain.service.SttHint
import com.example.test_app.domain.service.Transcript
import com.example.test_app.domain.service.TextToSpeechService
import com.example.test_app.domain.service.ToolExecutor
import com.example.test_app.domain.service.ValidationService
import com.example.test_app.mock.DevSettings
import com.example.test_app.mock.DevSettingsStore
import com.example.test_app.mock.MockImageAnalysis
import com.example.test_app.mock.MockIncidentExtraction
import com.example.test_app.mock.MockSpeechToText
import com.example.test_app.mock.SttChoice
import com.example.test_app.platform.AndroidTextToSpeechService
import com.example.test_app.mock.TtsEngine
import com.example.test_app.platform.AudioRecorder
import com.example.test_app.platform.DeviceContext
import com.example.test_app.platform.DialogueAssetsLoader
import com.example.test_app.platform.JsonFileReportRepository
import com.example.test_app.platform.LocalToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manual dependency-injection container: the ONE place where service implementations are chosen.
 * The extraction service and the rephraser follow the developer-menu switch **Mock AI / Real AI**
 * (`DevSettings.useRealAi`, persisted); speech-to-text follows `DevSettings.sttChoice` (Mock, or the whisper.cpp CPU stopgap).
 * See `doc/INTEGRATION_GUIDE.md` and `doc/SERVICES_AND_MOCKS.md`.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("dev_settings", Context.MODE_PRIVATE)

    /** Developer settings; only the AI switches survive a restart. */
    val devSettings = DevSettingsStore(
        DevSettings(
            useRealAi = prefs.getBoolean(PREF_REAL_AI, false),
            useLlmRephrase = prefs.getBoolean(PREF_REPHRASE, true),
            sttChoice = prefs.getString(PREF_STT, null)?.let { n -> SttChoice.entries.firstOrNull { it.name == n } },
            whisperPlacePrompt = prefs.getBoolean(PREF_WHISPER_PROMPT, false),
            aiBackend = prefs.getString(PREF_BACKEND, null)?.let { n -> AiBackend.entries.firstOrNull { it.name == n } } ?: AiBackend.QWEN_WHISPER,
            gemmaOneCall = prefs.getBoolean(PREF_GEMMA_ONE_CALL, false),
            gemmaEncoderOnCpu = prefs.getBoolean(PREF_GEMMA_CPU_ENCODER, false),
            ttsEngine = prefs.getString(PREF_TTS_ENGINE, null)?.let { n -> TtsEngine.entries.firstOrNull { it.name == n } },
            rasaSpeaker = prefs.getInt(PREF_RASA_SPEAKER, -1).takeIf { it >= 0 },
            rasaEmotion = prefs.getInt(PREF_RASA_EMOTION, 0),
        ),
    ) { s ->
        prefs.edit().putBoolean(PREF_REAL_AI, s.useRealAi).putBoolean(PREF_REPHRASE, s.useLlmRephrase)
            .putBoolean(PREF_WHISPER_PROMPT, s.whisperPlacePrompt).putString(PREF_STT, s.sttChoice?.name)
            .putString(PREF_BACKEND, s.aiBackend.name).putBoolean(PREF_GEMMA_ONE_CALL, s.gemmaOneCall).putBoolean(PREF_GEMMA_CPU_ENCODER, s.gemmaEncoderOnCpu)
            .putString(PREF_TTS_ENGINE, s.ttsEngine?.name).putInt(PREF_RASA_EMOTION, s.rasaEmotion)
            .putInt(PREF_RASA_SPEAKER, s.rasaSpeaker ?: -1).apply()
    }

    private val userPrefs = appContext.getSharedPreferences("user_settings", Context.MODE_PRIVATE)

    /** The user's Settings-screen preferences (persisted). `readResultsAloud` moved here from the developer menu (one source of truth). */
    val userSettings = UserSettingsStore(
        UserSettings(
            followUpsEnabled = userPrefs.getBoolean(USER_FOLLOW_UPS, true),
            handsFreeAnswers = userPrefs.getBoolean(USER_HANDS_FREE, true),
            // the developer-menu switch of the same name (earlier builds) is the default, so nobody loses their choice
            readResultsAloud = userPrefs.getBoolean(USER_READ_ALOUD, prefs.getBoolean(PREF_READ_ALOUD, true)),
            speechRate = SpeechRate.fromName(userPrefs.getString(USER_SPEECH_RATE, null)),
            spokenLanguage = userPrefs.getString(USER_SPOKEN_LANGUAGE, null) ?: UserSettings.SPOKEN_AUTO,
            postIncidentAnalysis = userPrefs.getBoolean(USER_ANALYSIS, true),
        ),
    ) { s ->
        userPrefs.edit().putBoolean(USER_FOLLOW_UPS, s.followUpsEnabled).putBoolean(USER_HANDS_FREE, s.handsFreeAnswers)
            .putBoolean(USER_READ_ALOUD, s.readResultsAloud).putString(USER_SPEECH_RATE, s.speechRate.name)
            .putString(USER_SPOKEN_LANGUAGE, s.spokenLanguage).putBoolean(USER_ANALYSIS, s.postIncidentAnalysis).apply()
    }

    /** The languages of the "Spoken language" setting, read from `assets/dialogue/languages.json` (data, editable without code). */
    val spokenLanguages: List<String> by lazy {
        runCatching { SpokenLanguages.parse(appContext.assets.open("dialogue/languages.json").bufferedReader(Charsets.UTF_8).use { it.readText() }) }
            .getOrElse { Log.w("AppContainer", "languages.json unusable: ${it.message}"); emptyList() }
    }

    /** Current validation rules. */
    fun incidentConfig() = IncidentConfig()

    private val mockStt = MockSpeechToText(devSettings)

    /** The whisper.cpp CPU stopgap (NOT the NPU Whisper). Created lazily; the model file is `files/whisper/ggml-small.en.bin`. */
    val whisperStt: WhisperCppSpeechToText by lazy {
        val file = WhisperCppSpeechToText.findModel(appContext) ?: WhisperCppSpeechToText.candidateFiles(appContext).last()
        WhisperCppSpeechToText(file) { if (devSettings.current.whisperPlacePrompt) WhisperCppSpeechToText.PLACE_NAMES_PROMPT else null }
    }

    /** True when the whisper model file is on the device (so Whisper CPU can be the default). */
    fun whisperModelPresent(): Boolean = WhisperCppSpeechToText.findModel(appContext) != null

    // ---- the two Real AI backends: one resident at a time (the NPU is shared), built on demand, closed when deselected ----------------------

    /** Qwen3-4B via Genie with the text services on top of it. */
    private class QwenStack(val engine: GenieLlmEngine) {
        val extraction = LlmIncidentExtractionService(engine)
        val rephraser = LlmRephraser(engine)
    }

    /** Gemma 4 E2B via llama.cpp: one engine for text, speech and photos. */
    private inner class GemmaStack(val engine: LlamaGemmaEngine, val encoderOnCpu: Boolean) {
        val stt = GemmaSpeechToText(engine, forcedLanguage = { userSettings.current.forcedLanguage })
        val narration = GemmaNarrationExtractor(engine, stt, forcedLanguage = { userSettings.current.forcedLanguage })
        val imageAnalysis = GemmaImageAnalysis(engine)
        val extraction = LlmIncidentExtractionService(engine, precomputed = narration)
        val rephraser = LlmRephraser(engine)
    }

    @Volatile private var qwenStack: QwenStack? = null
    @Volatile private var gemmaStack: GemmaStack? = null

    @Synchronized private fun qwen(): QwenStack = qwenStack ?: QwenStack(GenieLlmEngine(appContext)).also { qwenStack = it }

    @Synchronized private fun gemma(): GemmaStack = gemmaStack ?: run {
        val cpu = devSettings.current.gemmaEncoderOnCpu
        GemmaStack(LlamaGemmaEngine(appContext, if (cpu) LlamaGemmaConfig(mmprojDevice = "none") else LlamaGemmaConfig()), cpu)
    }.also { gemmaStack = it }

    @Synchronized private fun releaseQwen() {
        qwenStack?.engine?.close()
        qwenStack = null
        if (loadedBackend == AiBackend.QWEN_WHISPER) loadedBackend = null
    }

    @Synchronized private fun releaseGemma() {
        gemmaStack?.engine?.close()
        gemmaStack = null
        if (loadedBackend == AiBackend.GEMMA) loadedBackend = null
    }

    /** True when the Gemma GGUF and mmproj are on the device (shown in the developer menu). */
    fun gemmaModelPresent(): Boolean = LlamaGemmaEngine.modelPresent(appContext)

    /** The speech-to-text in use now: the explicit developer-menu choice, else the default rule of the selected backend (Real AI only). */
    fun effectiveStt(): SttChoice {
        val s = devSettings.current
        val gemmaBackend = s.useRealAi && s.aiBackend == AiBackend.GEMMA
        return when (s.sttChoice) {
            SttChoice.MOCK -> SttChoice.MOCK
            SttChoice.WHISPER_CPU -> SttChoice.WHISPER_CPU
            SttChoice.GEMMA_AUDIO -> if (gemmaBackend) SttChoice.GEMMA_AUDIO else defaultStt(s.useRealAi, gemmaBackend)
            null -> defaultStt(s.useRealAi, gemmaBackend)
        }
    }

    private fun defaultStt(realAi: Boolean, gemmaBackend: Boolean): SttChoice = when {
        !realAi -> SttChoice.MOCK
        gemmaBackend -> SttChoice.GEMMA_AUDIO // native audio: no second model
        whisperModelPresent() -> SttChoice.WHISPER_CPU
        else -> SttChoice.MOCK
    }

    /** One-call narration (audio + photos -> transcript and extraction JSON) is active: Gemma backend and speech, switched on. */
    private fun oneCallActive(): Boolean = devSettings.current.gemmaOneCall && effectiveStt() == SttChoice.GEMMA_AUDIO

    /** Speech-to-text: always this object; it forwards to the mock, Whisper CPU or Gemma according to [effectiveStt] at call time. */
    val speechToText: SpeechToTextService = object : SpeechToTextService {
        override suspend fun transcribe(audio: AudioInput, hint: SttHint): Transcript = when (effectiveStt()) {
            SttChoice.WHISPER_CPU -> whisperStt.transcribe(audio, hint)
            SttChoice.GEMMA_AUDIO ->
                if (hint.expectedField == null && oneCallActive()) gemma().narration.transcribeAndExtract(audio, emptyList())
                else gemma().stt.transcribe(audio, hint)
            SttChoice.MOCK -> mockStt.transcribe(audio, hint)
        }
    }

    private val mockExtraction = MockIncidentExtraction(devSettings)

    /** Extraction service for the current AI mode. */
    val extraction: IncidentExtractionService
        get() = when {
            !devSettings.current.useRealAi -> mockExtraction
            devSettings.current.aiBackend == AiBackend.GEMMA -> gemma().extraction
            else -> qwen().extraction
        }

    /** Rephraser for the current AI mode; null = speak templates (Mock AI, or rephrasing switched off). */
    fun rephraser(): Rephraser? = when {
        !devSettings.current.useRealAi || !devSettings.current.useLlmRephrase -> null
        devSettings.current.aiBackend == AiBackend.GEMMA -> gemma().rephraser
        else -> qwen().rephraser
    }

    /** Photo analysis of the current AI mode: Gemma 4 vision (512 px) with the Gemma backend, else the mock. */
    val imageAnalysis: ImageAnalysisService = object : ImageAnalysisService {
        override suspend fun analyze(media: MediaItem): ImageHint =
            if (usesGemmaVision()) gemma().imageAnalysis.analyze(media) else mockImageAnalysis.analyze(media)
    }
    private val mockImageAnalysis = MockImageAnalysis()

    private fun usesGemmaVision() = devSettings.current.useRealAi && devSettings.current.aiBackend == AiBackend.GEMMA

    /**
     * What the dialogue uses to describe the attached gallery photos before extraction: Gemma's photo analysis (Real AI, Gemma backend)
     * or the mock (Mock AI); null with the Qwen backend, which has no vision model: its photos are only attached to the report.
     * Gallery photos never travel with the audio, so one-call narration does not change this.
     */
    fun photoAnalysis(): ImageAnalysisService? = if (usesGemmaVision() || !devSettings.current.useRealAi) imageAnalysis else null
    val validation: ValidationService = DefaultValidationService()
    private val androidTts = AndroidTextToSpeechService(appContext)

    /** Where the Sherpa voice models live (`files/tts/piper-amy`, `files/tts/rasa`; `doc/TTS.md`). */
    val ttsDir: File = File(appContext.filesDir, "tts")
    private val sherpaTts by lazy {
        SherpaTtsService(SherpaModelFiles(ttsDir), fallback = androidTts) { RasaVoiceChoice(devSettings.current.rasaSpeaker, devSettings.current.rasaEmotion) }
    }

    /** True when at least one Sherpa voice model is on the device (shown in the developer menu). */
    fun sherpaModelsPresent(): Boolean = SherpaTtsService.modelsPresent(ttsDir)

    /** The speech engine in use now: the explicit developer-menu choice, else Sherpa when its model files are on the device, else the system TTS. A Sherpa choice without files falls back too. */
    fun effectiveTts(): TtsEngine = when (devSettings.current.ttsEngine) {
        TtsEngine.SYSTEM -> TtsEngine.SYSTEM
        TtsEngine.SHERPA, null -> if (sherpaModelsPresent()) TtsEngine.SHERPA else TtsEngine.SYSTEM
    }

    /** Text-to-speech: always this object; it forwards to the system TTS or to Sherpa (which itself falls back to the system TTS per text) according to [effectiveTts] at call time. */
    val textToSpeech: TextToSpeechService = object : TextToSpeechService {
        private fun current(): TextToSpeechService = if (effectiveTts() == TtsEngine.SHERPA) sherpaTts else androidTts

        override suspend fun speak(text: String) = current().speak(text)
        override suspend fun speak(text: String, language: String?) = current().speak(text, language)

        override fun stop() {
            androidTts.stop()
            if (effectiveTts() == TtsEngine.SHERPA) sherpaTts.stop()
        }

        override fun shutdown() {
            androidTts.shutdown()
            if (effectiveTts() == TtsEngine.SHERPA) sherpaTts.shutdown()
        }

        override fun setSpeechRate(rate: Float) {
            androidTts.setSpeechRate(rate)
            if (effectiveTts() == TtsEngine.SHERPA) sherpaTts.setSpeechRate(rate)
        }
    }
    val repository: ReportRepository = JsonFileReportRepository(File(appContext.filesDir, "reports"))
    val toolExecutor: ToolExecutor = LocalToolExecutor(repository) { devSettings.current.toolDelayMs }

    /** Device-sourced report data (reporter, time, GPS). */
    val deviceContext = DeviceContext(appContext)

    /** Microphone capture (not swappable: it is real Android capture). */
    val audioRecorder = AudioRecorder(appContext)

    /** Directory of the compressed gallery photos attached to reports (the originals are never copied here). */
    val mediaDir: File = File(appContext.filesDir, "media").apply { mkdirs() }

    /** Compresses a picked photo to a <= 512 px JPEG in [mediaDir] (`media/ImageCompressor`). */
    val imageCompressor = ImageCompressor(appContext, mediaDir)

    /** Root of the offline knowledge graph files: `packs/<version>.json`, `active.txt`, `audit/`, `outbox/` (ADR-055). */
    val kgDir: File = File(appContext.filesDir, "kg")

    /** The graph packs (the seed pack in `assets/kg/seed_pack.json` is installed on first use). */
    val graphRepository: GraphRepository by lazy {
        FileGraphRepository(kgDir) { appContext.assets.open("kg/seed_pack.json").bufferedReader(Charsets.UTF_8).use { it.readText() } }
    }

    /** Analysis exports waiting for the future admin dashboard (`files/kg/outbox/<incidentId>.json`). */
    val analysisOutbox: AnalysisOutbox by lazy { FileAnalysisOutbox(File(kgDir, "outbox")) }

    private val adminPrefsStore = appContext.getSharedPreferences("admin_settings", Context.MODE_PRIVATE)

    /** The admin dashboard's small settings: PIN (demo gate), engine settings JSON, "also delete KG files with reports". */
    val adminPrefs: AdminPrefs = object : AdminPrefs {
        override var pin: String?
            get() = adminPrefsStore.getString("pin", null)
            set(v) { adminPrefsStore.edit().putString("pin", v).apply() }
        override var alsoDeleteKgFiles: Boolean
            get() = adminPrefsStore.getBoolean("also_delete_kg", true)
            set(v) { adminPrefsStore.edit().putBoolean("also_delete_kg", v).apply() }
        override var analysisSettingsJson: String?
            get() = adminPrefsStore.getString("analysis_settings", null)
            set(v) { adminPrefsStore.edit().putString("analysis_settings", v).apply() }
    }

    private val auditStore = FileAuditStore(File(kgDir, "audit"))

    /** Post-incident analysis (offline knowledge graph). Template rendering; the LLM re-phrasing path is not wired to the UI (ADR-056). Engine settings come from the admin dashboard. */
    val knowledgeGraph: KnowledgeGraphService by lazy {
        KnowledgeGraphService(graphRepository, auditStore, analysisOutbox, config = { AnalysisSettings.decode(adminPrefs.analysisSettingsJson).toEngineConfig() })
    }

    /** Admin API of the graph (import, export, approve, preview, rollback); no screen uses it yet (`doc/ADMIN_DASHBOARD_CONTRACT.md`). */
    val graphAdmin: AdminGraphService by lazy { AdminGraphService(graphRepository, config = AnalysisSettings.decode(adminPrefs.analysisSettingsJson).toEngineConfig()) }

    /** File and store access of the admin dashboard (ADR-058). */
    val adminBackend: AdminBackend by lazy {
        AdminBackend(repository, auditStore, dialogueLogDir, evalRunsDir, graphRepository, graphAdmin, analysisOutbox, knowledgeGraph)
    }

    /** Directory of the per-report dialogue JSONL logs (`doc/DIALOGUE.md`). */
    val dialogueLogDir: File = File(appContext.filesDir, "dialogue_logs")

    /** Follow-up dialogue assets (question bank, keyword lists), parsed and validated on first use. */
    val dialogueAssets by lazy { DialogueAssetsLoader.load(appContext) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _aiStatus = MutableStateFlow<AiStatus>(if (devSettings.current.useRealAi) AiStatus.Loading else AiStatus.Mock)

    /** Model loading state for the Home screen. */
    val aiStatus: StateFlow<AiStatus> = _aiStatus

    /**
     * Loads the real models once, off the main thread: the selected backend's LLM (Qwen3 via Genie, or Gemma 4 E2B via llama.cpp, which
     * also provides speech and photo understanding) and, when Whisper CPU is the effective speech-to-text, whisper.cpp. The other
     * backend's engine is released first (the NPU is shared; only one backend is resident). Safe to call again: it does nothing while
     * loading or when everything needed is loaded, and retries after a failure. Called at app start and when a switch changes.
     */
    fun ensureModelsLoading() {
        val s = devSettings.current
        val needQwen = s.useRealAi && s.aiBackend == AiBackend.QWEN_WHISPER
        val needGemma = s.useRealAi && s.aiBackend == AiBackend.GEMMA
        val needWhisper = effectiveStt() == SttChoice.WHISPER_CPU
        if (loadJob?.isActive != true) {
            if (!needQwen) releaseQwen()
            // the encoder device changed in the developer menu: rebuild the engine (the next load uses the new device)
            if (!needGemma || gemmaStack?.encoderOnCpu?.let { it != s.gemmaEncoderOnCpu } == true) releaseGemma()
        }
        if (!needQwen && !needGemma && !needWhisper) {
            _aiStatus.value = AiStatus.Mock
            return
        }
        if (loadJob?.isActive == true) return
        val wantedBackend = if (needGemma) AiBackend.GEMMA else if (needQwen) AiBackend.QWEN_WHISPER else null
        if (_aiStatus.value == AiStatus.Ready && loadedBackend == wantedBackend && (!needWhisper || whisperStt.isLoaded)) return
        _aiStatus.value = AiStatus.Loading
        loadJob = appScope.launch {
            _aiStatus.value = try {
                if (needWhisper) {
                    try {
                        whisperStt.load()
                    } catch (e: Exception) {
                        throw IllegalStateException("Speech recognition (Whisper CPU): ${e.message ?: "the model could not be loaded."}", e)
                    }
                }
                if (needGemma) {
                    val engine = gemma().engine
                    try {
                        engine.load()
                    } catch (e: Exception) {
                        throw IllegalStateException("Gemma 4: ${e.message ?: "the model could not be loaded."}", e)
                    } catch (e: LinkageError) { // missing or incompatible native libraries
                        throw IllegalStateException("Gemma 4: native libraries could not be loaded (${e.message}).", e)
                    }
                    loadedBackend = AiBackend.GEMMA
                }
                if (needQwen) {
                    qwen().engine.load()
                    loadedBackend = AiBackend.QWEN_WHISPER
                }
                AiStatus.Ready
            } catch (e: Exception) {
                AiStatus.Failed(e.message ?: "The on-device model could not be loaded.")
            }
        }
    }

    @Volatile private var loadedBackend: AiBackend? = null
    private var loadJob: Job? = null

    // ---- evaluation (Level 4, doc/TESTING.md) -----------------------------------------------------

    /** True when real speech recognition is selected and its model is loaded (Whisper CPU stopgap); otherwise audio eval runs are refused. */
    val hasRealStt: Boolean
        get() = when (effectiveStt()) {
            SttChoice.WHISPER_CPU -> whisperStt.isLoaded
            SttChoice.GEMMA_AUDIO -> gemmaStack?.engine?.isLoaded == true
            SttChoice.MOCK -> false
        }

    /**
     * Where the recordings are read from (`eval/README.md`): the external files dir `eval/` when it has an `audio` folder the app can
     * read, else the internal `files/eval/` (where `run-as ... cp` puts adb-pushed files on boards that deny the external path);
     * null when neither exists.
     */
    val evalDir: File?
        get() {
            val candidates = listOfNotNull(appContext.getExternalFilesDir(null), appContext.filesDir).map { File(it, "eval") }
            return candidates.firstOrNull { File(it, "audio").canRead() && File(it, "audio").list()?.isNotEmpty() == true } ?: candidates.firstOrNull()
        }

    /** One directory per run: `<evalRunsDir>/<run id>/results.json` + `results.md`. */
    val evalRunsDir: File = File(appContext.filesDir, "eval_runs")

    /**
     * Runs `assets/eval/cases.json` through the real dialogue pipeline with the services of the current AI mode and
     * writes the results. [EvalInput.TRANSCRIPT] types the case transcripts (no ASR); [EvalInput.AUDIO] needs a real
     * speech-to-text service and the WAV files in [evalDir].
     */
    suspend fun runEval(input: EvalInput, onProgress: (EvalProgress) -> Unit): EvalFinished = withContext(Dispatchers.Default) {
        val cases = EvalCases.parse(appContext.assets.open("eval/cases.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val settings = devSettings.current
        val runner = EvalRunner(
            assets = dialogueAssets,
            extraction = extraction,
            rephraser = rephraser(),
            stt = if (hasRealStt) speechToText else null,
            dialogueConfig = DialogueConfig(useLlmRephrase = settings.useRealAi && settings.useLlmRephrase),
            incidentConfig = incidentConfig(),
            imageAnalysis = photoAnalysis(),
            imageRoot = evalDir,
        )
        val version = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull()
        val meta = EvalMeta(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            appVersion = version ?: "unknown",
            llmModel = when {
                !settings.useRealAi -> "mock"
                settings.aiBackend == AiBackend.GEMMA -> "Gemma 4 E2B q4_0 (llama.cpp b11552, LLM on Hexagon NPU, mmproj on Adreno GPU)"
                else -> "Qwen3-4B (Genie, Hexagon NPU)"
            },
            asrModel = when (effectiveStt()) {
                SttChoice.GEMMA_AUDIO -> if (hasRealStt) "Gemma 4 E2B native audio (NPU + GPU)" else "none"
                SttChoice.WHISPER_CPU -> if (hasRealStt) "whisper.cpp ${whisperStt.modelName} (CPU stopgap, not NPU)" else "none"
                SttChoice.MOCK -> "none"
            },
            rephraseEnabled = settings.useRealAi && settings.useLlmRephrase,
        )
        val run = runner.run(cases, input, evalDir, meta, onProgress)
        val dir = EvalReport.write(run, evalRunsDir)
        EvalFinished(EvalReport.headline(run), File(dir, "results.json").absolutePath)
    }

    private companion object {
        const val PREF_REAL_AI = "real_ai"
        const val PREF_BACKEND = "ai_backend"
        const val PREF_GEMMA_ONE_CALL = "gemma_one_call"
        const val PREF_GEMMA_CPU_ENCODER = "gemma_cpu_encoder"
        const val PREF_TTS_ENGINE = "tts_engine"
        const val PREF_RASA_SPEAKER = "rasa_speaker"
        const val PREF_RASA_EMOTION = "rasa_emotion"
        const val PREF_REPHRASE = "rephrase"
        const val PREF_STT = "stt_choice"
        const val PREF_WHISPER_PROMPT = "whisper_place_prompt"
        const val PREF_READ_ALOUD = "read_results_aloud"
        const val USER_FOLLOW_UPS = "follow_ups"
        const val USER_HANDS_FREE = "hands_free"
        const val USER_READ_ALOUD = "read_results_aloud"
        const val USER_SPEECH_RATE = "speech_rate"
        const val USER_SPOKEN_LANGUAGE = "spoken_language"
        const val USER_ANALYSIS = "post_incident_analysis"
    }
}
