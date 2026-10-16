# TODO

## Frontend
- GPS: request location permission, fill `DeviceInfo.gps`; `IncidentPolicies` already uses it as location backup (ADR-014).
- Move hardcoded UI strings to `strings.xml` (ADR-013) and add localisation.
- Remove single photos on Home (today "Remove photos" clears all); optional short video (ADR-007, camera removed by ADR-053).
- History: tap a report for a read-only detail view; export.
- Review: edit witness rows as structured rows instead of text lines.
- Compose UI tests (screens) and instrumented permission-flow tests; screenshot tests.
- Landscape and tablet layouts (content is width-capped at 640 dp but untested).
- Emergency alert hook for `injury_outcome = Serious injury / Fatal` (the field exists for this; no alert UI yet).
- Large-font layout audit on the Review form (Success/Review verified at default scale only).

## Model integration
- **Whisper ASR**: no model on the board yet. Implement `SpeechToTextService` (English, transcribe mode, give a real `confidence`) and replace `MockSpeechToText`
  in `AppContainer.speechToText`; remove the typed-transcript stand-in when done (`INTEGRATION_GUIDE.md`).
- Replace the interim prompts in `ai/Prompts.kt` with the team's `IncidentRules.kt` (re-run the parser fixtures, scenario tests, `PromptBudgetTest`, `tools/count_tokens.py`; update `PROMPTS.md`).
- Save real Qwen outputs from device runs into `app/src/test/resources/llm_outputs/` (the current fixtures are hand-written).
- On-device tests: Level 3 exists only as the benchmark (`GenieLlmBenchmarkTest`: no expected-value checks); Level 4 (eval set, `FileSpeechIn`, `EvalRunner`) exists but has not been run on the device and has never run on real audio (no Whisper); nothing in `MicSpeechIn` (VAD thresholds) or `TtsSpeechOut` has been verified on the device by the dialogue work (`TESTING.md`).
- Measure prefill/decode times per prompt on the QIDK and add them to `PROMPTS.md`.
- A slow rephrase (over 1500 ms) cannot be cancelled in Genie and delays the next call; consider skipping the rephrase when the engine is busy.
- Photo understanding now exists with the Gemma backend (`GemmaImageAnalysis`, hints in the extraction prompt); `ModeClassifier` still has no CLIP-style fire/smoke signal from the photo tags (could map tags like fire/smoke to the URGENT cue); Qwen/Mock still have no vision.
- Replace `AndroidTextToSpeechService` with a model-based TTS if required.
- Benchmark on QIDK; adjust the Processing step labels to real stages.

## Gemma 4 E2B (llama.cpp JNI): everything below needs the board (reserved for live testing during the integration)
- Run the device plan in `TESTING.md` ("Gemma on the device"): smoke test (devices `HTP0` + `GPUOpenCL`, load times), Qwen+Whisper still loading in the same build, text / speech / photo / one-call, timeout abort.
- Verify the load chain in the app namespace (`libggml-opencl.so` -> `libOpenCL.so`; `libcdsprpc.so`; FastRPC finding `libggml-htp-v79.so` through `ADSP_LIBRARY_PATH`). If `libOpenCL.so` cannot be resolved, rebuild the ggml libs without the OpenCL backend or declare it differently.
- Verify that the forced JSON prefixes (`{"incident_type":`, `{"transcript":`, `{"description":`) work with Gemma after `<|turn>model\n`; otherwise drop them for Gemma in `Prompts`.
- Replace the hand-written `llm_outputs/gemma_*.txt` with real captures; measure per-call latency (STT, photo, extraction, update, rephrase, one-call) and add them to `PROMPTS.md` / `MODEL_INTEGRATION.md`. Measure ASR number formatting ("a2" vs "a two") and decide whether a normaliser is needed.
- Decide whether the one-call narration (`gemmaOneCall`) should become the default after measuring transcript quality and latency against transcribe + extract.
- Rephrase budget: with abortable Gemma generations the 1500 ms rephrase timeout is safe; check whether Gemma finishes in it (`LlmRephraser`).
- Token counts of the prompts with Gemma's tokenizer (needs a tokenizer.json for Gemma 4; `count_tokens.py` is Qwen-only). The Hexagon backend options (`GGML_HEXAGON_*` env: fusion, HMX) were not tuned.
- Keep the llama.cpp headers and the pulled libs in sync: the structs are passed by value, a different b-number needs a recompile against its headers.
- The NPU is shared with `com.example.poodleai`: expect latency jitter (`MODELS_USAGE.md` section 1).

## Dialogue
- Hindi and Telugu: `question_bank.hi/te.json` carry `TODO_TRANSLATE`; `mode_keywords.json` / `field_keywords.json` non-English entries need native review (`needs_native_review`).
- Real emergency alerting: URGENT only shows the banner and speaks the safety check once; `{next_step}` of the read-back is always empty (ADR-026).
- GPS: `DeviceInfo.gps` is null, so "here" falls back to the spoken text (`location_source = spoken`).
- `incident_type` and the injury fields come from rules/LLM only; the prototype extraction prompt has no `injury` key (ADR-037).

## Device findings (2026-10-10)
- Rephrase latency on the QIDK is about 1.7 s end to end (prefill ~0.5 s + decode ~1.2 s) vs the 1,500 ms budget: raise the budget, shorten the prompt/max tokens, or expose a per-call max-token limit in `genie_jni.cpp`.
- Extraction decode is about 6.9 s: shorten JSON keys, drop `description`, or move to a smaller output schema. Measure after each change.
- Whisper ASR integration blocked: no model/runtime available (DEVICE_SETUP.md section 6).
- The real `IncidentRules.kt` was not provided; `ai/Prompts.kt` and `ai/LlmOutputParser.kt` are our own implementation from the board's prototype prompts. Replace/compare when it arrives.
- Genie sampler per call (rephrase temp 0.3) not wired.

## Evaluation
- Run the eval set on the QIDK with Real AI (menu "Run eval set"), pull `results.json`, add it with `tools/eval_report.py` (`EVAL_RESULTS.md` has no run yet). The first run will show the gaps listed in ADR-039.
- Audio runs: need the Whisper `SpeechToTextService` (above), then WAV files in `eval/audio/` (`eval/README.md`); until then WER is `n/a (no ASR)`. Record at least two speakers.
- **Synthetic audio** ("Generate synthetic audio" dev-menu action): not built. Plan: add `synthesizeToFile` to the TTS wrapper (`AndroidTextToSpeechService` only has `speak`), wait for `UtteranceProgressListener.onDone`, convert the engine's WAV
  (often 22.05 or 24 kHz) to 16 kHz mono PCM16 with a small resampler, write `files/eval/audio/<name from cases.json>` for the narration and every answer with a `transcript`. More than the 60 lines allowed for this step, and untestable without a device.
- "Record eval case" screen in the developer menu (shows the transcript, saves a WAV with the right file name, `AudioRecorder` already writes 16 kHz mono WAV).
- Eval run extras: prompt tokens per call type (needs `LlmResult` token counts or the tokenizer), per-case speaker label in `results.json`.

## NPU Whisper (replaces the CPU stopgap)
- The real ASR is still Whisper on the Hexagon NPU (QNN encoder + decoder context binaries, tokenizer, mel filters, runner). Until then `ai/whisper/WhisperCppSpeechToText` (whisper.cpp, CPU, ~3 s per utterance, English only, no confidence) is a stopgap
  (ADR-040). When the NPU model exists: implement `SpeechToTextService` for it, change the selection in `AppContainer`, keep or delete the whisper.cpp stopgap (and `third_party/whisper.cpp`, 3.9 MB of source + the 488 MB model).
- Whisper stopgap follow-ups: real confidence (average token probability from `whisper_full_get_token_p`), noise and accent measurement, a shorter audio context (`audio_ctx`) to cut latency for short answers, i8mm/SVE build flags, streaming partial results.
- Severity: `injury_outcome` stays "Minor injury" (confidence 0.5) after "Yes" even for a serious injury; only the severity is escalated by the policy. Estimating Serious/Fatal from cues ("seriously bleeding", "not breathing") would be a rule addition (ADR-041).
- Question cap: a CARE report that used its 3 questions gets no safety check when it escalates later (only the emergency flag and banner); decide whether a slot should be reserved (ADR-042).
- `ModelOutputMapper.parseYesNo` treats "someone"/"a person" as yes even without a hurt word; consider requiring an affirmative or injury word.

- Hindi (ADR-047): human-reviewed Hindi (and Telugu) question banks (`question_bank.hi/te.json`) and a Hindi TTS voice (the board has eSpeak with 141 voices incl. Hindi; the Android TTS path is English-only, `TtsVoicePicker`); native review of every `needs_native_review` word list; a transliteration step so a Devanagari place name could be kept instead of re-asked; Hinglish (Latin script) is not detected as Hindi.

- ADR-048: injury plausibility is a type list plus people cues (English, Hindi needs native review); a facility report with an unmentioned injury is not asked; the Review screen should offer a one-tap injury choice for it. The read-back statement could mention the highlighted fields; today it only says "Please review and tap submit".

- Settings: a Piper (or other) TTS service must implement `TextToSpeechService.setSpeechRate` to honour the Speech rate setting; the About block shows the developer-menu AI choice (read-only); contacts are still the dummy list; strings are hardcoded (ADR-013).

## Piper TTS (superseded 2026-10-11 by sherpa-onnx, ADR-059)
The `qai_hub_models` Piper plan and the parked `piper-wip` branch are superseded: the app now has a sherpa-onnx TTS (Piper Amy for English, Rasa for Indic scripts, `doc/TTS.md`), default when the model files are on the device, with the Android TextToSpeech as fallback. Open: listen to it (never heard); Rasa runs at RTF 1.0 with int8 (fp32 is twice as fast, 83 MB larger: decide); Odia and Gujarati have no Rasa voice (system fallback or another model); number / date / abbreviation normalization for the Rasa character frontend; ship the model files with the app or a first-run installer (they are pushed by hand today); TTS on the NPU (QNN) is not attempted.

- Language: verify on the device what Gemma 4 E2B returns for Tamil / Assamese / Odia audio with the new two-line prompt (does it print `Language:` and keep the native script?); if the model ignores the format the fallback is the script name. Extraction quality without the Hindi few-shot is unmeasured. The `hi` / `te` question banks and cue lists are data and still need native review.
- Greeting: `speakGreeting` stays in the code for a later toggle; nothing exposes it in the UI.
- Injury is optional: decide whether Success / history should show "Injury: not reported" instead of an empty value.

- Gallery: orphaned compressed photos in `files/media/` (app killed before the report started) are not cleaned up; photos are described after the narration, not in parallel with it (the Gemma engine is single-threaded); verify the picker, EXIF orientation on real photos and Gemma photo-only extraction on the device.

- Knowledge graph: real IIIT-H SOPs, verified contacts, capacities, exits and room data must come from administrators (templates in `assets/kg/templates/`); replace the demo source `sop:demo-seed:v1`; build the admin dashboard and the sync job over `files/kg/outbox/` and `AdminGraphService`; optional SQLite repository behind `GraphRepository`; utility-branch and corrective-action recurrence need data the reports do not have; a dev switch for the LLM re-phrasing path (`LlmGroundedRenderer`) and a screen for past analyses; remove orphan audit files when reports are deleted ("Delete all past reports" does not touch `files/kg/`).

- Admin dashboard: on-device check of the graph (gestures, smoothness with the seed pack, label legibility), the PIN dialog and the tabs on a small phone; real security for the gate if the dashboard is used beyond a demo; share as a file (FileProvider) instead of text for large bundles; a sticky header in Graph data; the web dashboard and sync over `files/kg/outbox/`.
