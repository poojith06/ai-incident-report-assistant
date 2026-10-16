# Agent log

## 2026-10-10 - Initial frontend + docs

Done:
- Built the whole app on top of the Android Studio Compose template: domain model, state machine, mock services,
  real validation/policies, platform services (AudioRecord, TTS, JSON storage, tool executor), all screens,
  hidden developer menu, theme tokens, unit tests, docs.
- Field set follows the team's "recommended field set" (12 fields, 4 groups; see `DATA_SCHEMA.md`, ADR-008..010).
  Mandatory = description, injury/outcome, location, date-time. Schema version 1.1.
- Verified: `gradlew.bat assembleDebug` OK; `testDebugUnitTest` 29 tests pass; installed on a physical phone (moto g62)
  and ran Home -> real mic recording -> Processing (transcript + steps) -> Review -> Submit -> Success for the
  Complete scenario, and Injury follow-ups up to "Question 1 of 3 - Was anyone hurt?" with the "2 details left" counter.
- Deleted template files: `ui/theme/Color.kt`, `Type.kt`, `res/values/colors.xml`, example tests.

Not verified on device (open issues):
- Camera + mic path: CameraX preview and simultaneous mic recording (timer, waveform) were seen working on the phone;
  shutter, photo saving, thumbnails and attaching photos to the report were not exercised.
- TTS audio was not heard/verified; failure path (banner) not exercised.
- Remaining clarification rounds, Skip, Add more by voice, error screens, permission-denied screens, STT/extraction
  failure scenarios were covered by unit tests (`FlowReducerTest`, `ScenarioLoopTest`) but not by hand on the device.
- Large system font and reduce-motion behaviour not checked.
- GPS not implemented (ADR-014).

Suggested next steps: smoke the unverified paths above on device; then start model integration per
`INTEGRATION_GUIDE.md` (ASR first).

Gotchas for the next agent:
- Never dispatch an event and then keep working in the same effect; `dispatch` cancels the current effect job.
- Edit `ValidationService` rules and `DATA_SCHEMA.md` together.
- Cold Gradle builds take ~10 min on this machine; incremental builds ~20-30 s.

## 2026-10-10 (later) - In-place mic frame + hands-free answers

Done (committed baseline `bdfef48` first, then these changes):
- Voice path now stays on ONE Home frame: mic -> stop button (same position) + timer + waveform -> spinner + step list +
  transcript. `HomeMode`, `ProgressOrb`, `ProcessingSteps`, `AppRoot.isFirstPass` (ADR-016). Removed `VoiceCaptureScreen`;
  `CaptureScreens.kt` renamed `CameraCaptureScreen.kt`.
- Clarification answers are hands-free: recorder starts after the spoken question, stops on a 2 s pause after speech
  (also tap), "Skip and fill manually" is a `TonalButton` (ADR-017).
- Bugs found on the Pixel_7 emulator and fixed: (1) a TTS init/speak timeout threw `TimeoutCancellationException`
  (a CancellationException) which cancelled the question effect silently and froze the flow on emulators without a
  TTS engine; now converted to `TtsUnavailableException` (banner shown, flow continues). (2) Skipping while an answer was
  being recorded left the mic open; `enter` now releases the recorder. (3) `startRecorder` is idempotent; silence
  auto-stop never fires before 1.5 s.
- Verified on the Pixel_7 emulator (`emulator-5554`, no TTS engine): idle -> recording in place -> processing -> Question 1
  of 3 with the recorder already running and the Skip button -> round 2 started by itself -> Skip -> Review.

Not verified: silence auto-stop with real speech (emulator mic is silent); phone showed "too short" / "Already recording"
errors once after an earlier build, not reproduced on the emulator after the fixes above (cause not isolated).
Dev settings (scenario) reset on every process restart; consider persisting them.

## 2026-10-11 - Emergency contacts for severe incidents
Added `EmergencyContacts` (5 dummy Indian numbers) and `EmergencyContactsCard`; shown on Review (after the form is filled)
and Success when severity is High/Critical; tap opens the dialer. No unit test (UI + static list).

## 2026-10-11 - Phase 2 step 1-3: schema 1.1 additions + follow-up dialogue (agent `DialogueCore`)
Done:
- Specs copied (read-only) to `doc/specs/FOLLOWUP_SPEC_v2.md` and `doc/specs/APP_INTEGRATION_GUIDE.md`.
- Schema 1.1 additions (the repo already said 1.1 with 12 fields): `injury` + `injury_type` fields (14 now), per-field `status` / `raw_answer`
  (`FieldEntry.status` nullable, `effectiveStatus`), report-level `location_source`, `time_source`, `dialogue {mode, asked_fields, emergency}`.
  `DATA_SCHEMA.md` updated (mapping table, one complete + one incomplete example).
- Review: highlight for `unknown_reporter_unsure` / `no_response` / `unverified` / `missing_flagged`, `EmergencyBanner` for `dialogue.emergency` (tokens only).
- `dialogue/` package + `assets/dialogue/*.json` (`DIALOGUE.md`); `ToolExecutor.fillIncidentFormWithHint` (`ToolResult`/`PlannerHint`); mock extraction understands dialogue keys;
  `AppContainer.dialogueAssets` + debug-build validation in `IncidentApp`.
- Tests: 103 new JVM tests (22 classifier, 19 planner, 20 validator/assets, 42 scenario incl. the 14 spec scenarios, the 6 fake-rephraser runs, tool hint, logging, mock compatibility); the 29 Phase 1 tests are unchanged and pass. `TESTING.md`.
- ADR-018 .. ADR-030 in `DECISIONS.md`; proposed merged state diagram in `STATE_MACHINE.md`.

Not done on purpose: `IncidentFlowViewModel` is NOT rewired to `DialogueManager` (hook-up steps in `DIALOGUE.md`); no real `SpeechIn`/`SpeechOut`/`Rephraser`
implementations (interfaces + fakes only); `doc/UI_FLOW.md` not merged with the dialogue states; Hindi/Telugu banks are `TODO_TRANSLATE` placeholders.

Gotchas: (1) A KDoc containing the characters slash-star (e.g. a path glob) opens a nested comment in Kotlin and breaks the build. (2) `./gradlew.bat` in the repo failed
during this work with `[CXX1101] NDK ... did not have a source.properties file` (another agent's NDK install was half-finished); the unit tests and `assembleDebug` were run in a
temporary copy of the repo without the `externalNativeBuild` lines. (3) The dialogue's `targetFields` are dialogue keys (`injury`, `location`, ...), not report keys.

## 2026-10-11 - Phase 2 step 2: dialogue wired into the app, Real AI switch, prompts (agent `DialogueCore`, helper `AiCore`)
Done:
- `IncidentFlowViewModel`/`FlowState` rewired to `DialogueManager` for BOTH Mock and Real AI (ADR-031..034): `FlowState.Dialogue`, `MicSpeechIn` (mic + energy VAD, 8 s silence = null, mock STT),
  `TtsSpeechOut`, `DialogueScreen`, Home SPEAKING mode + 10 s hint, camera path = transcribe then dialogue without greeting, Retry never re-greets. Phase 1 clarification loop removed;
  "Add more by voice" keeps its small loop. `STATE_MACHINE.md` (merged Mermaid) and `UI_FLOW.md` rewritten.
- Real AI (ADR-035..037): `ai/Prompts.kt` (interim board prompts verbatim + rephrase), `LlmOutputParser`, `ModelOutputMapper` + `SpokenTime`, `GenieIncidentExtractionService`, `LlmRephraser`;
  dev menu Mock/Real switch + rephrase switch + typed transcript (persisted switches); `AppContainer.ensureModelsLoading()` at app start, Home "Loading models...", `MODEL_FAILURE` + "Switch to Mock AI".
- Token budget: `tools/count_tokens.py` + `PromptBudgetTest`; measured with Qwen's tokenizer (worst case): extraction 462, update 458, rephrase 232 tokens (`PROMPTS.md`).
- Tests: 220 JVM tests, all green (ai 82, dialogue 103, flow 19, domain 11, mock 5), `assembleDebug` green with the NDK build.
- Docs: SERVICES_AND_MOCKS, INTEGRATION_GUIDE, DIALOGUE, TESTING, PROMPTS, DECISIONS, FILE_MAP, TODO, README, ARCHITECTURE.

Not verified (no device run by this work): `MicSpeechIn` VAD thresholds on real hardware, `TtsSpeechOut` skip/cancel, the Genie engine and real Qwen output with the interim prompts, the Home/Dialogue screens visually,
model-load failure UI, silence timeouts end to end. Whisper is not available (STT mock in both modes, typed-transcript stand-in).

Gotchas: (1) a KDoc containing slash-star opens a nested comment; (2) `dispatch` inside an effect cancels it, so every effect ends with its dispatch; (3) the add-more pass replaces the report with the extraction result, so
the extraction service must return the full merged report (mock and Genie service do); (4) `DialogueManager.run` is single-use: a retry builds a new manager.

## 2026-10-11 - Phase 2 step 8: English eval set, metrics, FileSpeechIn, runner, report tool (agent `EvalHarness`)
Done:
- `app/src/main/assets/eval/cases.json`: 26 English cases (canonical; schema and metric definitions in `eval/README.md`): every spec v2 section-12 scenario, every guide checklist row (with the ADR-019 resolutions), short / long, noise / fast notes,
  the place names Whisper may mishear. `eval/audio/.gitkeep` only: no audio exists (neither synthetic nor recorded).
- Pure-Kotlin metrics in `eval/` (WER, field match with relaxed location / time / overlap rules, mapping placement, dialogue incl. scenario-14 invariants and ask-once violations, rephraser, median / nearest-rank p90 latency) with 94 JVM tests
  using hand-computed values.
- `platform/FileSpeechIn` (WAV -> injected `SpeechToTextService`, null for an absent file), `eval/EvalRunner` (real `DialogueManager`, transcript or audio input, `results.json` + `results.md`), dev-menu "Evaluation" section
  (Run eval set / Run eval set from audio), `AppContainer.runEval`. `tools/eval_report.py` (tested on a hand-made results.json) and `doc/EVAL_RESULTS.md` ("No device run yet": no numbers exist).
- Docs: `TESTING.md` (Level 3 as it exists, Level 4, manual checklist incl. airplane mode), `FILE_MAP.md`, `DECISIONS.md` ADR-038 / ADR-039, `TODO.md`, `tools/README.md`, `README.md`.

Not done on purpose: no device run (no numbers); audio runs have never executed (Whisper is not on the board; the audio path is covered only by a fake STT in JVM tests); synthetic audio generation and the "Record eval case" screen (`TODO.md`);
prompt tokens per call type are not part of the run (`tools/count_tokens.py`, `PROMPTS.md`).

Gotchas: (1) the dialogue's `readback` and `safety` are listens too, so every case has a `readback` utterance (no audio, no WER); (2) the scenario-11 case needs one simulated mic tap, otherwise `DialogueManager.run` waits forever;
(3) mock extraction ignores transcripts: Mock AI eval numbers prove the plumbing only.

## 2026-10-11 - Whisper CPU stopgap: whisper.cpp behind `SpeechToTextService` (agent `WhisperCpp`)
Done:
- Vendored whisper.cpp v1.7.6 (ggml CPU only, MIT licence kept) in `app/src/main/cpp/third_party/whisper.cpp`; `CMakeLists.txt` builds `libwhisper_jni.so` (-O3 forced also in debug, NEON/dotprod/fp16, no OpenMP/network); `whisper_jni.cpp`;
  `ai/whisper/{WhisperNative,WhisperCppSpeechToText,WavPcm}.kt`.
- Wiring: `DevSettings.sttChoice` (null = default rule) + `whisperPlacePrompt`, dev-menu "Speech-to-text" section, `AppContainer.speechToText` forwarding object, `effectiveStt()`, whisper load inside `ensureModelsLoading()`, `hasRealStt`,
  `evalDir` falls back to internal `files/eval`; Error screen "Switch to Mock speech" for STT failures.
- Tests: `WavPcmTest` (8 JVM tests), androidTest `WhisperCppBenchmarkTest` and `EvalAudioRunTest` (the dev menu's `AppContainer.runEval(AUDIO)`, Real AI + Whisper CPU). `EvalCasesTest.audioFilesAreWellNamedAndUnique` now accepts the `audio/test-N.wav` names of the human-recording cases (E27..E30 were added with that naming and the test failed before this work).
- Measured on the board: see MODEL_INTEGRATION.md (small.en, 6 threads: WER 0 % on 4 samples vs the faster-whisper references, 3.05 s median; base.en: WER 11 %, 1.0 s). Eval audio run: `EVAL_RESULTS.md`.
Not verified: other speakers / noise, the dev-menu radio buttons and the error screen visually (the app was only started and the load logged), the UI-automation path of "Run eval set from audio" (the same `runEval` ran through an androidTest instead), the live microphone path with Whisper.
Gotchas: (1) Gradle debug builds compile with -O0: the whisper targets override `CMAKE_*_FLAGS_DEBUG` inside a CMake function so genie_jni is unaffected; (2) JNI names for the package `test_app` need `_1` (`test_1app`); (3) the benchmark and EvalAudioRunTest need the model/WAVs in internal files (run-as copy).

## 2026-10-11 - Deterministic severity policy + strict question cap (agent `DialogueCore`)
Done (ADR-041, ADR-042): `SeverityPolicy` (final = max(model hint, floors): injury/hazard table, URGENT >= High, URGENT + escalation cue or injury = Critical, Fire / Gas Leak /
Electrical Hazard >= High; user edits never touched; escalate-only) with `assets/dialogue/severity_cues.json`; `IncidentPolicies.apply` requires the cues; `ModeClassifier` treats "need help",
"very urgent", "seriously bleeding", "bleeding badly" as URGENT (negation-guarded); `DialogueConfig.maxQuestionsTotal = 3` enforced in `DialogueManager` (safety check counts, hard stop + debug assert,
skipped safety check still raises the emergency flag); `emergency` raised the moment the mode becomes URGENT; `TextMatch` moved to `domain/service`.
Root cause of the device report: `IncidentPolicies` replaced the model/mapper severity with the injury table value (Medium for a minor or unknown injury), so URGENT fires ended up Medium.
Tests: SeverityPolicyTest (27), SeverityScenarioTest (7, the three device transcripts), QuestionCapTest (10), plus new cases in ModeClassifierTest, FollowUpPlannerTest, ModelOutputMapperTest; all JVM tests green, assembleDebug green.
Could not find a code path that sets `injury_outcome = Minor injury` while `injury` is unknown: the only writers (`ModelOutputMapper.applyUpdate` for a "yes" answer and `DialogueForm.reconcileInjury`) require injury == true.
A "yes" can come from `parseYesNo` matching "someone"/"a person" in a distress answer (TODO). Not verified on a device.

## 2026-10-11 - Spoken form read-out + offline TTS (agent `DialogueCore`, ADR-043)
Done: `FormReadout` (review + submitted lines), `ReadoutPolicy` (once per Review entry), speaker button on Review, TTS stops on edit / leaving Review / before any recording, `readResultsAloud` dev setting
(persisted), offline-robust `AndroidTextToSpeechService` (default engine, English, installed non-network voice via `TtsVoicePicker`, rate constant, one-time log `TtsService`). The app no longer calls `DialogueManager.close()`.
Tests: 20 new (FormReadoutTest 10, TtsVoicePickerTest 5, ReadoutPolicyTest 5); 395 JVM tests green, assembleDebug green. Not verified on a device: the QIDK has no TTS engine, so nothing was heard;
voice selection logic is only unit-tested through `TtsVoicePicker`, not against a real `TextToSpeech.voices` list.

## 2026-10-11 - Gemma 4 E2B via llama.cpp JNI (agent `GemmaJni`; ADR-044..046)
Done (host only; the board was reserved, NOTHING was run on the device):
- Pulled (read-only `adb pull`) the b11552 Snapdragon headers + libs (`tools/pull_llama_libs.sh`), `llama_jni.cpp` + CMake target `llama_jni` (imported prebuilt libs), `ai/llama/*` (engine, STT, photo analysis, one-call narration), `ChatPrompt`/`ChatFormat` (ChatML + Gemma 4), `LlmEngine.generate(ChatPrompt, maxTokens)`
  (clean cutover: Genie engine, `Prompts`, `GenieIncidentExtractionService` -> `LlmIncidentExtractionService`, tests), `DevSettings.aiBackend` / `gemmaOneCall` + dev menu, `AppContainer` backend stacks, `DialogueManager(imageAnalysis)`, manifest `libOpenCL.so`.
- Gemma template read from the GGUF (`tools/gguf_meta.py`), rendered with jinja2 (`tools/gemma_render_reference.py`) and used as the golden file of `ChatFormatTest`. Findings in `PROMPTS.md` "Gemma chat format".
- Soname clash found by Main on the device (`libggml-rpc.so not found` in `WhisperNative.<clinit>`): whisper.cpp's CMake built shared libggml*/libwhisper (the old `set(BUILD_SHARED_LIBS OFF)` was ineffective); fixed with `set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)` and hidden symbols;
  `libggml-rpc.so` added to the pulled libs (libggml/libllama list it in DT_NEEDED); v73/v75/v81 skels dropped. Evidence (`python tools/check_apk_libs.py` on the debug APK, `llvm-readelf -d`): 20 libraries, all sonames unique, all DT_NEEDED resolved:
```
libGenie.so needed=['libc.so', 'libm.so', 'libdl.so', 'liblog.so']
libQnnHtp.so needed=['libc.so', 'libm.so', 'libdl.so', 'liblog.so']
libQnnHtpV79Skel.so needed=['libc++.so.1', 'libc++abi.so.1']
libQnnHtpV79Stub.so needed=['libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libcdsprpc.so']
libQnnSystem.so needed=['libc.so', 'libm.so', 'libdl.so', 'liblog.so']
libandroidx.graphics.path.so needed=['libm.so', 'libdl.so', 'libc.so']
libgenie_jni.so needed=['libGenie.so', 'liblog.so', 'libandroid.so', 'libm.so', 'libdl.so', 'libc.so']
libggml-base.so needed=['libm.so', 'libdl.so', 'libc.so']
libggml-cpu.so needed=['libggml-base.so', 'libm.so', 'libdl.so', 'libc.so']
libggml-hexagon.so needed=['liblog.so', 'libdl.so', 'libggml-base.so', 'libm.so', 'libc.so']
libggml-htp-v79.so needed=['libc.so', 'libgcc.so']
libggml-opencl.so needed=['libggml-base.so', 'libOpenCL.so', 'libm.so', 'libdl.so', 'libc.so']
libggml-rpc.so needed=['libggml-base.so', 'libm.so', 'libdl.so', 'libc.so']
libggml.so needed=['libggml-cpu.so', 'libggml-rpc.so', 'libggml-opencl.so', 'libggml-hexagon.so', 'libggml-base.so', 'libm.so', 'libdl.so', 'libc.so']
libimage_processing_util_jni.so needed=['liblog.so', 'libandroid.so', 'libjnigraphics.so', 'libm.so', 'libdl.so', 'libc.so']
libllama.so needed=['libggml.so', 'libggml-cpu.so', 'libggml-rpc.so', 'libggml-opencl.so', 'libggml-hexagon.so', 'libggml-base.so', 'libm.so', 'libdl.so', 'libc.so']
libllama_jni.so needed=['libmtmd.so', 'libllama.so', 'libggml.so', 'libggml-base.so', 'libggml-cpu.so', 'libggml-rpc.so', 'libggml-opencl.so', 'libggml-hexagon.so', 'liblog.so', 'libandroid.so', 'libm.so', 'libdl.so', 'libc.so']
libmtmd.so needed=['libllama.so', 'libggml.so', 'libggml-cpu.so', 'libggml-rpc.so', 'libggml-opencl.so', 'libggml-hexagon.so', 'libggml-base.so', 'libm.so', 'libdl.so', 'libc.so']
libsurface_util_jni.so needed=['libandroid.so', 'libm.so', 'libdl.so', 'libc.so']
libwhisper_jni.so needed=['liblog.so', 'libandroid.so', 'libm.so', 'libdl.so', 'libc.so']
```
  (`libggml-htp-v79.so` and `libQnnHtpV79Skel.so` are DSP-side ELF files, not loaded by the Android linker; `libOpenCL.so` / `libcdsprpc.so` are vendor libraries declared in the manifest.) `libllama_jni.so` and `libwhisper_jni.so` export only `Java_*` symbols.
- Tests: `./gradlew.bat testDebugUnitTest assembleDebug` green, 436 JVM tests (new: `ChatFormatTest`, `GemmaTextTest`, `LlmServiceOptionsTest`, `PhotoHintsTest`).
Not verified (needs the device): everything native at runtime (backend/device registration, `HTP0`/`GPUOpenCL` selection, load times, FastRPC skel lookup, `libOpenCL` in the app namespace), Gemma output quality with the forced JSON prefixes, ASR number formatting, latencies in the app,
that llama.cpp's own jinja engine renders the same text as jinja2, the abort path, EXIF rotation on real photos, the dev-menu layout.
Gotchas: (1) mtmd/llama structs are passed by value: headers and libs must be the same build; (2) JNI strings are modified UTF-8: `llama_jni.cpp` converts by hand; (3) `LlmIncidentExtractionService` takes `precomputed` before `clock` so existing trailing-lambda clocks keep working;
(4) a plain `CoroutineScope(dispatcher)` would be cancelled by the first failed generation: the engine scope uses a `SupervisorJob`.

## 2026-10-11 - Hindi input, English report (agent `DialogueCore`, ADR-047)
Done: prompts require English values (+ Hindi few-shots, `translateAnswer`), script safety net in `ModelOutputMapper` (never stores Devanagari/Telugu; `unverified` + `raw_answer`), `LanguageDetect` (`input.language`, log `lang`), Hindi cue lists and `AnswerCues.yesNo`, after-negation guard, one-call translation fallback for unclassified non-English injury answers, no script in the read-back / `FormReadout` / rephrases.
Tests: `HindiTest` (25); all 461 JVM tests green, assembleDebug green. `ChatFormatTest` pins updated. Measured (Qwen tokenizer, worst case): extraction 666, update 546, rephrase 232 tokens.
Not verified on a device: real Gemma/Qwen output for Hindi (the scripted JSON mimics the reported behaviour), Hindi ASR quality, TTS behaviour with the generic phrases, the translation call latency. Side effect: the three dialogue JSON assets were re-serialised by a script (formatting only).

## 2026-10-11 - Cap of 2 questions, relevance policy, statement read-back (agent `DialogueCore`, ADR-048)
Done: `maxQuestionsTotal = 2` (absolute, safety check counts), read-back is a statement (no listen, no `?`), planner reworked (location > injury (plausible only) > description > time > actions (High/Critical) > people > injured person > type), `relevance` audit in the JSONL, `relevance_cues.json`, vague-location rule, ReadoutPolicy skips the form read-out after a dialogue read-back. Spec v2 section 12 scenarios, eval cases and mock scenario updated (list in ADR-048).
Tests: 466 JVM tests green (new: RelevancePolicyTest 5 with 20 transcripts, QuestionCapTest 9, FollowUpPlannerTest 22); assembleDebug green. Not verified on a device: the spoken flow with real Gemma output, that users accept a statement read-back, the injury relevance on real transcripts.

## 2026-10-11 - User Settings screen, follow-ups on/off (ADR-049, agent `DialogueCore`)
Done: gear on the idle Home, `SettingsScreen` (follow-up questions, hands-free answers, read results aloud, speech rate, emergency contacts, delete all past reports with confirm, About), `UserSettings` store (persisted, `readResultsAloud` moved out of DevSettings), `DialogueConfig.followUpsEnabled` / `handsFreeAnswers` (+ `WAITING_FOR_ANSWER_TAP`), `ReportRepository.deleteAll`, `TextToSpeechService.setSpeechRate` (Android TTS applies it). Tests: 24 new (UserSettingsTest 7, FollowUpsOffTest 17); 490 JVM tests green, assembleDebug green. Not verified on a device: the screens (compile-checked only), gear placement/touch target, the TTS rate on the real engine (the QIDK has none), the tap-to-answer flow with the real microphone.

## 2026-10-11 - Language-agnostic STT, no greeting, injury optional (ADR-050/051/052, agent `DialogueCore`)
Done: Gemma STT prompt names no language and returns `Language:` / `Transcript:` (pure parser `GemmaText.parseTranscriptReply`, language stored as the model reports it, one-call prompt returns `language` too); `LanguageDetect` made generic (dominant Unicode script fallback, `hasNonLatin` for any non-Latin script, translate fallback for any script); extraction/update/translate prompts say "any Indian language" and lost their Hindi few-shot (Qwen tokens: extraction 541, update 502, rephrase 232, all within budget); `DialogueConfig.speakGreeting = false` (no greeting, recording starts at the first tap); injury removed from mandatory fields, the planner, the question banks and the "still missing" read-out. Tests rewritten/added; see TESTING.md. Not verified on a device: Gemma's Tamil / Assamese / Odia audio quality and format obedience, the effect of removing the Hindi few-shot on Qwen/Gemma extraction.

## 2026-10-11 - Gallery replaces the camera, photos compressed on pick (ADR-053, agent `DialogueCore`)
Done: Home gallery button + system photo picker (`PickMultipleVisualMedia(3)`, fallback `GetMultipleContents`, no permission); `media/ImageCompressor` (inSampleSize, EXIF rotation + mirror, <= 512 px, JPEG 80, `files/media/<uuid>.jpg`, original never copied); photos attached as thumbnails above the mic, voice with photos or "Continue with photos only" (photo-only report from the photo description, AI fields capped at 0.5); Gemma / Mock describe photos, Qwen only attaches (About text); CameraX, CAMERA permission, camera screen, camera flow states/events/errors removed; mock scenario renamed `GALLERY_WITH_PHOTO`; tests and docs updated. Not verified on a device: the picker UI, EXIF handling on real photos, Gemma photo-only extraction quality.

## 2026-10-11 - Spoken language setting (ADR-054, agent `DialogueCore`)
Done: "Spoken language" in Settings and a Home chip (shared bottom sheet), list from `assets/dialogue/languages.json`, persisted in `UserSettings`; Gemma STT uses the forced-language prompt for every transcription when a language is chosen (plain-text reply, `input.language` = chosen name), the one-call prompt gets the language line and a pre-filled key; Auto prompts unchanged; Whisper/mock: row disabled with a note, chip hidden. Tests and docs updated, measured lab table appended to `TESTING.md`. Not verified on a device: the UI, and the forced prompt through the app's STT and one-call path.

## 2026-10-11 - Offline knowledge graph for post-incident analysis (ADR-055..057, agent `DialogueCore`, seed data by subagent)
Done: `knowledgegraph` package (model, repository, rules, engine, history, llmgrounding, admin, seed) as pure Kotlin; JSON packs under `files/kg/` instead of Room; engine per spec sections 12-13 with audience flag (REPORTER / ADMIN_PREVIEW); demo source `sop:demo-seed:v1` (labelled, not a real SOP); safety and medical guards with the eight required negative tests; history detector; grounded renderer (template default, LLM path wired but off) with output validator; admin service (import, export, upsert, disable, attach source, approve, verify, validate, preview, rollback); seed pack with 56 incident types, 125 actions and the 12 required scenarios (generated by `tools/kg_seed/build_seed_pack.py`); Success-screen analysis card, Settings switch, outbox + audit files; `doc/ADMIN_DASHBOARD_CONTRACT.md`; 168 new tests and sample outputs. Not verified on a device: the card layout, the asset load of the seed pack on Android, real submit-to-analysis timing.

## 2026-10-11 - In-app admin dashboard (ADR-058, agent `DialogueCore`, tab UIs by subagents)
Done: Admin icon on Home, PIN gate (demo), seven tabs (Overview with Canvas charts, Reports with detail and analysis, Logs with viewer/share/clear, interactive force-directed Graph with incident-path highlight, Graph data editor with approve/attach/verify/preview/import/export/versions, Outbox, Settings with PIN, engine settings, demo data); pure logic and reducer with 125 tests; `ForceLayout`, `GraphSubgraph`, `IncidentPath` derived from audit provenance (no schema change); engine settings persisted and read per analysis; "also delete KG files with reports" toggle; `ReportRepository.delete`. Not verified on a device: every Compose screen, the graph gestures and smoothness, the share sheet and the document picker.

## 2026-10-11 - Sherpa TTS: Piper English + Rasa Indic (ADR-059, agent `SherpaTts`)
Done: `ai/tts/` (`SherpaVoices` routing / Rasa chunking / speed, `SherpaTtsService` with AudioTrack streaming and per-text fallback to the Android TTS), verbatim sherpa-onnx v1.13.8 `Tts.kt`, `tools/pull_sherpa_libs.sh`, developer-menu "TTS engine" + Rasa speaker / emotion (persisted, default Sherpa when the files exist), `speak(text, language)` on the TTS interface, 23 JVM tests, device tests with WAV export (`TTSLAB`) and a Gemma round trip (`TTSRT`), `TTS.md`, DEVICE_SETUP section 9; models pushed to the board (`files/tts/piper-amy`, `files/tts/rasa`). Findings: Rasa has 20 trained per-language speakers and 24 kHz (the sherpa export README is wrong about gu / hi / or); Hindi works with the Marathi F speaker, Odia and Gujarati do not work at all; int8 Rasa RTF 1.0 vs fp32 0.48; the JNI callback must be a class. Round trips: English 0.93-1.00 (faster-whisper), Indic 0.66-1.00 (Gemma STT). Not verified: how it sounds.

## 2026-10-11 - Spoken lines follow the heard language (ADR-060, agent `SherpaTts`)
Cause of "TTS still English after a Hindi narration": the dialogue always loaded the English bank (the hi / te banks were English `TODO_TRANSLATE` stubs and never loaded), passed the configured language (always `en`) to the voice, and the form read-out / confirmation were hard-coded English and spoken without a language. Done: language packs (bank + `readout` templates + glossary, `language_packs.json`), real hi / te translations (needs native review), `DialogueAssets.pack`, `FormReadout` over templates, dialogue and view model on the pack of the heard language, per-script-run voices with one `AudioTrack` (`SherpaVoices.segments`), Rasa chunk threshold counts vowel signs, 30 s AudioTrack buffer (synthesis of the next sentence now overlaps playback), 40 new JVM tests, device session test `LocalizedSpeechTest` + Gemma round trip, docs. Languages without a pack stay English.

## 2026-10-11 - Questions not spoken: AudioTrack never started (ADR-061, agent `SherpaTts`)
Root cause: the 30 s AudioTrack buffer I introduced in ADR-060 has no start threshold, so a stream track waits for a full buffer: head 0, every line silent after a deadline wait. Found by driving the real UI (adb taps, Mock AI scenario "Vague location") with new trace logs, confirmed with `AudioTrackProbeTest`. Fixed with `PcmBufferPolicy` (start threshold 100 ms on API 31+, 1 s buffer + silence pad on API 30), `TtsSpeechOut` logs and surfaces every failure, `PcmPlayer` warns on head 0. JVM `PcmBufferPolicyTest`, device probe tests. Not verified: audibility.

## 2026-10-11 - Photo-only reports without questions, photo prompts tuned on the board (ADR-062, agent `SherpaTts`)
Done: strict photo-only sessions (no greeting / question / safety check / defer / mic), primary "Use photos only" button under the thumbnails, new description prompt and photo-only extraction prompt (iterated on the board, goldens + `PROMPTS.md` verbatim), generic-location filter, photo-only eval cases P01-P06 (`images`, `severity_min`, runner support), device lab / session / eval tests, developer switch for the Gemma encoder device, fixed the flaky `AdminViewModelTest` Main dispatcher leak (it failed a random later test in about half of the full runs). Findings: GPU image encoder unreliable (different descriptions per process, OpenCL aborts), CPU encoder stable and 3x slower; one-call image path not better; knowledge graph does not abstain on a submitted image-only report. Not verified: a typed note path (not built), photos taken by a person (only six internet-style images).
