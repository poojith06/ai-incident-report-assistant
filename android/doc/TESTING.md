# Testing

JVM unit tests (no device, no models, no audio) are the default; what exists on the device side is described under Level 3 and Level 4 at the end.

```bat
gradlew.bat testDebugUnitTest            :: everything below
gradlew.bat testDebugUnitTest --tests "com.example.test_app.dialogue.*"   :: dialogue only
```

Reports: `app/build/reports/tests/testDebugUnitTest/index.html`.

## Phase 1 tests (unchanged)

| File | What |
|---|---|
| `flow/FlowReducerTest.kt` | State machine transitions. |
| `domain/ValidationAndPoliciesTest.kt` | Validation rules, severity policy, JSON round trip. |
| `mock/ScenarioLoopTest.kt` | Each mock scenario through the real clarification loop. |

## Level 1: unit tests for the dialogue pieces (`app/src/test/java/com/example/test_app/dialogue/`)

| File | What it pins down |
|---|---|
| `ModeClassifierTest` | URGENT / CARE keyword rules, whole-word matching, `broke` needs a person or bone, **negation guard** ("no one was hurt", "nobody got hurt", "not injured", "no injuries", "wasn't hurt", "koi chot nahi"; a negation exactly 4 tokens before negates, 5 tokens before does not; a comma or sentence end resets the window; non-injury CARE keywords and URGENT are not negated), model mode (injury, severity Critical, CLIP fire/smoke), `max(rule, model)`, personal-incident detection. |
| `FollowUpPlannerTest` | Every rule of spec 4: the priority table, each field's condition (injury, injured_person, injury_type only in CARE, location, thin description, past-cue time, immediate actions, personal-incident people), never-asked fields, **ask-once** (asked fields skipped; a full walk never returns a field twice), mode caps 2/3/2. |
| `PhraseValidatorTest` | Hand-written good and bad rephrasings: blank, no/multiple `?`, 20 vs 21 words, every blocked-phrase family (case-insensitive, smart apostrophes, "again" only as a whole word), "why"/"kyun"/"enduku" openers, off-topic text, unknown field; the rephrase prompt; asset checks (all three banks share keys, `TODO_TRANSLATE` present in hi/te, every English question has exactly one `?` and <= 20 words, a bank with a `clarify` key or two `?` is rejected, hi/te keywords flagged `needs_native_review`). |

Assets are read from `app/src/main/assets/dialogue` on disk (`TestAssets`), so the shipped JSON is what is tested.

## Level 2: scenario tests (`DialogueScenarioTest`)

`DialogueManager` runs with a fake `SpeechIn` (scripted utterances; `null` and an exhausted script both mean silence), a recording
`SpeechOut`, and a fake extraction service that returns scripted report JSON. Coroutines run on `runTest` (virtual time, so the
8 s / 10 s / 1500 ms timings cost nothing).

- `s01` ... `s14` implement the 14 scenarios of spec v2 section 12, template-only (`useLlmRephrase = false`). Scenario 14
  (`assertInvariants`) is checked after every scenario: each field in `askedFields` at most once, no spoken line twice, greeting /
  safety check / read-back / opening / defer at most once, every question has exactly one `?` and <= 20 words and never starts with
  "why", no "notified" / "help is on the way" / retry phrasing, modes never decrease, report `dialogue.asked_fields` matches.
- `rephraser_a..f`: the same 13 scenario bodies re-run with the six fake rephrasers of spec section 13 (good text, "why", two
  questions, "Let me ask again", null, timeout). (a) is spoken verbatim, (b)-(f) must fall back to the template.
- Extra: "here" with and without a GPS fix, statuses (`unknown_reporter_unsure`, `no_response`, `unverified` + `raw_answer`,
  `missing_flagged`), a later answer upgrading a flagged field, read-back correction merged once, escalation after the read-back,
  first-extraction failure propagates, tool hint (`PlannerHint`, `ToolResult.toJson` shape), JSONL log fields, validator
  `pass`/`fail:`/`timeout` in the log, report stamping + JSON round trip, and the dialogue running on the Phase 1
  `MockIncidentExtraction`.

## Level 1: on-device-model glue (`app/src/test/java/com/example/test_app/ai/`, no device, no model)

| File | What |
|---|---|
| `LlmOutputParserTest` (18) | Raw model text -> JSON object: missing/repeated prefix, repeated `{`, trailing text, code fences, `<think>` blocks (also unclosed), `<|im_end|>`, truncated JSON (incomplete last pair dropped), garbage -> null, `cleanLine` for the rephraser. Fixtures: `app/src/test/resources/llm_outputs/*.txt` (HAND-WRITTEN until real device outputs are saved there; see its README). |
| `ModelOutputMapperTest` (36) | Model JSON -> report fields: never invent, type/severity normalisation, `SpokenTime` cases with a fixed clock, injury rules (negation beats mentions), emergency severity bump, deterministic yes/no / injured person / injury type answers, update merging, bad update leaves the report unchanged. |
| `LlmIncidentExtractionServiceTest` (15) | The service with a fake `LlmEngine` (prompts recorded as ChatML text): prompts sent, load failure -> `ModelLoadException`, parse failure -> `ExtractionException`, update paths with and without an LLM call. |
| `LlmRephraserTest` (7) | Timeout, null/blank/garbled output, quotes stripped, never throws, metrics. |
| `PromptBudgetTest` (6) | Worst-case (90-word) prompts stay under the hard caps with a 1-token-per-3-characters estimate; sanitising cannot break the chat format; writes `app/build/rendered_prompts/*.txt` for `tools/count_tokens.py`. |

`python tools/count_tokens.py --tokenizer <tokenizer.json>` measures the same prompts with Qwen's tokenizer (results in `PROMPTS.md`).

### Gemma 4 (llama.cpp) glue: pure JVM tests (`ai/ChatFormatTest`, `ai/llama/GemmaTextTest`, `ai/LlmServiceOptionsTest`, `dialogue/PhotoHintsTest`)
| File | What |
|---|---|
| `ChatFormatTest` | `ChatFormat.GEMMA4` equals the model's own jinja template (golden strings from `tools/gemma_render_reference.py`, `src/test/resources/chat_golden/gemma4.json`: transcription, system + few-shot, photo + audio, rephrase, trimming, user only); transcription / photo / one-call prompts literally; marker count and order; media refused outside a user turn; `ChatFormat.CHATML` equals the prototype Qwen text (update prompt byte for byte, extraction head and tail, empty think block); photo hints add one rule + one line; user text cannot inject Gemma tokens (`<turn|>`, `<|turn>`, `<|channel>`, `<__media__>`, `<bos>` ...); `multimodalExtraction` needs media. |
| `GemmaTextTest` | Transcript cleanup (label, quotes, turn token, empty thought channel, numbers untouched), photo JSON / plain sentence / tags (lowercase, deduped, capped at 5), Gemma replies through the existing parser (HAND-WRITTEN fixtures `llm_outputs/gemma_*.txt`: plain, with the empty channel, one-call with 9 keys, photo only, truncated), `ImageScaling` (fit, `inSampleSize`, EXIF rotation, plan before/after rotation, ARGB -> RGB), `PackedMedia` order, audio clip. |
| `LlmServiceOptionsTest` | Photo hints in the first-pass prompt, per-call token budgets (400 / 200 / 48), the one-call JSON replacing the first-pass call exactly once. |
| `PhotoHintsTest` | `DialogueManager` describes at most 3 images before the first extraction, a failing photo is skipped, no service = no `PHOTO:` line. |

### Gemma on the device: bring-up steps (results: "Multilingual STT check", "Spoken language" and "Photo-only reports" below)
The steps used to bring Gemma up on the board (models copied per `DEVICE_SETUP.md` section 8):
1. `tools/pull_llama_libs.sh`, `gradlew.bat assembleDebug`, `python tools/check_apk_libs.py`, install, copy the two GGUF files (commands in `DEVICE_SETUP.md`).
2. Smoke test: start the app, `adb logcat -c`, dev menu -> Real AI on, backend Gemma -> `adb logcat -d -s LlamaNative LlamaGemmaEngine AppContainer AndroidRuntime`. Expect: `devices:` listing with `HTP0` and `GPUOpenCL`, `model loaded in .. ms`, `mmproj loaded .. vision=1 audio=1 audio_rate=16000`, Home leaves "Loading models...".
   Failures to look for: `dlopen failed` (library chain, `libOpenCL.so`), `ggml device 'HTP0' not found` (ADSP/CDSP), `clEnqueueNDRangeKernel error`, `fastrpc`/`htp` errors, `failed to open ... libggml-htp-v79.so`.
3. Qwen+Whisper still load in the same build (backend Qwen + Whisper CPU, as before), then switch back to Gemma (the other engine is released).
4. Text: type a transcript in the dev menu (mock speech) with Gemma extraction and check the 7 fields; watch `generate:` lines (prompt tokens, prefill/decode ms).
5. Speech: record the 4 eval clips (`samples/test-1..4.wav`, `DEVICE_SETUP.md` section 7) with STT = Gemma audio; compare with the guide's transcripts, record `asr_ms` (tag `GemmaStt`). Run "Run eval set from audio".
6. Photo: pick a photo from the gallery (and one with a long side > 512 px, one rotated by EXIF, one PNG with transparency; also a photo-only report with "Continue with photos only"): tag `GemmaImage` (size, description, tags), check the report's fields and that the first extraction's prompt grew by the `PHOTO:` line (`LlamaGemmaEngine generate promptChars=..`).
7. One-call narration (dev menu switch): compare transcript quality and latency with step 5 + 4 (tag `GemmaNarration`).
8. Timeout: rephrase on (1500 ms budget): confirm `LlamaGemmaEngine generate ... stop=ABORTED` appears on a timeout and the next call is not delayed.
9. Save real raw outputs into `app/src/test/resources/llm_outputs/gemma_*.txt` (replacing the hand-written ones) and the measured numbers into `PROMPTS.md` / `MODEL_INTEGRATION.md`.

Not covered by JVM tests: real Qwen output, Genie, microphone VAD and TTS (Android-bound `MicSpeechIn` / `TtsSpeechOut`; see Level 3 and Level 4 below for what exists on the device), and the Compose screens (`HomeScreen`, `DialogueScreen`, the Review highlighting, the emergency banner and the developer menu are compile-checked only).

## Level 1: evaluation metrics, `FileSpeechIn` and the runner (`app/src/test/java/com/example/test_app/eval/`, no device)

The metric maths in `app/src/main/java/com/example/test_app/eval/` is pure Kotlin (no Android imports) and is tested with hand-computed expectations
(94 tests). Definitions of every metric: `eval/README.md`.

| File | What |
|---|---|
| `WerTest` (14) | Word error rate: identical text, 1 deletion of 6 (1/6), 2 substitutions of 5 (0.4), 1 insertion of 4 (0.25), S/D split on a mixed case, WER above 100 %, empty reference / hypothesis, normalisation (case, punctuation, apostrophes, `a.m.`, "two" = "2"), mean versus corpus WER (0.25 versus 0.1). |
| `FieldMatchTest.kt`: `TextNormTest` (5), `FieldMatchTest` (8), `MappingTest` (4) | Normal form and light stemming; match levels per field kind: strict, location (relaxed token containment, "Conference Room" is not "Conference Room 2"), time (`10 AM` = `10:10 a.m.` = ISO `...T10:00...`, 15-minute tolerance inclusive, noon / midnight, unparsable text falls back to words), overlap (60 % of content words); placement classes `CORRECT` / `MISPLACED` / `WRONG` / `MISSING` and the placement rate. |
| `EvalMetricsTest.kt`: `StatsTest` (5), `DialogueInvariantsTest` (9), `EvalMetricsTest` (12) | Median and nearest-rank p90 (odd, even, 10 and 20 values); the scenario-14 invariants (ask-once, a line spoken twice, one `?`, 20 words, "why", banned phrases, escalate-only); the summary built from hand-made `CaseResult`s: WER mean/corpus, extraction accuracy per field, mapping rate, questions per report, outcome mix, mode accuracy, asked-fields variants (exact / includes / excludes), ask-once violations, rephraser pass rate and rejection reasons, latency median/p90, only `ok` cases counted. |
| `EvalCasesTest` (12) | The shipped `app/src/main/assets/eval/cases.json`: at least 20 unique cases, tags cover spec scenarios 1..13 and guide rows 1..6, short / long / noise / fast / place-name cases, the E01 example from the build prompt, well-formed unique audio names, `eval/audio/` holds only `.gitkeep` or WAVs; the parser rejects bad JSON, unknown fields, duplicate ids, duplicate answers. |
| `FileSpeechInTest.kt`: `WavInfoTest` (5), `FileSpeechInTest` (5) | WAV header parsing (canonical, extra and odd-sized chunks, streaming header), `FileSpeechIn`: transcribes the file for the expected field and passes the hint, returns null without a file (STT never called), blank text = silence, rejects non-16 kHz-mono-16-bit files. |
| `EvalRunnerTest` (12) | The real `DialogueManager` through `EvalRunner` with a scripted extraction fake: transcript run scored field by field, answers keyed by field, silent case (no narration), a failing case becomes `error` and the run goes on, LLM prefill/decode attributed only to calls with new metrics, rephraser verdicts into the rephraser metrics, audio run with a fake STT (WER 2 errors in 10 words), missing audio = `skipped`, no STT = everything skipped and "n/a (no ASR)", `results.json` round trip + `results.md`. |
| `EvalControllerTest` (3) | Progress, summary, one run at a time, failure state of the developer-menu runner. |

## Level 3: on-device LLM (real Qwen, text input): what exists

There are 11 instrumented test files in `app/src/androidTest/`. The Qwen LLM benchmark is `app/src/androidTest/java/com/example/test_app/ai/GenieLlmBenchmarkTest.kt` (Genie engine only, no ASR, no dialogue); the Whisper, Gemma, sherpa TTS and eval-run tests are described in the sections below.

| Test | What |
|---|---|
| `benchmark` | Loads `GenieLlmEngine`, runs the 8 integration-guide / spec transcripts (water leak, "Something is broken in the lab.", cafeteria stairs, "Hello, can you hear me?", canteen fire, AC in block C, printer spark, harassment) 3 rounds through `Prompts.extraction`, asserts non-empty output, logs prefill / decode median and p90 (`logcat` tag `BENCH`). Raw outputs: `files/llm_bench/raw.jsonl` in the external files dir; copy lines into `app/src/test/resources/llm_outputs/` as parser fixtures. |
| `benchmarkUpdateAndRephrase` | Latency of the production update and rephrase prompts (3 rounds, logged). |

Needs the model on the board (`doc/DEVICE_SETUP.md`). It does not check extracted fields against expected values: that is what the eval set does (Level 4, text input).
Run (needs the device; not run by the unit-test task), as in `DEVICE_SETUP.md`:
```sh
./gradlew.bat :app:installDebug :app:installDebugAndroidTest
adb -s <serial> shell am instrument -w -e class com.example.test_app.ai.GenieLlmBenchmarkTest \n  com.example.test_app.test/androidx.test.runner.AndroidJUnitRunner
```
**Do not use `connectedAndroidTest` / `connectedDebugAndroidTest`: it uninstalls the app when it finishes, which deletes the models copied into the app's internal storage.**

## Level 4: on-device end-to-end (eval set)

What exists: the **English eval set** (`app/src/main/assets/eval/cases.json`, 36 cases: E01-E30 speech cases and P01-P06 photo-only cases, canonical; `eval/README.md` explains the format, metrics, recording and the adb commands),
`platform/FileSpeechIn` (a `SpeechIn` that reads WAV files and feeds the injected `SpeechToTextService`; null when the file is absent), `eval/EvalRunner` (runs the cases through the real
`DialogueManager` with the services of the current AI mode and writes `files/eval_runs/<run id>/results.json` + `results.md`), the developer-menu item **Run eval set**
(Home title long-press), and `tools/eval_report.py` (adds a run, with date, device, model versions, git commit and one table per metric, above the older runs of `doc/EVAL_RESULTS.md`).

- **From transcripts** ("Run eval set"): works today with Mock AI or Real AI. Speech recognition is not involved, so WER is reported as `n/a (no ASR)`. With Mock AI the extraction is scripted and the numbers
  only prove the plumbing (the menu and the results say so).
- **From audio** ("Run eval set from audio", offered when Whisper CPU (the stopgap ASR) is selected and loaded): needs the WAV files in the app's `files/eval/audio` (`DEVICE_SETUP.md` section 7). The runner skips cases whose files are missing.
  Only the 4 human recordings (E27..E30, `test-1.wav` to `test-4.wav` in `eval/audio/`) exist; synthetic audio is not built. The androidTest `EvalAudioRunTest` runs this path without UI automation (Real AI + Whisper CPU).
- Not built: synthetic audio generation (`TextToSpeech.synthesizeToFile`) and the "Record eval case" screen.
- Results so far: one audio run of E27..E30 with Qwen + Whisper CPU in `doc/EVAL_RESULTS.md` (25 cases skipped: no audio).

**Whisper CPU benchmark (Level 3 for ASR)**: androidTest `ai/whisper/WhisperCppBenchmarkTest` transcribes the 4 samples on the board and logs transcript, WER (against the approximate faster-whisper references in `cases.json`), `asr_ms` and RTF under tag `BENCH`
(options `-e model`, `-e threads`, `-e prompt true`, `-e rounds`). Run it with `am instrument` as in `DEVICE_SETUP.md` section 7, never `connectedAndroidTest` (it uninstalls the app and deletes the models). Numbers: `MODEL_INTEGRATION.md`.
JVM tests for the Kotlin side: `ai/whisper/WavPcmTest` (PCM16 to float, stereo average, extra chunks, streaming header, resampling, rejects).

```bat
gradlew.bat testDebugUnitTest --tests "com.example.test_app.eval.*"   :: the eval metrics, runner and cases (no device)
```

## Manual checklist: live microphone, airplane mode

1. Real AI selected and the model loaded (Home no longer says "Loading models..."). Tap the mic: recording starts at once (no spoken greeting by default, ADR-052); stay silent for 10 s: the "Tap the mic" hint appears.
2. Say the water-leak sentence (eval case E01): no follow-up, a read-back with the type, location and time, then Review with all fields filled.
3. Say "There's water all over the floor.": exactly two questions (location, then what happened), each once; answer one with "I don't know": that field is highlighted on Review and not asked again. Injury is never asked (ADR-050), and no report gets more than 2 questions.
4. Say "There's smoke coming from the server room!": red emergency banner, the safety question once (it counts toward the 2-question cap, so at most one field question follows), nothing says "notified" or "help is on the way".
5. **Airplane mode**: switch it on (also Wi-Fi and Bluetooth off), repeat steps 2 and 3. The flow must behave the same (everything is on-device). Run the eval set in this state too: the numbers must not change.

## Spoken read-outs
`dialogue/FormReadoutTest` (10): complete form, defaulted/spoken time phrases, severe sentence, up to 3 missing/flagged fields, empty form generic line, word cap in the worst case, reference read digit by digit, and no forbidden phrase ("notified", "help is on the way", `blocked_phrases.json`) in any line. `flow/ReadoutPolicyTest` (5): once per Review entry (edits and the return from a submit error do not speak), confirmation once after submit. `dialogue/TtsVoicePickerTest` (5): offline voice choice. The ViewModel's actual TTS calls, the speaker button and the Android engine are not covered by JVM tests.

## Hindi (ADR-047)
`dialogue/HindiTest` (25): language detection by script, Hindi yes/no/unsure/skip/no-change, Hindi URGENT/CARE cues with the before/after negation guard, Hindi escalation cues in the severity policy, and the device transcript "के आर बी के वॉशरूम में पानी लीक हो रहा है ..." through the real mapper + dialogue with scripted model output: `input.language = hi`, transcript untouched, Devanagari location never stored (`unverified` + `raw_answer`), nothing spoken in non-Latin script, "नहीं कोई एंजॉय नहीं है" -> injury false, Hindi yes, the one-translation fallback (and an unreadable translation stays `unverified`), a Hindi location answer through the update path, script never overwriting a good English value. `ChatFormatTest` pins were updated for the Hindi few-shot examples. Not tested: linguistic correctness of the Hindi word lists (needs native review) and real model translations.

## Question cap 2, relevance and the statement read-back (ADR-048)
`dialogue/RelevancePolicyTest` (5 tests, 20 transcripts: complete water leak, fire with "need help", slip with ankle, worker fell, harassment, threat without location, "something is broken", Hindi fire and Hindi washroom leak, snake sighting, power cut, gas smell, electrical spark, ceiling panel accident, AC leak, vague "near the office", spills with and without a person, past cue, "nobody was hurt"): exact asked list, safety check, mode, at most 2 questions in total, read-back without `?`, relevance log content. `QuestionCapTest` (9): PLAIN 2, CARE 2 (not 3), URGENT = safety + 1, escalation spending the 2nd question on the safety check, hard stop with the emergency flag, smaller / larger configured totals, read-back not counted and not listened for. `FollowUpPlannerTest` rewritten for the new priority and conditions plus `explain`. Changed expectations are listed in ADR-048; `DialogueScenarioTest` scenarios 2, 8, 9, 10, 12, 12b, 13 and the eval cases E02, E08, E09, E10, E12, E14, E16, E18, E20, E22, E23 were rewritten.

## Settings (user settings, follow-ups off, delete all)
`domain/UserSettingsTest` (7): defaults (follow-ups on, hands-free on, read aloud on, Normal rate), the three speech-rate values and the unknown-name fallback, the store persists only real changes, `JsonFileReportRepository.deleteAll` (count, empty afterwards, other files kept, missing folder). `dialogue/FollowUpsOffTest` (17): spec v2 scenarios 1-10 with follow-ups off (zero questions, no safety check, no opening, only the narration listened for, wanted fields `missing_flagged`, emergency flag + severity High/Critical for the fire cases, greeting and read-back statement still spoken), the same narration asks 2 questions with follow-ups on, and the hands-free gate (waits for a tap, no tap = `no_response` and the microphone never starts, an early tap is forgotten, hands-free on has no wait, follow-ups off ignores hands-free). Not covered by JVM tests: the Compose Settings screen, the gear visibility, the confirm dialog, the TTS rate on a real engine.

## Language-agnostic STT, no greeting, injury optional (ADR-050/051/052)
New or rewritten: `GemmaTextTest` (11 parser tests: clean two-line reply, English = `en`, language names as reported, missing `Language:` line, unknown language, chatter, markdown, fences, think/channel tokens, multi-line transcript, swapped and single-line labels, label only, instruction shape), `HindiTest` (script fallback for Devanagari / Telugu / Tamil / Odia / Bengali-Assamese / Latin, the STT language name wins, generic `hasNonLatin`, input language stored as reported, the translate fallback for Tamil / Odia / Bengali / Telugu answers and not for Latin), `FollowUpPlannerTest` / `RelevancePolicyTest` / `DialogueScenarioTest` / `SeverityScenarioTest` / `FollowUpsOffTest` / `ScenarioLoopTest` / `EvalRunnerTest` (no greeting, injury never asked; `speakGreeting = true` still speaks it once), `ValidationAndPoliciesTest` (injury not mandatory), `FormReadoutTest` (injury never named as missing), `assets/eval/cases.json` (E06, E17, E18, E21, E26 answers changed; `injury` is no longer an answer/outcome key). The golden `chat_golden/gemma4.json` transcribe entry and the `gemma_multimodal_*.txt` fixtures (assistant prefix `{"language":`) were hand-updated. Not tested: real Gemma audio output for Tamil / Assamese / Odia (device).

## Multilingual STT check (Gemma, on device)
`GemmaLanguageTest` transcribes `files/lang/*.wav` (copy `eval/audio/test_{assamese,odia,tamil,telugu}.wav` there with `run-as`, see DEVICE_SETUP.md) and logs `LANG | language | asr_ms | text`.
Measured 2026-10-11 (Gemma 4 E2B, language-agnostic prompt ADR-051), asr_ms 1.7 to 3.2 s:
| Clip | language reported | script | verdict |
|---|---|---|---|
| test_assamese | assamese | Assamese (Bengali-Assamese script) | correct |
| test_odia | odia | Odia script | correct |
| test_telugu | telugu | Latin transliteration | language correct |
| test_tamil | hindi (wrong) | Devanagari phonetic | model limitation: with a "translate" prompt it returns Latin phonetics ("Himalaya class la bulb ti purchichu ...") |
A prompt that lists candidate Indian languages made it worse (Telugu named Tamil, Tamil named Odia), so the prompt names no languages.

## Gallery photos (ADR-053)
`media/ImageCompressionMathTest` (9): every EXIF orientation 1-8 against its definition (mirror first, then rotate, checked pixel by pixel on a 4x3 grid), mirror flags, rotated photos swap sides before fitting, `inSampleSize` never decodes the full bitmap (long side >= 512 and < 1024), small photos are not upscaled, final long side <= 512 for rotations and odd sizes, JPEG quality 80, at most 3 photos and the dropped-photo notice. `FlowReducerTest` (photo events, empty photo list = plain voice, photo mode kept by add-more, retired `voice_and_camera` read as `voice_and_photo`). `PhotoHintsTest` (photo-only: empty narration, prompt = `PHOTO:` line only, AI fields <= 0.5 confidence, the usual questions are still asked; narration + photos keeps the user words first). Not tested by JVM tests: `ImageCompressor` decoding on real photos, the picker UI, the Compose Home changes.

## Spoken language (ADR-054)
Tests: `ChatFormatTest` (forced prompt equals the golden Gemma turn `transcribe_forced_tamil` and the exact text for any name, forced English = the plain instruction, a name cannot inject chat tokens, Auto prompts and prefix unchanged, one-call forced line + pre-filled `language` key, photo-only one-call not forced), `GemmaTextTest` (forced reply is plain text with the chosen language, the two-line parser is not used, `GemmaSpeechToText.prompt` / `parseReply` choose by the setting), `UserSettingsTest` (default `auto`, round trip, `languages.json` read from disk, strict list parser). The golden entry `transcribe_forced_tamil` was written by hand in the model's turn format (same structure as the verified `transcribe` entry), not regenerated with jinja2.

Measured on the device, 2026-10-11 (prompt lab, Gemma 4 E2B, real clips; not produced by the app code):

| Clip | Auto prompt: language / script | Forced prompt: result |
|---|---|---|
| assamese | assamese / Assamese script | Assamese script, good |
| odia | odia / Odia script | Odia script, good |
| telugu | telugu / Latin transliteration | Telugu script, good |
| tamil | hindi (wrong) / Devanagari phonetic | Tamil script, usable |

Not verified on a device: the Settings row, the Home chip and the sheet in the app, the forced prompt through `GemmaSpeechToText` and the one-call path with a language set (the lab used the prompt text directly).

## Knowledge graph (ADR-055..057)
`app/src/test/java/com/example/test_app/knowledgegraph/` (168 tests): `ConditionEvaluatorTest` (every condition operator, unknown facts name the missing fact), `EngineApprovalTest` (approval, draft, disabled, expiry of recommendation and SOP, unverified and rejected sources, sourceless, incomplete approval, edges, unconfirmed input, labels), `EngineSafetyTest` (restart of fire-damaged equipment, touching electrical devices near water, authorized-only, enter/fight fire/chemicals/shut-offs, medical classes, diagnosis/medicine/dosage/treatment, footer, unverified and verified contacts, phone numbers, dispatch claims), `EngineSelectionTest` (safety order, prerequisites, limits, dedup, paths, consequences, arrangements, teams, weights), `EngineAbstentionTest` (unknown type, location, asset, status; one question; no invented classroom), `HistoryPatternTest` (recurrence scopes, windows, thresholds, citations), `LlmGroundingTest` (ids, numbers, names, medical terms, phones, fallbacks, prompt), `AuditAndAdminTest` (audit completeness, import validation, production approval rule, versions, rollback, file repository), `InputAdapterAndServiceTest` (report mapping, service, export redaction, outbox), `SeedPackTest` (pack validity, demo-source policy, 12 scenarios, taxonomy), `SampleOutputTest` (end to end with the file stores; writes `app/build/kg-samples/`, copied to `doc/samples/kg/`). Not covered by JVM tests: the Compose card, the view-model wiring (`startAnalysis`) and the Android asset loading of `seed_pack.json`.

## Admin dashboard (ADR-058)
`app/src/test/java/com/example/test_app/admin/` (125 tests): `ForceLayoutTest` (determinism, bounds, no NaN, convergence, 0/1/2 nodes, disconnected components, pinned nodes, cache, reheat, speed), `GraphSubgraphTest` (kinds, relations, approved-only, orphans, local depth, search, node cap, highlighted nodes kept), `IncidentPathTest` (provenance parsing, path of a small graph and of the seed computer-fire audit, suppressed candidates, admin preview), `OverviewAndFilterTest` (windows, severity/type counts, 14-day bucketing, source expiry, unverified counts, report filters and search), `LogParserTest`, `PinImportDiffSettingsTest` (PIN, import diff, analysis settings), `EditorFormsTest` (form validation, list rows, preview choices), `AdminReducerTest` (state transitions), `AdminBackendTest` (real temp stores: snapshot, clearing, demo generation and removal never touching real reports, preview), `AdminViewModelTest` (unlock, PIN change, settings, demo, analysis, import with confirm and rollback, edits, preview). Not covered by JVM tests: every Compose screen (the tabs, the graph canvas and its gestures, the PIN dialog, the share sheet, the document picker), the Home icon and the Settings wiring.

## Sherpa TTS (ADR-059)
JVM: `ai/tts/SherpaVoicesTest` (23): Latin -> Piper whatever the hint, per-script routing and speaker table, language name chooses within a script (Marathi / Nepali / Assamese), a contradicting hint is ignored, Hindi speaker, Arabic / Cyrillic / CJK / Odia / Gujarati -> System, developer-menu speaker and emotion override and range checks, Rasa chunking (trailing punctuation, ellipsis, closing quotes, abbreviations "K.R.", short-sentence merge, decimals, newlines) and the speed mapping. Wiring is not unit-tested.
Device (`SherpaTtsTest`, `SherpaTtsRoundTripTest`; `TTS.md` "Device test and round trip"): sentence set with timings and amplitude statistics (tag `TTSLAB`), Rasa speaker x emotion grid, borrowed speakers for Hindi / Odia / Gujarati, thread scaling, fp32 diagnostic (needs `files/tts_diag/`), service speak / stop / fallback; Gemma STT round trip of the WAVs (tag `TTSRT`). English WAVs additionally go through faster-whisper on the PC. Results: `TTS.md`. Nobody has listened to the audio.

## Language packs and localized speech (ADR-060)
JVM: `dialogue/LanguagePackTest` (language name / code / script mapping, pack lookup and English fallback, complete `readout` keys and placeholders, packs in their own script, glossary coverage, template and glossary parsing, exact Hindi and Telugu review / submitted lines, minutes and "just now", severe sentence, missing-field labels, no Latin letters left in any type x severity x injury x time combination, English location kept as an English run, glossary and template gaps fall back to English, dialogue sessions in Hindi / Telugu / script-only / Tamil (no pack) / English, forced-language greeting, no rephrase outside English); `ai/tts/SherpaVoicesTest` (script runs, per-segment plans, hint on non-Latin runs only, pauses, resampling); `HindiTest` and `PhraseValidatorTest` rewritten where they encoded "spoken lines are English".
Device: `LocalizedSpeechTest` runs a Hindi / Telugu / Tamil / English session through the real container, dialogue and TTS (tags `TTSLOC`, `SherpaTts`) and renders the localized lines to `files/tts_out/<hi|te>_loc_*.wav` (+ `.txt`); `SherpaTtsRoundTripTest -e prefix hi_loc` transcribes them with Gemma (forced language). Results: `TTS.md`. Nobody has listened to the audio.

## Spoken lines are really played (ADR-061)
JVM `ai/tts/PcmBufferPolicyTest` (start threshold far below the buffer, API 30 padding). Device `AudioTrackProbeTest` (`headAdvances` logs playbackHeadPosition for buffer lengths and thresholds, tag `ATPROBE`; `policyTrackPlaysAShortLine` asserts the shipped configuration plays a 0.33 s line completely). Whole path by hand: developer menu -> Mock AI -> scenario "Vague location" -> start a voice report; `adb logcat -s TtsSpeechOut SherpaTts` must show `playback finished head=N written=N` (equal) for every question. A line `the AudioTrack never started` means a silent utterance.

## Photo-only reports (ADR-062)
JVM: `ai/PhotoPromptsTest` (golden renderings `src/test/resources/prompts/*.gemma4.txt`, this prompt text verbatim in `PROMPTS.md`, rules, examples not taken from the test scenes, sanitizing, the service picks the photo-only prompt only without a narration, generic locations dropped and real ones kept); `dialogue/PhotoHintsTest` (photo-only: nothing asked, `listen` never called, only the read-back spoken, fire: no safety question and no defer line but the emergency flag, photo + narration keeps the questions); `eval/EvalCasesTest` (+3: the six `P` cases, parser rules) and `eval/EvalPhotoCasesTest` (runner: no utterances, severity-floor row, skipped without service / images).
Device (images pushed once: `adb shell mkdir -p /data/local/tmp/images_test`, `adb push <image> /data/local/tmp/images_test/`, then `run-as com.example.test_app cp` into `files/images_test/`; the eval runner reads `files/eval/images/`): `ai/llama/PhotoReportLabTest` (`describeThenExtract`, `oneCallImageOnly`, `audioWithThisEncoder`; `-e mm none` = CPU encoder, `-e only name`), `ai/llama/PhotoOnlySessionTest` (real dialogue + knowledge graph; asserts no mic, nothing asked), `eval/EvalPhotoRunTest` (`-e cpu true`). Tags `PHOTOLAB`, `EVALPHOTO`. The first generate of a process on the GPU encoder sometimes aborts (`clSetKernelArg error -48`): retry the instrumentation run.

Results on the QIDK, 2026-10-11, Gemma 4 E2B, final prompts, image encoder on the CPU (stable: identical in 4 runs). Eval cases P01-P06 (`EvalPhotoRunTest -e cpu true`): 6 ok, field accuracy 11/12, 0 questions, 0 invariant violations, every severity floor met.
| Image (ground truth) | Description (Gemma) | Type | Severity | Location | Describe / extract ms |
|---|---|---|---|---|---|
| brokenpipe.jpeg (pressurised pipe joint spraying water in a trench) | large metal pipe, heavily corroded, appears to be leaking or damaged | Water Leak | Medium | - | 7437-13897 / 2603 |
| fire.png (burnt lab, soot around an electrical panel) | interior space with significant smoke, fire or intense heat damage | Fire (expected Fire) | Critical (floor High) | - | 7437 / 2474 |
| waterleakingfromAC.jpg (AC dripping, wall stain) | AC on a wall, significant water leak from the bottom | Water Leak | Medium (expected Low-Medium) | - | 7651 / 2763 |
| office_fire.jpg (flames and smoke from upper floors of a building) | building with a significant fire and heavy smoke from the upper floors | Fire | Critical | - (GPU: building exterior) | 7677 / 2577 |
| short_circuit.jpg (burnt socket) | wall with a power outlet engulfed in thick black smoke | Electrical Hazard | High | - | 7060 / 2723 |
| toilet_foam.jpg (foam overflowing from a toilet) | bathroom with a bathtub filled with white foam | **Spill (expected Water Leak)** | Medium | bathroom | 7163 / 2543 |
The first describe call of a process is slower (13.9 s). Per image about 10 s in the eval run (describe + extract + session). Same run with the encoder on the GPU: describe 2.4-4.5 s, extract 2.5-2.8 s, but in roughly half of the whole-set runs at least one image was misread (pipe as "a person's clothing" / "a tree trunk" / "a kitchen fire", AC as "no incident", burnt lab as "a person using a machine") and several instrumentation starts aborted in OpenCL (three in a row on the last eval run).
Real dialogue sessions + knowledge graph (`PhotoOnlySessionTest`, GPU encoder, a good run): 0 `listen` calls, 0 questions, one spoken line (the read-back), ~4.8-7 s per photo end to end. Post-incident analysis of the SUBMITTED image-only report does not abstain: 2-3 immediate actions, 2-3 operational steps, 1-4 responsible teams, and the unresolved question "Was anyone hurt?"; before submit it abstains ("not confirmed yet") as for every report. One poor suggestion: foam from a toilet (Spill) gets "Stay away from the spilled material" (the chemical-spill action).
One-call image path (`multimodalExtraction`, GPU): 3.7-5.8 s per image against ~5 s for describe + extract: pipe Water Leak High, burnt lab **Water Leak** (wrong), building Fire Critical, socket Electrical Hazard High, foam Spill Low, AC Water Leak Low; location null everywhere.
Encoder device for speech (`audioWithThisEncoder`, 4 clips): GPU 1.3-3.0 s, CPU 2.3-5.3 s after a 14.8 s first call; the CPU clip "test-1" was heard correctly ("pipe leaking"), the GPU one as "five leaking".
UI (real board, Gemma backend, screenshots `build/shots/photo_*.png`): Home with one photo shows the thumbnail, the primary "Use photos only" button, the mic ("Or tap and describe what happened.") and "Remove photos"; "Use photos only" ran describe + extract, spoke the read-back and opened Review with the photo, "What happened" and "Location" highlighted as AI-filled / please check, Severity Medium.
