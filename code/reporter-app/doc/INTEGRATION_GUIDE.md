# Integration guide: replacing mocks with real on-device models

Constraint: **all models run on-device, accelerated on Snapdragon (QAIRT / QNN / Qualcomm AI Hub). No cloud APIs,
no INTERNET permission.** Ship model files in the APK assets or push to app storage; load them lazily.

## What is real today

| Service | Status | How to switch |
|---|---|---|
| `IncidentExtractionService` | **Real implementation exists** (`ai/LlmIncidentExtractionService`, Qwen3-4B via Genie) and the mock stays | Developer menu: **Mock AI / Real AI** switch (persisted). `AppContainer.extraction` picks per call. |
| `Rephraser` | **Real** (`ai/LlmRephraser`); mock = none (templates) | Same switch + "Rephrase questions with the LLM". |
| `SpeechToTextService` | **Mock only.** Whisper is not on the device yet; do NOT fake a wrapper | Implement `WhisperSpeechToText` and change the one line in `AppContainer` (below). Until then the typed-transcript box in the dev menu is the stand-in. |
| `SpeechIn` | **Real** (`platform/MicSpeechIn`): mic + energy VAD, 8 s silence = null, then the `SpeechToTextService` | nothing to switch |
| `TextToSpeechService` | Real (Android `TextToSpeech`) behind `platform/TtsSpeechOut` | |
| `ImageAnalysisService` | Mock (out of scope) | |

General recipe (same for every service):
1. Create a class implementing the interface in a new file under `platform/` (or `ml/`).
2. Register it in `AppContainer.kt` by changing the single line for that service.
3. Delete the unused mock (clean cutover) and update `FILE_MAP.md`, `SERVICES_AND_MOCKS.md`, `AGENT_LOG.md`.
4. Keep services `suspend` and main-safe: do model work on `Dispatchers.Default` / a dedicated dispatcher.
5. Run `gradlew.bat testDebugUnitTest` (tests use the mocks and must still pass), then smoke on device.

## SpeechToTextService (ASR, e.g. Whisper on QNN)
- File: `platform/WhisperSpeechToText.kt`. Register: `val speechToText: SpeechToTextService = WhisperSpeechToText(appContext)`
  (`AppContainer.speechToText`; it is used by `MicSpeechIn`).
- Input: `AudioInput.file` = 16 kHz mono PCM16 WAV (header 44 bytes), `durationMs`. Ignore `SttHint` (mock-only).
- Output: `Transcript(text, language, confidence)`. Give a real `confidence` if the model has one: below 0.5 the dialogue stores the
  answer as `unverified` and does not extract it. On failure throw `SpeechToTextException("friendly message")`.
- The caller deletes the wav after the call. English only in this phase (language forced to `en`).

## IncidentExtractionService (on-device LLM)
- Real: `ai/LlmIncidentExtractionService` over `ai/LlmEngine` (`ai/genie/GenieLlmEngine`: Qwen3-4B, Genie, reset per call). Prompts: `ai/Prompts.kt`
  (see `PROMPTS.md`); output parsing: `ai/LlmOutputParser.kt`; model JSON -> report fields and safety rules: `ai/ModelOutputMapper.kt`.
- To swap the model or prompts: change `ai/Prompts.kt` + the mapper only; keep the contract below.
- Input: `ExtractionRequest` (see `SERVICES_AND_MOCKS.md`, "ExtractionRequest semantics"): first pass, dialogue answer (`targetFields` = one
  dialogue field key) or correction (empty `targetFields`).
- Output: a raw JSON string matching `DATA_SCHEMA.md` (Markdown fences are tolerated by `IncidentJsonCodec`): `schema_version`, `report_id`,
  `status`, `created_at`, `input` (echo mode, transcript, media), `fields` (use empty entries, confidence 0.0, when unknown; never invent),
  `clarification`. The app overwrites `validation`, fills `device`, defaults the time, suggests severity.
- Throw `ModelLoadException` when the model cannot be loaded (error screen offers "Switch to Mock AI"), `ExtractionException("friendly message")`
  when the first pass cannot be parsed or the call fails. A follow-up answer that cannot be mapped returns the report unchanged (the dialogue marks the field `unverified`).
- Thinking must be off for every call; the parser strips `<think>...</think>` and `<|im_end|>` anyway.

## ImageAnalysisService (optional vision model)
- File: `platform/VisionImageAnalysis.kt`. Input: `MediaItem.localUri` (`file://`). Output: `ImageHint`.
  Tags like equipment names help fill `equipment_asset_involved` and `incident_type`. To use the hints the LLM prompt
  must include them (already passed in `ExtractionRequest.imageHints`).

## Follow-up questions
Not a service any more: `dialogue/FollowUpPlanner` (deterministic) chooses the field, `dialogue/QuestionBank` holds the one template per
field and mode, and the optional `Rephraser` only rewords that template (see `DIALOGUE.md`). The LLM never picks the question.

## ValidationService, ToolExecutor, ReportRepository
Already real. Extend `LocalToolExecutor` if submission must also sync to a local system. Replace
`JsonFileReportRepository` with Room if querying is needed.

## TextToSpeechService (model-based TTS)
- File: `platform/ModelTextToSpeech.kt`. `speak` must suspend until playback ends. Throw `TtsUnavailableException`
  when unavailable (the app shows a banner and continues). `stop()` must interrupt immediately (called when the
  user taps the mic).

## DeviceContext / GPS
`platform/DeviceContext.snapshot()` returns `DeviceInfo` with `gps = null`. To add GPS: request
`ACCESS_FINE_LOCATION` in `AppRoot` (same pattern as the mic), use `FusedLocationProvider`/`LocationManager`
last-known fix, fill `Gps`. `IncidentPolicies` already uses it as the location backup.

## Image / audio access
Photos: `filesDir/media/*.jpg` (`MediaItem.localUri` is a `file://` URI). Audio: `cacheDir/rec_*.wav`.

## Checklist before declaring a swap done
- Unit tests pass; `ScenarioLoopTest` and `DialogueScenarioTest` still exercise the mocks (keep mocks for tests/demo).
- Offline: airplane mode run completes a full report.
- Latency: update the Processing screen step labels if the pipeline changes.
- Docs updated (this file, `SERVICES_AND_MOCKS.md`, `FILE_MAP.md`, `AGENT_LOG.md`).
