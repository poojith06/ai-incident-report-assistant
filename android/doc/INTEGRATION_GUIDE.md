# Integration guide: replacing mocks with real on-device models

Constraint: **all models run on-device (the LLMs on the Hexagon NPU; whisper.cpp and sherpa-onnx on the CPU). No cloud APIs,
no INTERNET permission.** Ship model files in the APK assets or push to app storage; load them lazily.

## What is real today

| Service | Status | How to switch |
|---|---|---|
| `IncidentExtractionService` | **Real** (`LlmIncidentExtractionService` in `ai/RealAi.kt`, on Qwen3-4B via Genie or Gemma 4 E2B via llama.cpp) and the mock stays | Developer menu: **Mock AI / Real AI** switch and "Real AI backend" (persisted). `AppContainer.extraction` picks per call. |
| `Rephraser` | **Real** (`LlmRephraser` in `ai/RealAi.kt`); mock = none (templates) | Same switch + "Rephrase questions with the LLM". |
| `SpeechToTextService` | **Real**: `ai/whisper/WhisperCppSpeechToText.kt` (whisper.cpp `small.en`, CPU, English) and `ai/llama/GemmaSpeechToText.kt` (Gemma 4 E2B audio, multilingual); the mock stays | Developer menu "Speech-to-text" (`DevSettings.sttChoice`); default rule in `SERVICES_AND_MOCKS.md`. The typed-transcript box applies to the mock only. |
| `SpeechIn` | **Real** (`platform/MicSpeechIn`): mic + energy VAD, 8 s silence = null, then the `SpeechToTextService` | nothing to switch |
| `TextToSpeechService` | **Real**: `ai/tts/SherpaTtsService.kt` (sherpa-onnx: Piper English + Rasa Indic) or the Android `TextToSpeech`, behind `platform/TtsSpeechOut` | Developer menu "Text-to-speech engine" (Sherpa by default when the voice files are on the device) |
| `ImageAnalysisService` | **Real** with the Gemma backend (`ai/llama/GemmaImageAnalysis.kt`); mock otherwise | Follows the Real AI backend (Qwen has no vision model: photos are only attached) |

General recipe (same for every service):
1. Create a class implementing the interface in a new file under `platform/` or `ai/`.
2. Register it in `AppContainer.kt` by changing the single line for that service.
3. Delete the unused mock (clean cutover) and update `SERVICES_AND_MOCKS.md`.
4. Keep services `suspend` and main-safe: do model work on `Dispatchers.Default` / a dedicated dispatcher.
5. Run `gradlew.bat testDebugUnitTest` (tests use the mocks and must still pass), then smoke on device.

## SpeechToTextService (ASR)
- Implementations: `ai/whisper/WhisperCppSpeechToText.kt` and `ai/llama/GemmaSpeechToText.kt`. `AppContainer.speechToText` forwards each call to the selected one
  (`effectiveStt()`; it is used by `MicSpeechIn`). A future NPU Whisper would be one more implementation registered there.
- Input: `AudioInput.file` = 16 kHz mono PCM16 WAV (header 44 bytes), `durationMs`. Ignore `SttHint` (mock-only).
- Output: `Transcript(text, language, confidence)`. Give a real `confidence` if the model has one (both current implementations leave it at 1.0): below 0.5 the dialogue stores the
  answer as `unverified` and does not extract it. On failure throw `SpeechToTextException("friendly message")`.
- The caller deletes the wav after the call. Whisper is English only (`small.en`); Gemma names the spoken language or uses the one chosen in Settings (`DIALOGUE.md`).

## IncidentExtractionService (on-device LLM)
- Real: `LlmIncidentExtractionService` (`ai/RealAi.kt`) over `ai/LlmEngine.kt` (`ai/genie/GenieLlmEngine.kt`: Qwen3-4B, Genie; `ai/llama/LlamaGemmaEngine.kt`: Gemma 4 E2B; reset per call). Prompts: `ai/Prompts.kt`
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

## ImageAnalysisService (vision)
- Implemented with the Gemma backend: `ai/llama/GemmaImageAnalysis.kt` (photo downscaled to <= 512 px, a description and up to 5 tags). Input: `MediaItem.localUri` (`file://`). Output: `ImageHint`.
  Tags like equipment names help fill `equipment_asset_involved` and `incident_type`. To use the hints the LLM prompt
  must include them (already passed in `ExtractionRequest.imageHints`).

## Follow-up questions
Not a service any more: `dialogue/FollowUpPlanner` (deterministic) chooses the field, `dialogue/QuestionBank` holds the one template per
field and mode, and the optional `Rephraser` only rewords that template (see `DIALOGUE.md`). The LLM never picks the question.

## ValidationService, ToolExecutor, ReportRepository
Already real. Extend `LocalToolExecutor` if submission must also sync to a local system. Replace
`JsonFileReportRepository` with Room if querying is needed.

## TextToSpeechService (model-based TTS)
- Implemented: `ai/tts/SherpaTtsService.kt` (`TTS.md`), with the Android TTS as fallback. `speak` must suspend until playback ends. Throw `TtsUnavailableException`
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
- Docs updated (this file and `SERVICES_AND_MOCKS.md`).
