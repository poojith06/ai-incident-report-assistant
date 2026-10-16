package com.example.test_app.mock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Demo scenarios selectable from the hidden developer menu. See `doc/SERVICES_AND_MOCKS.md`. */
enum class MockScenario(val label: String, val description: String) {
    COMPLETE("Complete report", "Everything extracted on the first pass: no questions."),
    INJURY_FOLLOW_UPS(
        "Injury follow-ups",
        "Injury not mentioned, hazard still active: asks injury, who was hurt, then what was done (3 rounds).",
    ),
    VAGUE_LOCATION(
        "Vague location",
        "No time mentioned (defaults to now), vague location, injury unknown: 3 rounds (hits the default cap).",
    ),
    GALLERY_WITH_PHOTO("Gallery with photo", "Complete report; photos picked from the gallery are attached."),
    STT_FAILURE("STT failure", "Speech-to-text fails once; Retry then succeeds."),
    EXTRACTION_FAILURE("Extraction failure", "The LLM step fails once; Retry then succeeds."),
}

/** Real AI backend chosen in the developer menu. */
enum class AiBackend(val label: String, val description: String) {
    QWEN_WHISPER("Qwen3-4B + Whisper", "Qwen3-4B on the Hexagon NPU (Genie) for the text steps; speech by whisper.cpp on the CPU (stopgap); no photo understanding."),
    GEMMA("Gemma 4 E2B", "One model (llama.cpp): LLM on the Hexagon NPU, audio and image encoder on the Adreno GPU. Native speech, photo (512 px) and text input."),
}

/** Speech-to-text implementation chosen in the developer menu. */
enum class SttChoice(val label: String, val description: String) {
    MOCK("Mock", "Scripted or typed transcripts; the audio is ignored."),
    WHISPER_CPU(
        "Whisper CPU (stopgap)",
        "Real recognition with whisper.cpp on the CPU, English only. Not the NPU Whisper (the board has no NPU Whisper model).",
    ),
    GEMMA_AUDIO("Gemma 4 native audio", "Real recognition by Gemma 4 E2B (NPU + GPU). Only with the Gemma backend."),
}

/** Text-to-speech engine chosen in the developer menu (persisted). */
enum class TtsEngine(val label: String, val description: String) {
    SYSTEM("System", "Android TextToSpeech (the board has no engine installed; offline English voice only)."),
    SHERPA("Sherpa (Piper + Rasa)", "sherpa-onnx on the CPU: Piper Amy for English, Rasa for Indic scripts; other languages and missing model files use the system engine."),
}

/**
 * Developer-menu settings. All mock delays are here so the processing UI can be watched or sped up.
 * Documented in `doc/SERVICES_AND_MOCKS.md`.
 */
data class DevSettings(
    val scenario: MockScenario = MockScenario.COMPLETE,
    val sttDelayMs: Long = 1800,
    val llmDelayMs: Long = 2200,
    val jsonDelayMs: Long = 700,
    val validationDelayMs: Long = 800,
    val toolDelayMs: Long = 600,
    /** Real AI (Genie/Qwen) instead of the mock extraction. Speech-to-text stays mock until Whisper is available. */
    val useRealAi: Boolean = false,
    /** Dialogue rephrase flag (spec v2 section 8.4). Only effective with Real AI (the mock has no LLM). */
    val useLlmRephrase: Boolean = true,
    /**
     * Typed transcript script, one utterance per line (first line = the narration, then one line per answer).
     * Used by the mock speech-to-text instead of its scripts while lines remain; blank = scripts. Clearly a
     * stand-in for real speech recognition (Whisper is not available yet).
     */
    val typedTranscript: String = "",
    /**
     * Speech-to-text choice; null = the default rule: Whisper CPU when Real AI is selected and the whisper model is on the device,
     * otherwise the mock. Resolved by `AppContainer.effectiveStt`.
     */
    val sttChoice: SttChoice? = null,
    /** Whisper CPU only: bias the decoder with an initial prompt of place names (conference room, server room, ...). */
    val whisperPlacePrompt: Boolean = false,
    /** Which on-device models Real AI uses (persisted). */
    val aiBackend: AiBackend = AiBackend.QWEN_WHISPER,
    /**
     * Gemma backend only: narration audio -> transcript and extraction JSON in ONE model call instead of transcribe, then
     * extract (`ai/llama/GemmaNarrationExtractor`). Off by default; not yet measured.
     */
    val gemmaOneCall: Boolean = false,
    /** Text-to-speech engine; null = the default rule: Sherpa when its model files are on the device, otherwise the system TTS (`AppContainer.effectiveTts`). */
    val ttsEngine: TtsEngine? = null,
    /**
     * Gemma only: run the audio / image encoder on the CPU instead of the Adreno GPU (ADR-062). The GPU encoder is about 3x faster (photo
     * description 2.5 s instead of 7.5 s) but on the QIDK it sometimes aborts in OpenCL (`clSetKernelArg error -48`) and sometimes misreads
     * photos (a different description per process); the CPU encoder gave identical, correct descriptions in every run. Default off (GPU).
     */
    val gemmaEncoderOnCpu: Boolean = false,
    /** Sherpa Rasa voices only: speaker id 0..1023 used for every Indic language; null = the speaker of the text's language. */
    val rasaSpeaker: Int? = null,
    /** Sherpa Rasa voices only: emotion / style id 0..31 (0 = assistant). */
    val rasaEmotion: Int = 0,
)

/**
 * Holds [DevSettings] and the per-session "fail once" bookkeeping for failure scenarios. [onChanged] is called after
 * every [update] (the app persists the AI switches there).
 */
class DevSettingsStore(initial: DevSettings = DevSettings(), private val onChanged: (DevSettings) -> Unit = {}) {
    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<DevSettings> = _settings

    val current: DevSettings get() = _settings.value

    private var sttFailed = false
    private var extractionFailed = false
    private var typedIndex = 0

    fun update(transform: (DevSettings) -> DevSettings) {
        _settings.value = transform(_settings.value)
        resetFailures()
        onChanged(_settings.value)
    }

    /** Re-arms the one-shot failures. Called whenever a new report session starts. */
    fun resetFailures() {
        sttFailed = false
        extractionFailed = false
        typedIndex = 0
    }

    /** Next line of [DevSettings.typedTranscript], or null when there is none left (or none was typed). */
    fun nextTypedUtterance(): String? {
        val lines = current.typedTranscript.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.getOrNull(typedIndex)?.also { typedIndex++ }
    }

    /** True exactly once per session while the STT_FAILURE scenario is active. */
    fun consumeSttFailure(): Boolean =
        current.scenario == MockScenario.STT_FAILURE && !sttFailed.also { sttFailed = true }

    /** True exactly once per session while the EXTRACTION_FAILURE scenario is active. */
    fun consumeExtractionFailure(): Boolean =
        current.scenario == MockScenario.EXTRACTION_FAILURE && !extractionFailed.also { extractionFailed = true }
}
