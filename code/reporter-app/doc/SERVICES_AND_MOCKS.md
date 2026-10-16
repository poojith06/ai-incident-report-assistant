# Services and mocks

Interfaces: `domain/service/Services.kt` (and `dialogue/SpeechIo.kt`, `dialogue/Rephraser.kt`, `ai/LlmEngine.kt`).
Wiring: `AppContainer.kt`. All are `suspend` where they may be slow.

## Mock AI / Real AI

The developer menu (long-press the Home title) has a **Mock AI / Real AI** switch (persisted in `SharedPreferences`
`dev_settings`) and a second switch for the LLM rephraser. `AppContainer` builds the chosen implementations:

| Service | Mock AI | Real AI | Notes |
|---|---|---|---|
| `IncidentExtractionService` | `mock/MockIncidentExtraction` | `ai/LlmIncidentExtractionService` on the selected backend's `LlmEngine`: Qwen3-4B via `ai/genie/GenieLlmEngine`, or Gemma 4 E2B via `ai/llama/LlamaGemmaEngine` (prompts in `ai/Prompts.kt` as chat-neutral `ChatPrompt`s, parsing in `ai/LlmOutputParser.kt`, mapping in `ai/ModelOutputMapper.kt`) | `AppContainer.extraction` follows the switch and the backend on every access. |
| `Rephraser` (dialogue) | none (templates) | `ai/LlmRephraser` (same engine, 1500 ms timeout, spec 8.2 prompt, validated by `PhraseValidator`) | `AppContainer.rephraser()`; null when Mock AI or the rephrase switch is off. |
| `SpeechToTextService` | `mock/MockSpeechToText` | `ai/llama/GemmaSpeechToText` (Gemma 4 native audio, NPU + GPU) or `ai/whisper/WhisperCppSpeechToText` (whisper.cpp, CPU, English; **stopgap, not the NPU Whisper**) | Dev menu "Speech-to-text" (`DevSettings.sttChoice`); default (nothing chosen) = Gemma audio with the Gemma backend, else Whisper CPU when Real AI is on and `files/whisper/ggml-small.en.bin` exists, else the mock. `AppContainer.speechToText` forwards per call (`effectiveStt()`); `hasRealStt` = the selected real STT is loaded. The typed-transcript box applies to the mock only (see below). |
| `ImageAnalysisService` | `mock/MockImageAnalysis` | `ai/llama/GemmaImageAnalysis` with the Gemma backend (photo downscaled to <= 512 px, one sentence + tags) | `AppContainer.imageAnalysis` follows the backend; the dialogue gets it through `AppContainer.photoAnalysis()` (null for Mock AI, Qwen, and with one-call narration). `DialogueManager` describes up to 3 attached photos before the first extraction and passes the `ImageHint`s in every `ExtractionRequest`. |
| `TextToSpeechService` | `platform/AndroidTextToSpeechService` or `ai/tts/SherpaTtsService` (developer-menu "TTS engine", `AppContainer.effectiveTts`, `TTS.md`) | same | Real in both modes; spoken only through `platform/TtsSpeechOut` (`SpeechOut`) by `DialogueManager`. |
| `SpeechIn` (dialogue) | `platform/MicSpeechIn` (real mic + energy VAD + the selected STT) | same | Returns null after 8 s (10 s for the narration) without speech. |
| `ValidationService`, `ToolExecutor`, `ReportRepository` | real local logic | same | |

Real AI loads the selected backend's model **once at app start, off the main thread** (`AppContainer.ensureModelsLoading()`, status in
`AppContainer.aiStatus`: `Mock` / `Loading` / `Ready` / `Failed(message)`). Home shows "Loading models...", the mic stays
tappable (the recording is processed when the model is ready, because the extraction service waits for `engine.load()`).
A load failure (`ModelLoadException`) or a failed call shows the error screen with "Try again" and **"Switch to Mock AI"**.
Every LLM call starts from a reset Genie dialog / a cleared Gemma KV cache (no history leaks); each engine runs on its own single thread. Only the selected backend is resident: selecting the other one releases it.

## Table of interfaces

| Interface | Signature | Notes |
|---|---|---|
| `SpeechToTextService` | `suspend transcribe(audio: AudioInput, hint: SttHint = SttHint()): Transcript` (throws `SpeechToTextException`) | Mock: ignores audio, waits `sttDelayMs`, returns the next typed line if the dev menu has any, else a scripted transcript chosen by scenario and `hint.expectedField` (dialogue field keys, `safety`, `readback`, or `__add_more__`). |
| `IncidentExtractionService` | `suspend extract(request: ExtractionRequest): String` raw JSON (throws `ExtractionException`, `ModelLoadException`) | See "ExtractionRequest semantics". |
| `ImageAnalysisService` | `suspend analyze(media: MediaItem): ImageHint` | Mock: 400 ms, fixed tags (never given to the dialogue). Gemma: 512 px photo -> one sentence + tags; `DialogueManager(imageAnalysis = ...)` calls it for up to 3 attached photos before the first extraction (a failing photo is skipped). |
| `ValidationService` | `validate(report, config): IncidentReport` (sync) | REAL logic. See `DATA_SCHEMA.md`. |
| `ToolExecutor` | `suspend fillIncidentForm(report)`, `fillIncidentFormWithHint(report, hint): ToolResult`, `suspend submitIncidentReport(report)` | REAL local logic (`platform/LocalToolExecutor`): sets `tool_call`; submit sets status `submitted`, hides reporter if anonymous, saves via `ReportRepository`. `toolDelayMs` paces the visible form step only. |
| `TextToSpeechService` | `suspend speak(text)` / `speak(text, language)` (throws `TtsUnavailableException`), `stop()`, `shutdown()`, `setSpeechRate` | Android `TextToSpeech`, lazy init, suspends until the utterance is done, 30 s cap; or sherpa-onnx (Piper English + Rasa Indic, `TTS.md`). |
| `ReportRepository` | `suspend save(report)`, `suspend list()` | One JSON file per report in `filesDir/reports`. |
| `SpeechIn` / `SpeechOut` / `Rephraser` / `LlmEngine` | see `DIALOGUE.md`, `ai/LlmEngine.kt` | Dialogue-side interfaces. |

The Phase 1 `ClarificationService` and `TemplateClarificationService` are gone: the dialogue's `FollowUpPlanner` chooses
every question.

Not behind an interface (real Android capture): `platform/AudioRecorder` (16 kHz mono PCM16 WAV in `cacheDir`),
`platform/DeviceContext` (reporter = device name or model; GPS always null for now).

`AppContainer.speechToText` routes the first narration of a voice report with `hint.expectedField == null`; `ImageHint(mediaId, tags, description)` now comes from Gemma (tags: up to 5 lowercase keywords).

Supporting data types: `AudioInput(file, durationMs)`, `Transcript(text, language, confidence = 1f)`, `SttHint(expectedField)`,
`ImageHint(mediaId, tags, description)`, `ExtractionRequest(mode, originalTranscript, previousReport, answerTranscript,
answeredQuestion, imageHints, media, config)`, `PlannerHint`, `ToolResult`.

## ExtractionRequest semantics
- First pass: `previousReport = null`, `answerTranscript = null`.
- Dialogue answer: `previousReport` = the report so far, `answerTranscript` = the user's words,
  `answeredQuestion.targetFields = [<dialogue field key>]` (`injury`, `injured_person`, `injury_type`, `location`,
  `description`, `time`, `immediate_actions`, `people_involved`). `DialogueManager` merges the result field by field, so the service
  may return the full report or only changed fields; it must tag injured people in `persons_involved` with ` (injured)`.
- Correction / "Add more by voice": `answeredQuestion.targetFields` is empty. Merge the new information into the report
  (the add-more pass replaces the report with the returned one, so return the full merged report).

## Mock scenarios (`mock/DevSettings.kt`, `MockScenario`)

| Scenario | First-pass transcript | Dialogue behaviour (Mock AI) | Notes |
|---|---|---|---|
| `COMPLETE` | Water leak near Conference Room 2 around 10 AM, nobody hurt, ... | no questions | Severity Low (policy). |
| `INJURY_FOLLOW_UPS` | Leak, still dripping, started ~10 AM | asks `injury`; the scripted "yes, ... hurt their ankle" escalates to CARE, then `injured_person`, `injury_type` | Hazard active; `immediate_actions` flagged `missing_flagged` (CARE cap 3). |
| `VAGUE_LOCATION` | "Someone slipped near the stairs." | asks `injury` first (up to the mode cap); time defaulted to now (`time_source = default_now`) | |
| `GALLERY_WITH_PHOTO` | same as COMPLETE | no questions | Pick a photo from the gallery (Mock AI describes it with the mock analysis); the compressed photo is attached to `input.media`. |
| `STT_FAILURE` | fails once, then COMPLETE script | error screen "I couldn't understand the audio", Try again continues without a second greeting | One-shot failure per session. |
| `EXTRACTION_FAILURE` | STT OK; LLM fails once, then COMPLETE | error screen, Try again | One-shot failure per session. |

Scripted answers (`MockSpeechToText.script`): injury "Yes, one person slipped and hurt their ankle.", injured person "It was me.",
injury type "A twisted ankle.", people "Priya from accounts.", actions "Maintenance put up a barrier and started mopping.", location
"It was the main building, second floor, by the east stairwell.", time "Around 10 AM.", safety check "Yes, I am safe.", read-back "That's
fine.", add-more "Also, the projector might be damaged, and Ravi from facilities saw it first."

## Typed transcript (mock speech only)
`DevSettings.typedTranscript`: one utterance per line (first line = the narration, then one per answer). While lines remain the
mock STT returns them instead of the scripts (safety-check and read-back replies never consume a line). Works in Mock and Real AI
mode, so the real LLM can be exercised with chosen sentences. Ignored while Whisper CPU is the speech-to-text. It is a developer tool, not a user feature.

## Whisper CPU (stopgap speech-to-text)
`SttChoice.WHISPER_CPU` selects `ai/whisper/WhisperCppSpeechToText` (whisper.cpp, English, CPU; not the NPU Whisper, ADR-040). The container loads it at app start together with Qwen
(`ensureModelsLoading`); `AppContainer.speechToText` forwards each call to it or to the mock according to `effectiveStt()`. Load or run failure: `SpeechToTextException` -> error screen with
"Try again" and "Switch to Mock speech". Details and measurements: `MODEL_INTEGRATION.md`.

## Real AI backend (Qwen3 + Whisper | Gemma 4 E2B)
`DevSettings.aiBackend` (`AiBackend.QWEN_WHISPER` default | `GEMMA`, persisted `ai_backend`) chooses what Real AI runs; the developer menu shows a "Real AI backend" radio group. With `GEMMA`: one model (llama.cpp, LLM on the Hexagon NPU, audio/image
encoder on the Adreno GPU) provides the LLM steps, speech (`SttChoice.GEMMA_AUDIO`, only valid with this backend) and photo understanding; files in `files/gemma/` (`DEVICE_SETUP.md` section 8). `DevSettings.gemmaOneCall` (persisted, off) turns on
the one-call narration (audio + photos -> transcript + report in one model call, `MODEL_INTEGRATION.md`). `AppContainer.photoAnalysis()` is Gemma's photo analysis (Gemma backend), the mock (Mock AI) or null (Qwen: photos are only attached). Gallery photos never travel with the audio, so the one-call narration is audio only. Details: `MODEL_INTEGRATION.md`, ADR-044..046.

## DevSettings (developer menu)
`DevSettings(scenario, sttDelayMs=1800, llmDelayMs=2200, jsonDelayMs=700, validationDelayMs=800, toolDelayMs=600, useRealAi=false,
useLlmRephrase=true, typedTranscript="", sttChoice=null, whisperPlacePrompt=false, readResultsAloud=true, aiBackend=QWEN_WHISPER, gemmaOneCall=false)`. Held in `DevSettingsStore` (`StateFlow`); `update {}` re-arms one-shot failures and rewinds the
typed script. `useRealAi`, `useLlmRephrase`, `sttChoice` (null = default rule), `whisperPlacePrompt`, `readResultsAloud`, `aiBackend` and `gemmaOneCall` are persisted. `AppContainer.incidentConfig()` is the default `IncidentConfig`.

## Tests that cover this
`ScenarioLoopTest` (every scenario through the real `DialogueManager` with the mock STT/extraction, plus the typed script),
`ValidationAndPoliciesTest`, `FlowReducerTest`, `DialogueScenarioTest`, and the `ai/` tests (`TESTING.md`).
