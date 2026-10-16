# File map

Paths relative to `app/src/main/java/com/example/test_app/` unless noted.

## Root
| File | What |
|---|---|
| `MainActivity.kt` | `MainActivity` (single activity, sets theme + `AppRoot`) and `IncidentApp` (Application, owns `AppContainer`). |
| `AppContainer.kt` | Manual DI: the one place implementations are chosen; `incidentConfig()`. |

## `domain/`
| File | What |
|---|---|
| `model/IncidentReport.kt` | Incident JSON data classes (`IncidentReport`, `ReportInput`, `MediaItem`, `FieldEntry`, `Validation`, `Clarification`, `ClarificationQuestion`, `ClarificationTurn`, `DeviceInfo`, `Gps`, `ToolCall`, `Witness`, `InputMode`, `FieldSource`). |
| `model/IncidentField.kt` | `IncidentField` catalog (14 fields: key, label, kind, group), `FieldKind`, `FieldGroup`, `Severity`, `InjuryOutcome`, JSON value helpers. |
| `config/IncidentConfig.kt` | Mandatory fields, `askOrder`, confidence threshold, max rounds, min recording length. |
| `service/Services.kt` | All service interfaces + request/response types + exceptions. |
| `service/IncidentJsonCodec.kt` | Parse/serialize the JSON contract. |
| `service/DefaultValidationService.kt` | REAL validation (missing, low confidence, follow-ups). |
| `service/IncidentPolicies.kt` | REAL app-side fills: default time, GPS backup, severity suggestion. |

## `flow/`
| File | What |
|---|---|
| `FlowState.kt` | `FlowState`, `FlowEvent`, `FlowError`, `Session`, `FlowReducer` (pure transitions). |
| `IncidentFlowViewModel.kt` | Owns state; runs effects (STT, LLM, validation, TTS, tools, recording); exposes flows for the UI. `ProcessingPhase`. |

## `mock/`
| File | What |
|---|---|
| `DevSettings.kt` | `MockScenario`, `DevSettings` (delays, AI switches, typed transcript), `DevSettingsStore` (one-shot failures, typed script). |
| `MockSpeechToText.kt` | Mock STT scripts; `MockImageAnalysis`; `ADD_MORE_HINT`. |
| `MockIncidentExtraction.kt` | Mock LLM: canned first pass per scenario, answer merge. |

## `platform/`
| File | What |
|---|---|
| `AudioRecorder.kt` | `AudioRecord` -> WAV, live level flow. |
| `AndroidTextToSpeechService.kt` | Android TTS wrapper (suspends until done). |
| `JsonFileReportRepository.kt` | JSON files in `filesDir/reports`. |
| `LocalToolExecutor.kt` | `fill_incident_form` / `submit_incident_report` (real local logic). |
| `DeviceContext.kt` | Reporter / time / GPS (GPS null for now). |

## `ui/`
| File | What |
|---|---|
| `AppRoot.kt` | State -> screen mapping, permission launchers, back handling, dev sheet host. |
| `theme/Theme.kt` | ALL design tokens + `IncidentAssistantTheme` + `AppTheme` accessors. |
| `components/Common.kt` | `ScreenFrame`, `PrimaryButton`, `TonalButton`, `SecondaryButton`, `LinkButton`, `SurfaceCard`, `NoticeBanner`, `StepRow`, `SourceChip`, `CenteredMessage`, `contentPadding`. |
| `components/MicButton.kt` | `MicButton`, `SecondaryCircleButton`, `ProgressOrb`. |
| `components/Waveform.kt` | `Waveform`, `formatElapsed`. |
| `components/Thumbnail.kt` | `Thumbnail`. |
| `home/HomeScreen.kt` | Home frame: `HomeMode` IDLE / RECORDING / PROCESSING for the voice path. |
| `home/HistoryScreen.kt` | Past reports list. |
| `home/DevMenuSheet.kt` | Hidden developer menu. |
| `capture/CameraCaptureScreen.kt` | `CameraCaptureScreen` (CameraX, camera + mic path). |
| `processing/ProcessingScreen.kt` | Step list + transcript. |
| `clarification/ClarificationScreen.kt` | Follow-up question + answer mic. |
| `review/ReviewScreen.kt` | Review form. |
| `review/FieldEditors.kt` | `FieldEditor` per `FieldKind`, date/time pickers. |
| `success/SuccessScreen.kt` | Success. |
| `error/ErrorScreen.kt` | Error with retry paths. |

## Resources and manifest (`app/src/main/`)
| File | What |
|---|---|
| `AndroidManifest.xml` | RECORD_AUDIO, CAMERA, TTS `<queries>`; no INTERNET. |
| `res/values/themes.xml` | Dark window theme (in-app colors live in `Theme.kt`). |
| `res/values/strings.xml` | App name only. |

## Tests (`app/src/test/java/com/example/test_app/`)
| File | What |
|---|---|
| `flow/FlowReducerTest.kt` | Transition tests. |
| `domain/ValidationAndPoliciesTest.kt` | Validation rules, severity policy, JSON round trip. |
| `mock/ScenarioLoopTest.kt` | Each mock scenario through the real clarification loop. |

## Build
`build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml` (versions), `settings.gradle.kts`.

## Added: emergency contacts
`domain/config/EmergencyContacts.kt` (dummy contact list, `isSevere`), `ui/components/EmergencyContactsCard.kt` (call card, used by Review and Success).

## Added: follow-up dialogue (Phase 2) - see `DIALOGUE.md`
Main code under `dialogue/` (pure Kotlin, no Android imports):
| File | What |
|---|---|
| `dialogue/DialogueMode.kt` | `DialogueMode` (PLAIN/CARE/URGENT, caps), `DialogueFields` (dialogue keys, priority, mapping to report keys, `(injured)` marker). |
| `dialogue/DialogueConfig.kt` | `DialogueConfig` (rephrase flag, timeouts, ASR confidence floor). |
| `dialogue/DialogueAssets.kt` | `DialogueAssets` loader/validator, `ModeKeywords`, `BlockedPhrases`, `FieldKeywords`. |
| `dialogue/QuestionBank.kt` | `QuestionBank`, `DialogueAssetException`. |
| `dialogue/ModeClassifier.kt` | `ModeClassifier`, `ClipLabel` (rules, negation guard, model signals). |
| `dialogue/AnswerCues.kt` | `AnswerCues` (unsure / here / past cue / no-change), `TextMatch` (whole-word matching). |
| `dialogue/FollowUpPlanner.kt` | `FollowUpPlanner`, `PlannerState`. |
| `dialogue/DialogueForm.kt` | `DialogueForm`: report -> planner state, thin description, `reconcileInjury`. |
| `dialogue/Rephraser.kt` | `Rephraser`, `RephraseRequest`, `RephrasePrompt`, `LlmMetricsProvider`, `LlmCallMetrics`. |
| `dialogue/PhraseValidator.kt` | `PhraseValidator`, `ValidationResult`. |
| `dialogue/DialogueLogger.kt` | `DialogueLogger`, `TurnLog` (JSONL). |
| `dialogue/SpeechIo.kt` | `SpeechIn`, `SpeechOut`, `AsrResult`. |
| `dialogue/DialogueManager.kt` | `DialogueManager`, `DialogueState`, `DialogueUiState`, `SpokenLine`, `DialogueOutcome`. |
| `platform/DialogueAssetsLoader.kt` | Android `AssetManager` loader for the dialogue assets. |

Assets (`app/src/main/assets/dialogue/`): `question_bank.en.json`, `question_bank.hi.json`, `question_bank.te.json` (TODO_TRANSLATE),
`mode_keywords.json`, `blocked_phrases.json`, `field_keywords.json`.

Changed Phase 1 files: `domain/model/IncidentReport.kt` (`FieldEntry.status/raw_answer`, `FieldStatus`, `DialogueInfo`, `location_source`, `time_source`),
`domain/model/IncidentField.kt` (`INJURY`, `INJURY_TYPE`, now 14 fields), `domain/service/Services.kt` (`PlannerHint`, `ToolResult`, `ToolExecutor.fillIncidentFormWithHint`),
`mock/MockIncidentExtraction.kt` (dialogue field keys), `ui/components/Common.kt` (`EmergencyBanner`), `ui/review/ReviewScreen.kt` (status highlights, emergency banner),
`AppContainer.kt` (`dialogueAssets`), `MainActivity.kt` (`IncidentApp` validates assets in debuggable builds).

Docs: `DIALOGUE.md`, `TESTING.md`, `specs/FOLLOWUP_SPEC_v2.md`, `specs/APP_INTEGRATION_GUIDE.md` (read-only copies).

Tests (`app/src/test/java/com/example/test_app/dialogue/`): `TestAssets.kt`, `ModeClassifierTest.kt`, `FollowUpPlannerTest.kt`, `PhraseValidatorTest.kt`, `DialogueScenarioTest.kt`.

## Added: wiring the dialogue + real AI (Phase 2, step 2)
| File | What |
|---|---|
| `flow/FlowState.kt` | Now: `Idle`, `RecordingVoiceAndCamera`, `Dialogue`, add-more states, `FillingForm`, `Reviewing`, `Submitting`, `Submitted`, `Error`; `DialogueFinished`; `FlowError.MODEL_FAILURE`. |
| `flow/IncidentFlowViewModel.kt` | Runs `DialogueManager` (`runDialogue`), `DialogueView`, mic/skip handling, add-more loop, model-status passthrough. |
| `platform/MicSpeechIn.kt` | `MicSpeechIn` (real `SpeechIn`: mic + energy VAD + STT), `MicPhase`. |
| `platform/TtsSpeechOut.kt` | `TtsSpeechOut` (`SpeechOut` over the Android TTS, `speaking`, `skip()`). |
| `ai/AiStatus.kt` | `AiStatus` (Mock / Loading / Ready / Failed). |
| `ai/Prompts.kt` | All LLM prompts as chat-neutral `ChatPrompt`s (interim prototypes, rephrase, Gemma transcription / photo / one-call). |
| `ai/ChatPrompt.kt` | `ChatPrompt`, `ChatMessage`, `MediaPart`, `ChatFormat` (`CHATML` for Qwen, `GEMMA4` for Gemma 4; renderers). |
| `ai/LlmOutputParser.kt` | Tolerant parser of raw model text (think blocks, prefix, truncation). |
| `ai/ModelOutputMapper.kt`, `ai/SpokenTime.kt` | Model JSON -> report fields, safety rules, yes/no answers, spoken time -> ISO. |
| `ai/LlmEngine.kt` | `LlmEngine` (`generate(ChatPrompt, maxTokens)`), `LlmResult`, `StopReason`. |
| `ai/RealAi.kt` | `LlmIncidentExtractionService` (any `LlmEngine`), `PrecomputedFirstPass`, `LlmRephraser`. |
| `ui/dialogue/DialogueScreen.kt` | Follow-up question / read-back screen. |
| `ui/clarification/ClarificationScreen.kt` | Now only "Add more by voice". |
| `ui/home/HomeScreen.kt`, `ui/home/DevMenuSheet.kt`, `ui/error/ErrorScreen.kt`, `ui/AppRoot.kt` | Home SPEAKING mode + model status, AI switches + typed transcript, "Switch to Mock AI", screen mapping. |
| `mock/DevSettings.kt`, `mock/MockSpeechToText.kt` | `useRealAi`, `useLlmRephrase`, `typedTranscript`; dialogue field keys in the STT scripts. |
| `AppContainer.kt` | Mock/Real selection, `aiStatus`, `ensureModelsLoading()`, `rephraser()`, `dialogueLogDir`. |
| `domain/service/Services.kt` | `ModelLoadException`, `Transcript.confidence`, `ClarificationService` removed. |
Removed: `mock/TemplateClarificationService.kt`.

Tests: `test/.../ai/` (`LlmOutputParserTest`, `ModelOutputMapperTest`, `LlmIncidentExtractionServiceTest`, `LlmRephraserTest`, `PromptBudgetTest`, `AiTestSupport`), fixtures `app/src/test/resources/llm_outputs/`;
`flow/FlowReducerTest`, `mock/ScenarioLoopTest` rewritten. Tools: `tools/count_tokens.py`, `tools/README.md`. Docs: `PROMPTS.md`.

## Added: evaluation harness (Phase 2, step 8; Level 4, see `TESTING.md` and `eval/README.md`)
Pure Kotlin metric maths and runner in `eval/` (no Android imports); only the last three rows touch Android.
| File | What |
|---|---|
| `eval/TextNorm.kt` | Normal form of a text (case, `a.m.`, apostrophes, punctuation, number words -> digits), light stemming, content / location tokens. |
| `eval/Wer.kt` | `Wer` (word-level Levenshtein, mean and corpus WER), `WerResult`. |
| `eval/FieldMatch.kt` | `FieldMatch` (exact / normalised / relaxed per field kind), `TimeOfDay`, `Mapping` (placement classes and rate), `MatchLevel`, `MatchKind`, `Placement`. |
| `eval/Stats.kt` | `Stats` (median, nearest-rank p90, mean, min, max), `LatencySummary`. |
| `eval/EvalMetrics.kt` | `EvalMetrics.summarize` (ASR, extraction, mapping, dialogue, rephraser, latency from `CaseResult`s), `DialogueInvariants` (spec scenario 14 as a check), `SpokenObs`. |
| `eval/EvalResults.kt` | Serializable result types: `CaseResult`, `EvalRun`, `EvalMeta`, `EvalSummary` and the per-metric summaries (the `results.json` schema `eval_results/1`). |
| `eval/EvalCases.kt` | `EvalCase`, `EvalAnswer`, `EvalExpected`, `EvalCases.parse` (validating parser of `cases.json`). |
| `eval/Wav.kt` | `WavInfo` (RIFF header: rate, channels, bits, duration; 16 kHz mono PCM16 check). |
| `eval/EvalRunner.kt` | `EvalRunner` (cases -> real `DialogueManager` with typed or WAV input), `EvalInput`, `EvalProgress`, `ReportText`, `TranscriptSpeechIn`, `RecordingSpeechIn`, `TimingExtraction`. |
| `eval/EvalReport.kt` | `EvalReport`: `results.json` / `results.md` writer and reader, headline lines, Markdown tables. |
| `eval/EvalController.kt` | `EvalController`, `EvalUiState`, `EvalFinished`: one run at a time, progress for the developer menu. |
| `platform/FileSpeechIn.kt` | `FileSpeechIn`: `SpeechIn` that reads WAV files and feeds the injected `SpeechToTextService` (null when the file is absent). Pure JVM. |
| `AppContainer.kt` | `runEval`, `hasRealStt`, `evalDir`, `evalRunsDir`. |
| `flow/IncidentFlowViewModel.kt`, `ui/AppRoot.kt`, `ui/home/DevMenuSheet.kt` | `eval` controller and `hasRealStt`; the dev menu "Evaluation" section (Run eval set, Run eval set from audio, progress, summary). |

Data: `app/src/main/assets/eval/cases.json` (26 cases, canonical), `eval/README.md`, `eval/audio/.gitkeep` (no audio yet), `tools/eval_report.py`, `EVAL_RESULTS.md`.
Tests (`app/src/test/java/com/example/test_app/eval/`): `WerTest`, `FieldMatchTest` (+ `TextNormTest`, `MappingTest`), `EvalMetricsTest` (+ `StatsTest`, `DialogueInvariantsTest`), `EvalCasesTest`, `FileSpeechInTest` (+ `WavInfoTest`), `EvalRunnerTest`, `EvalControllerTest`.

## Added: Whisper CPU stopgap (whisper.cpp; not the NPU Whisper, ADR-040)
| File | What |
|---|---|
| `app/src/main/cpp/third_party/whisper.cpp/` | Vendored whisper.cpp v1.7.6 (trimmed: ggml CPU backend + whisper core, `LICENSE` = MIT). |
| `app/src/main/cpp/CMakeLists.txt` | Also builds whisper/ggml (static, -O3, NEON) and `libwhisper_jni.so`. |
| `app/src/main/cpp/whisper_jni.cpp` | JNI: create / transcribe (greedy, English forced, optional initial prompt) / free. |
| `ai/whisper/WhisperNative.kt` | JNI declarations. |
| `ai/whisper/WhisperCppSpeechToText.kt` | `SpeechToTextService` over whisper.cpp: single thread, load once, logs `asr_ms` / RTF (tag `WhisperCpp`), model lookup, place-name prompt text. |
| `ai/whisper/WavPcm.kt` | Pure JVM WAV to 16 kHz mono float (channel average, linear resample). |
| `mock/DevSettings.kt` | `SttChoice` (Mock / Whisper CPU), `DevSettings.sttChoice` (null = default rule), `whisperPlacePrompt`. |
| `AppContainer.kt` | `speechToText` forwarding object, `effectiveStt()`, `whisperStt`, whisper load in `ensureModelsLoading()`, `hasRealStt`, `evalDir` internal fallback. |
| `ui/home/DevMenuSheet.kt`, `ui/error/ErrorScreen.kt`, `ui/AppRoot.kt`, `flow/IncidentFlowViewModel.kt` | "Speech-to-text" section, "Switch to Mock speech" on STT errors. |
Tests: `src/test/.../ai/whisper/WavPcmTest.kt`; `src/androidTest/.../ai/whisper/WhisperCppBenchmarkTest.kt`; `src/androidTest/.../eval/EvalAudioRunTest.kt` (audio eval run, Real AI + Whisper CPU).

## Added: severity policy and the strict question cap (ADR-041 / ADR-042)
| File | What |
|---|---|
| `domain/service/SeverityPolicy.kt` | `SeverityPolicy.decide` (max of hint and floors), `SeverityCues`, `SeverityInput`. |
| `domain/service/TextMatch.kt` | Whole-word matching + negation guard helper (moved from `dialogue/AnswerCues.kt`). |
| `domain/service/IncidentPolicies.kt` | `apply(report, now, cues, extraTexts)`: severity via `SeverityPolicy`. |
| `assets/dialogue/severity_cues.json` | Escalation cues and high-severity incident types. `mode_keywords.json` gained the distress URGENT cues + `negatable`. |
| `dialogue/DialogueConfig.kt`, `FollowUpPlanner.kt`, `DialogueManager.kt`, `DialogueForm.kt`, `DialogueAssets.kt`, `ModeClassifier.kt` | `maxQuestionsTotal`, `capReached(.., otherQuestions, maxTotal)`, safety check counted and skipped at the cap, emergency raised on URGENT, policy severity for planner and classifier, `DialogueAssets.severityCues`, `ModeKeywords.urgentNegatable`. |
| `test/.../domain/SeverityPolicyTest.kt`, `test/.../dialogue/SeverityScenarioTest.kt`, `QuestionCapTest.kt`, `DialogueTestSupport.kt` | New tests and shared fakes. |

## Added: spoken read-outs and offline TTS
| File | What |
|---|---|
| `dialogue/FormReadout.kt` | Pure `FormReadout.review(report)` / `submitted(report)` / `speakableId`. |
| `flow/ReadoutPolicy.kt` | Which text is spoken after which event (once per Review entry). |
| `platform/TtsConfig.kt` | `TtsConfig.SPEECH_RATE`, `TtsVoicePicker` (offline English voice choice). |
| `platform/AndroidTextToSpeechService.kt` | Offline voice selection, rate, one-time log (tag `TtsService`). |
| `flow/IncidentFlowViewModel.kt`, `ui/review/ReviewScreen.kt`, `ui/AppRoot.kt` | `readOut`, `toggleReadout`, `readoutSpeaking`, speaker button, TTS stops on edit/leave/record. |
| `mock/DevSettings.kt`, `ui/home/DevMenuSheet.kt`, `AppContainer.kt` | `readResultsAloud` setting (persisted) and its switch. |
| `test/.../dialogue/FormReadoutTest.kt`, `TtsVoicePickerTest.kt`, `test/.../flow/ReadoutPolicyTest.kt` | 10 + 5 + 5 tests. |

## Added: Gemma 4 E2B via llama.cpp (JNI; ADR-044..046)
| File | What |
|---|---|
| `app/src/main/cpp/third_party/llama/include/` | Headers of llama.cpp b11552 (llama.h, mtmd.h, mtmd-helper.h, ggml*.h, gguf.h) pulled from the board. |
| `app/src/main/cpp/llama_jni.cpp` | JNI over libllama/libmtmd: load (HTP0 + GPUOpenCL), generate (text/audio/image, greedy, abortable), stats, devices. |
| `app/src/main/cpp/CMakeLists.txt` | Also: `llama_jni` against the prebuilt libs (imported targets); whisper's ggml forced static (soname fix). |
| `app/src/main/jniLibs/arm64-v8a/libllama.so` ... (git-ignored) | `tools/pull_llama_libs.sh`: libllama, libmtmd, libggml{,-base,-cpu,-rpc,-opencl,-hexagon}, libggml-htp-v79. |
| `ai/llama/LlamaNative.kt` | JNI declarations. |
| `ai/llama/LlamaGemmaEngine.kt` | `LlmEngine` for Gemma (+ `LlamaGemmaConfig`, `PackedMedia`): single thread, abort on cancel. |
| `ai/llama/GemmaSpeechToText.kt` | `SpeechToTextService` with Gemma's native audio. |
| `ai/llama/GemmaImageAnalysis.kt`, `ai/llama/ImageScaling.kt` | `ImageAnalysisService` (decode, EXIF, <= 512 px, describe) and its pure maths. |
| `ai/llama/GemmaNarration.kt` | `GemmaNarrationExtractor`: optional one-call audio (+ photos) -> transcript + report JSON. |
| `ai/llama/GemmaText.kt` | Pure output cleanup (transcript, photo description + tags). |
| `mock/DevSettings.kt` | `AiBackend`, `SttChoice.GEMMA_AUDIO`, `DevSettings.aiBackend`, `gemmaOneCall`. |
| `AppContainer.kt` | `QwenStack` / `GemmaStack`, `effectiveStt()`, `transcribeNarration`, `photoAnalysis()`, backend-aware `ensureModelsLoading()`. |
| `dialogue/DialogueManager.kt` | `imageAnalysis` parameter: photos described before the first extraction, hints in every request. |
| `ui/home/DevMenuSheet.kt` | "Real AI backend" radio group, Gemma options, speech choice incl. Gemma audio. |
| `tools/pull_llama_libs.sh`, `tools/check_apk_libs.py`, `tools/gguf_meta.py`, `tools/gemma_render_reference.py` | Pull libs; host check of sonames / DT_NEEDED of the APK; read GGUF metadata (chat template); render golden Gemma prompts with jinja2. |
Tests: `src/test/.../ai/ChatFormatTest.kt`, `ai/llama/GemmaTextTest.kt`, `ai/LlmServiceOptionsTest.kt`, `dialogue/PhotoHintsTest.kt`; resources `chat_golden/gemma4.json`, `llm_outputs/gemma_*.txt` (hand-written).

## Added: Hindi support (ADR-047)
| File | What |
|---|---|
| `dialogue/LanguageDetect.kt` | Script-based `detect` (en/hi/te), `hasNonLatin`, `speakable`. |
| `dialogue/AnswerCues.kt` | Hindi `yesNo`; Hindi unsure/skip/here/no-change through `field_keywords.json`. |
| `domain/service/TextMatch.kt` | Combining marks as word characters; negation guard also looks after the cue (`after_words`). |
| `ai/Prompts.kt`, `ai/ModelOutputMapper.kt`, `ai/RealAi.kt` | English-output rules and Hindi few-shots, `translateAnswer`, script safety net, `input.language`, one-call translation fallback. |
| `dialogue/DialogueManager.kt`, `DialogueLogger.kt`, `FormReadout.kt`, `PhraseValidator.kt` | Hindi yes/no handed to the extractor, `lang` in the log, no script in spoken lines or rephrases. |
| `assets/dialogue/field_keywords.json`, `mode_keywords.json`, `severity_cues.json` | Hindi entries (`needs_native_review`); the files were re-serialised (one array item per line). |
| `test/.../dialogue/HindiTest.kt` | 25 tests. |

## Added: cap of 2, relevance, statement read-back (ADR-048)
| File | What |
|---|---|
| `dialogue/FollowUpPlanner.kt` | New priority (location, injury, description, time, actions, people, injured person, injury type), relevance conditions, `explain`. |
| `dialogue/DialogueForm.kt`, `DialogueAssets.kt` | `injuryPlausible`, `isVagueLocation`, `RelevanceCues` (`assets/dialogue/relevance_cues.json`). |
| `dialogue/DialogueManager.kt`, `DialogueConfig.kt`, `DialogueLogger.kt` | `maxQuestionsTotal = 2`, read-back statement (no listen), `DialogueOutcome.Review(report)`, `relevance` in the log. |
| `assets/dialogue/question_bank.{en,hi,te}.json`, `dialogue/QuestionBank.kt` | Statement read-back lines; parse rejects a `?` in them. |
| `flow/ReadoutPolicy.kt`, `flow/IncidentFlowViewModel.kt` | No automatic form read-out right after a dialogue read-back. |
| `dialogue/SpeechIo.kt`, `mock/MockSpeechToText.kt`, `eval/*` | `ANSWER_HINT_READBACK` removed; eval invariants check the cap and the statement read-back. |
| `assets/eval/cases.json` | Expectations updated (see ADR-048). |
| `test/.../dialogue/RelevancePolicyTest.kt`, `QuestionCapTest.kt`, `FollowUpPlannerTest.kt` | New / rewritten tests. |

## Added: Settings screen and user settings
| File | What |
|---|---|
| `domain/config/UserSettings.kt` | `UserSettings`, `SpeechRate`, `UserSettingsStore`. |
| `ui/settings/SettingsScreen.kt` | The Settings screen, `AboutInfo`. |
| `ui/home/HomeScreen.kt`, `ui/AppRoot.kt` | Gear icon (idle Home only) and the Settings route. |
| `ui/components/EmergencyContactsCard.kt`, `ui/components/Common.kt` | `title` / `severe` parameters; `SecondaryButton(enabled)`. |
| `ui/dialogue/DialogueScreen.kt` | "Tap the mic, then answer" state (hands-free off). |
| `dialogue/DialogueConfig.kt`, `DialogueManager.kt` | `followUpsEnabled`, `handsFreeAnswers`, `WAITING_FOR_ANSWER_TAP`. |
| `domain/service/Services.kt`, `platform/JsonFileReportRepository.kt` | `ReportRepository.deleteAll`, `TextToSpeechService.setSpeechRate`. |
| `platform/AndroidTextToSpeechService.kt`, `platform/TtsConfig.kt` | Speech rate applied before each utterance; rate bounds. |
| `AppContainer.kt`, `mock/DevSettings.kt`, `ui/home/DevMenuSheet.kt`, `flow/IncidentFlowViewModel.kt` | `userSettings` (persisted), `readResultsAloud` moved out of DevSettings, VM wiring (`updateUserSettings`, `deleteAllReports`, rate push). |
| `test/.../domain/UserSettingsTest.kt`, `test/.../dialogue/FollowUpsOffTest.kt` | 7 + 17 tests. |

## Changed: language-agnostic STT, no greeting, injury optional (ADR-050/051/052)
| File | What |
|---|---|
| `ai/Prompts.kt` | `TRANSCRIBE_INSTRUCTION` (two-line `Language:` / `Transcript:` format, language-agnostic), one-call prompt with `language` first (`MULTIMODAL_PREFIX = {"language":`), generic extraction/update/translate wording, Hindi few-shots removed. |
| `ai/llama/GemmaText.kt` | `parseTranscriptReply`, `TranscriptReply`. |
| `ai/llama/GemmaSpeechToText.kt`, `ai/llama/GemmaNarration.kt` | Use the parser; return the model's language in `Transcript.language`. |
| `ai/whisper/WhisperCppSpeechToText.kt`, `domain/service/Services.kt` | `Transcript.language` default is blank (unknown). |
| `dialogue/LanguageDetect.kt` | Generic: `resolve`, `normalizeName`, `detect` (dominant Unicode script), `hasNonLatin` (any non-Latin/Common/Inherited letter). |
| `dialogue/DialogueManager.kt`, `DialogueConfig.kt` | `speakGreeting` (default false); language = STT language or script; injury answer branch removed. |
| `dialogue/DialogueMode.kt`, `FollowUpPlanner.kt`, `DialogueForm.kt`, `DialogueAssets.kt`, `AnswerCues.kt`, `FormReadout.kt` | `injury` out of the planner/readout; `injuryPlausible`, yes/no cues removed; `RelevanceCues` = vague locations only. |
| `domain/config/IncidentConfig.kt`, `domain/model/IncidentField.kt` | Injury not mandatory, not asked; `injury_outcome` in the Situational group. |
| `ai/ModelOutputMapper.kt`, `ai/RealAi.kt`, `mock/MockIncidentExtraction.kt`, `mock/MockSpeechToText.kt` | Injury branches and `parseYesNo` removed; translate fallback for any non-Latin answer; mock injury scenario states the injury. |
| `assets/dialogue/*.json`, `assets/eval/cases.json` | Injury rows removed from banks/cues; E06, E17, E18, E21, E26 changed. |
| tests | `GemmaTextTest`, `HindiTest`, `FollowUpPlannerTest`, `RelevancePolicyTest`, `DialogueScenarioTest`, `SeverityScenarioTest`, `FollowUpsOffTest`, `EvalCasesTest`, `EvalRunnerTest`, `ScenarioLoopTest`, `ValidationAndPoliciesTest`, `FormReadoutTest`, `ChatFormatTest`, `PhraseValidatorTest`, `ModelOutputMapperTest`, `LlmIncidentExtractionServiceTest`; fixtures `chat_golden/gemma4.json`, `llm_outputs/gemma_multimodal_*.txt`. |

## Changed: gallery replaces the camera (ADR-053)
| File | What |
|---|---|
| `media/ImageCompressor.kt` (new) | Android: decode with `inSampleSize`, EXIF rotation + mirror, <= 512 px, JPEG 80 -> `files/media/<uuid>.jpg`. |
| `media/PhotoPicks.kt` (new) | Pure: at most 3 photos per report, dropped-photo notice. |
| `ai/llama/ImageScaling.kt` | `JPEG_QUALITY`, `exifMirrored`; orientation table complete (mirror first, then rotate). |
| `ui/capture/CameraCaptureScreen.kt` | DELETED (CameraX removed from `app/build.gradle.kts` and `libs.versions.toml`; CAMERA permission and camera feature removed from the manifest). |
| `ui/home/HomeScreen.kt`, `ui/components/MicButton.kt`, `ui/components/Thumbnail.kt` | Gallery button, thumbnails above the mic, "Use photos only" (primary, ADR-062), "Remove photos"; `SecondaryCircleButton(description)`. |
| `ui/AppRoot.kt` | Photo picker launchers (`PickMultipleVisualMedia` / `GetMultipleContents`), camera permission code removed. |
| `ui/settings/SettingsScreen.kt`, `ui/error/ErrorScreen.kt`, `ui/home/DevMenuSheet.kt` | About "Photos" line; camera error removed; one-call text. |
| `flow/FlowState.kt`, `flow/IncidentFlowViewModel.kt`, `domain/model/IncidentReport.kt` | `StartVoiceWithPhotos`, `StartPhotoOnly`, `PermissionDenied` object, camera states removed; `onPhotosPicked`, `clearPhotos`, `startPhotoOnly`; `InputMode.VOICE_AND_PHOTO` / `PHOTO_ONLY` / `fromWire`. |
| `AppContainer.kt`, `ai/RealAi.kt`, `ai/ModelOutputMapper.kt`, `ai/Prompts.kt`, `mock/*` | `imageCompressor`, `photoAnalysis()` (Gemma or mock), photo-only confidence cap, PHOTO rule, `GALLERY_WITH_PHOTO`. |
| tests | `FlowReducerTest`, `PhotoHintsTest`, `ImageCompressionMathTest` (new), `GemmaTextTest`. |

## Added: spoken language setting (ADR-054)
| File | What |
|---|---|
| `assets/dialogue/languages.json` (new) | The language names of the picker (data). |
| `domain/config/SpokenLanguages.kt` (new) | Pure parser of the file + display label. |
| `domain/config/UserSettings.kt` | `spokenLanguage` (default "auto"), `forcedLanguage`, `SPOKEN_AUTO`. |
| `ai/Prompts.kt` | `transcriptionForced`, `TRANSCRIBE_EXACT`, `multimodalPrefix`, `multimodalExtraction(forcedLanguage)`. |
| `ai/llama/GemmaSpeechToText.kt`, `GemmaNarration.kt`, `GemmaText.kt` | Forced-language provider, `prompt` / `parseReply`, `forcedTranscriptReply`. |
| `ui/components/SpokenLanguageSheet.kt` (new) | `LanguageChip`, `SpokenLanguageSheet`. |
| `ui/home/HomeScreen.kt`, `ui/settings/SettingsScreen.kt`, `ui/AppRoot.kt`, `flow/IncidentFlowViewModel.kt`, `AppContainer.kt` | Chip, Settings row, sheet wiring, persistence, `spokenLanguages`. |
| tests | `ChatFormatTest`, `GemmaTextTest`, `UserSettingsTest`; golden entry `transcribe_forced_tamil` in `resources/chat_golden/gemma4.json`. |

## Added: offline knowledge graph (ADR-055..057)
| File | What |
|---|---|
| `knowledgegraph/model/GraphModel.kt`, `IncidentAndOutput.kt` | Graph rows, conditions, pack, normalization rules; input `ValidatedIncident`; output, audit log. |
| `knowledgegraph/KnowledgeGraphInput.kt` | The one adapter report -> graph incident / history record. |
| `knowledgegraph/KnowledgeGraphService.kt`, `AnalysisExport.kt` | Orchestration (never throws), dashboard export document. |
| `knowledgegraph/repository/GraphIndex.kt`, `GraphRepository.kt`, `AuditStore.kt`, `AnalysisOutbox.kt` | In-memory index, file/in-memory pack repository, audit log store, export outbox. |
| `knowledgegraph/rules/*` | `ConditionEvaluator`, `SafetyPolicy`, `MedicalOutputGuard`, `PhoneNumberGuard`, `Instants`. |
| `knowledgegraph/engine/*` | `KnowledgeGraphEngine`, `EngineConfig`. |
| `knowledgegraph/history/HistoryPatternDetector.kt` | Recurrence queries. |
| `knowledgegraph/llmgrounding/*` | Renderer interface, template and LLM renderers, output validator. |
| `knowledgegraph/admin/*` | `AdminGraphService`, `GraphValidator`, `GraphPackCodec`. |
| `assets/kg/seed_pack.json`, `README.md`, `templates/*` | Seed pack (generated), pack README, admin import templates. |
| `tools/kg_seed/build_seed_pack.py` | Seed pack and test fixture generator. |
| `ui/analysis/AnalysisCard.kt` | The Success-screen card. |
| `ai/Prompts.kt` | `groundedRephrase`. |
| `domain/config/UserSettings.kt`, `AppContainer.kt`, `flow/IncidentFlowViewModel.kt`, `ui/success/SuccessScreen.kt`, `ui/settings/SettingsScreen.kt`, `ui/AppRoot.kt` | Switch, wiring, state, card, toggle. |
| `app/src/test/.../knowledgegraph/*`, `app/src/test/resources/kg/*` | Tests and the 12 scenario fixtures. |
| `doc/KNOWLEDGE_GRAPH.md`, `doc/ADMIN_DASHBOARD_CONTRACT.md`, `doc/samples/kg/*` | Docs, JSON contract, sample exports and audit logs. |

## Added: in-app admin dashboard (ADR-058)
| File | What |
|---|---|
| `admin/AdminState.kt`, `AdminViewModel.kt`, `AdminBackend.kt`, `AdminModels.kt`, `AdminPrefs.kt` | State and reducer, the one view model, file and store access, snapshot models, prefs interface. |
| `admin/OverviewStats.kt`, `ReportFilter.kt`, `LogModels.kt`, `ImportDiff.kt`, `DemoData.kt`, `AdminPin.kt`, `AnalysisSettings.kt`, `EditorForms.kt`, `PreviewSpec.kt` | Pure logic of the tabs. |
| `knowledgegraph/admin/graphview/ForceLayout.kt`, `GraphSubgraph.kt`, `IncidentPath.kt` | Layout, subgraph and filters, incident-path derivation. |
| `ui/admin/*.kt`, `ui/admin/graph/*.kt` | Dashboard shell, tabs, PIN dialog, charts, graph canvas, controls, node sheet. |
| `ui/theme/Theme.kt`, `ui/home/HomeScreen.kt`, `ui/AppRoot.kt`, `MainActivity.kt`, `AppContainer.kt`, `flow/IncidentFlowViewModel.kt` | Palette and code style, Admin icon, routing and PIN, view model factory, wiring, delete toggle. |
| `domain/service/Services.kt`, `platform/JsonFileReportRepository.kt`, `knowledgegraph/KnowledgeGraphService.kt`, `knowledgegraph/repository/AuditStore.kt` | `ReportRepository.delete`, config provider, audit `all` / `delete` / `clear`. |
| `app/src/test/.../admin/*` | 125 tests. |
| `doc/ADMIN_DASHBOARD.md` | User guide and architecture. |

## Added: Sherpa TTS (Piper English + Rasa Indic; ADR-059, `TTS.md`)
| File | What |
|---|---|
| `app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt` | Verbatim sherpa-onnx v1.13.8 Kotlin API (package fixed by the JNI; Apache-2.0 header kept). |
| `ai/tts/SherpaVoices.kt` | Pure: `TtsModel`, `VoiceEngine`, `VoicePlan`, `RasaVoiceChoice`, `SherpaVoices` (script / language routing, speaker table, Rasa chunking and punctuation stripping, speed mapping). |
| `ai/tts/SherpaTtsService.kt` | `SherpaModelFiles`, `SherpaSynthesizer` (lazy load, streaming, timings), `SherpaTtsService` (`TextToSpeechService`, `AudioTrack` stream, fallback per text). |
| `domain/service/Services.kt`, `platform/TtsSpeechOut.kt` | `TextToSpeechService.speak(text, language)`; the dialogue language is passed on. |
| `mock/DevSettings.kt`, `AppContainer.kt`, `ui/home/DevMenuSheet.kt`, `ui/AppRoot.kt`, `flow/IncidentFlowViewModel.kt` | `TtsEngine`, `ttsEngine` / `rasaSpeaker` / `rasaEmotion` (persisted), `effectiveTts()`, forwarding `textToSpeech`, `ttsDir`, developer-menu section "Text-to-speech engine". |
| `tools/pull_sherpa_libs.sh` | Downloads the v1.13.8 prebuilt, copies `libsherpa-onnx-jni.so` + `libonnxruntime.so` to `jniLibs/arm64-v8a`. |
Tests: `src/test/.../ai/tts/SherpaVoicesTest.kt` (23); `src/androidTest/.../ai/tts/SherpaTtsTest.kt` (sentence set, speaker / emotion grid, borrowed speakers, thread scaling, fp32 diagnostic, service stop / fallback) and `SherpaTtsRoundTripTest.kt` (Gemma STT round trip of the WAVs).

## Added: spoken lines follow the heard language (ADR-060, `DIALOGUE.md` "Languages", `TTS.md`)
| File | What |
|---|---|
| `dialogue/LanguageNames.kt` (new) | Pure: language names / codes / scripts tables, `code(language)`, `forText(text, hint)` (moved out of `ai/tts/SherpaVoices`). |
| `dialogue/LanguagePack.kt` (new) | `ReadoutTemplates` (the `readout` object, English defaults), `Glossary` (type, severity, field labels, numbers 0..59, `spellDigits`), `LanguagePack(code, bank, readout, glossary)`. |
| `dialogue/DialogueAssets.kt` | `readout`, `packs`, `primaryPack`, `pack(language)`; loads `language_packs.json` and per pack the bank + glossary. |
| `dialogue/FormReadout.kt` | Rewritten over the templates: `review(report, pack?)`, `submitted(report, pack?)`, `speakableId(id, glossary, sep)`, `clockPhrase(iso, templates, glossary)`; English output unchanged. |
| `dialogue/DialogueManager.kt`, `DialogueConfig.kt` | Pack of the heard language (forced language before the narration), `speak(text, pack.code)`, localized read-back, no rephrase outside English. |
| `flow/ReadoutPolicy.kt`, `flow/IncidentFlowViewModel.kt` | Read-outs use the pack of `report.input.language` (else the forced setting) and pass its code to `TextToSpeechService.speak`. |
| `ai/tts/SherpaVoices.kt`, `ai/tts/SherpaTtsService.kt` | `scriptRuns`, `segments` (one voice per script run, pauses by punctuation), `resample`; the service plays the segments through one `AudioTrack` (30 s buffer). |
| `assets/dialogue/language_packs.json`, `question_bank.{hi,te}.json` (translated, `readout` object), `question_bank.en.json` (`readout` key set), `glossary.{hi,te}.json` | Data of the packs. |
Tests: `test/.../dialogue/LanguagePackTest.kt` (30), `SherpaVoicesTest` (segments, resample), `HindiTest` / `PhraseValidatorTest` (rewritten for the new rule); device: `androidTest/.../ai/tts/LocalizedSpeechTest.kt`.

## Changed: photo-only reports (ADR-062, `DIALOGUE.md` "Photo-only reports", `PROMPTS.md` G2 / G2b)
| File | What |
|---|---|
| `ai/Prompts.kt` | `imageDescription` rewritten (setting + what is wrong), new `photoOnlyExtraction(hints)` (rules, four generic examples), `PHOTO_ONLY_PREFIX`. |
| `ai/RealAi.kt`, `ai/ModelOutputMapper.kt` | Blank narration + photo hints -> the photo-only prompt; generic "locations" of a vision model ("indoor", "wall", ...) are dropped (`isGenericPhotoLocation`). |
| `ai/llama/GemmaImageAnalysis.kt` | 160 reply tokens. |
| `dialogue/DialogueManager.kt` | `followUps` = setting AND somebody spoke: a `PHOTO_ONLY` session has no opening, question, safety check, defer line and never listens. |
| `ui/home/HomeScreen.kt` | Primary "Use photos only" under the thumbnails; caption "Or tap and describe what happened." |
| `eval/EvalCases.kt`, `eval/EvalRunner.kt`, `AppContainer.runEval` | Photo-only cases: `images`, `expected.severity_min`; the runner takes `imageAnalysis` + `imageRoot` (`files/eval/images/`); cases without them are skipped. |
| `assets/eval/cases.json` | `P01`..`P06` (the six photos). |
| `mock/DevSettings.kt`, `AppContainer.kt`, `ui/home/DevMenuSheet.kt` | `gemmaEncoderOnCpu` (persisted): Gemma's image / audio encoder on the CPU instead of the GPU; the engine is rebuilt when it changes. |
Tests: `ai/PhotoPromptsTest` (with the golden files `src/test/resources/prompts/*.gemma4.txt`), `dialogue/PhotoHintsTest` (rewritten photo-only tests), `eval/EvalPhotoCasesTest`, `eval/EvalCasesTest` (+3), `admin/AdminViewModelTest` (flaky Main dispatcher fixed). Device: `ai/llama/PhotoReportLabTest` (describe -> extract, one-call, audio encoder timing), `PhotoOnlySessionTest` (real dialogue + knowledge graph), `eval/EvalPhotoRunTest`.
