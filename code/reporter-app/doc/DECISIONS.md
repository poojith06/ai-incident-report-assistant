# Architecture Decision Records

## ADR-001 Stack: native Android, Kotlin, Compose, MVVM, Coroutines/Flow
Why: the target (Qualcomm QIDK) runs QAIRT/QNN/AI Hub models most directly from native Android. Alternatives
(Flutter, React Native) add a bridge layer between the UI and native model runtimes. Prompt default; no deviation.

## ADR-002 One pure reducer + a ViewModel that runs effects
The whole flow is a single state machine (`flow/FlowState.kt`). `FlowReducer.reduce` is pure so every transition is
unit-testable without Android. The ViewModel performs side effects when a state is entered. Alternative: logic
spread across several ViewModels and nav callbacks (rejected: the clarification loop and error retries become
hard to reason about).

## ADR-003 No navigation library
The screen is a pure function of `FlowState` (plus two UI-only flags: history screen, dev sheet in `AppRoot`).
Back button maps to `Cancel`. Avoids a second source of truth for "where am I".

## ADR-004 Manual DI via `AppContainer`
Few services, one swap point, zero codegen. Hilt/Koin rejected as overhead. Swap a mock by editing one line.

## ADR-005 Extraction service returns RAW JSON text
`IncidentExtractionService.extract` returns a `String`, parsed by `IncidentJsonCodec`. A real LLM emits text; this
keeps the parsing/failure path ("JSON output" step) real and tested. The codec tolerates Markdown code fences.

## ADR-006 JSON via kotlinx.serialization; field values are `JsonElement`
Field values are heterogeneous (string, bool, string list, witness list, null). `FieldEntry.value` is a
`JsonElement` with typed helpers in `domain/model/IncidentField.kt`. Keeps the wire format identical to the contract.

## ADR-007 Camera path = photos only (no video) (superseded by ADR-053: the camera was removed, photos come from the gallery)
User takes one or more photos while the microphone records. Simpler, matches "image metadata" input, and
CameraX `ImageCapture` + `AudioRecord` do not contend for the mic. Video is listed in `TODO.md`.

## ADR-008 Field set follows the team's "recommended field set" (supersedes the original prompt's list)
12 fields in 4 groups (required / situational / optional / app-filled). Mandatory = description, injury/outcome,
location, date-time. Severity, incident type and recommended follow-up are never asked: the LLM or app fills them.
Details: `DATA_SCHEMA.md`. Schema version bumped to `1.1`.

## ADR-009 Rules the app owns (real logic, not LLM)
`IncidentPolicies`: time defaults to now; GPS as location backup; severity suggested from injury + hazard (never
overwrites a user-set severity). `DefaultValidationService`: missing / low-confidence / situational follow-ups.
The mock LLM deliberately does not do these, so the real LLM does not have to either.

## ADR-010 Ask only injury/location/description/persons/actions, one at a time, in `askOrder`
`IncidentConfig.askOrder` is the whitelist and priority order (injury first). Situational follow-ups
(`persons_involved`, `immediate_actions_taken`) are asked at most once (tracked in `clarification.history[].asked_fields`)
and never block submission.

## ADR-011 Submit is blocked only by missing required fields
Low-confidence fields are highlighted but do not block (user may confirm by editing). `is_complete` (no missing and
no low confidence) still drives the clarification loop.

## ADR-012 TTS failure is non-blocking
If TTS is unavailable, a dismissible banner appears and the flow continues on screen. The clarification screen has
"Read aloud again" as the retry path. Confirmation/"submitted" speech is fire-and-forget (navigation does not wait).

## ADR-013 Strings are hardcoded in Kotlin
Single-language demo; moving to `strings.xml` is listed in `TODO.md`. Colors/typography/spacing are NOT hardcoded.

## ADR-014 GPS deferred
"GPS as backup" and "GPS from device" need a location permission flow and a fix strategy. The data model
(`DeviceInfo.gps`) and policy (`IncidentPolicies`) are ready; capture is not implemented. See `TODO.md`.

## ADR-015 Mock STT ignores the audio
Scripts are chosen by scenario and by `SttHint.expectedField` (which question is being answered). Real STT must
ignore the hint.

## ADR-016 Voice path stays on one Home frame
Tapping the mic does not navigate: the same `HomeScreen` shows idle, recording (mic -> stop button, waveform below) and
first-pass processing (spinner + steps + transcript), so nothing jumps. `AppRoot` picks the frame with `isFirstPass`.
Answer passes use the full `ProcessingScreen`. (The camera path had its own screen; it was removed by ADR-053.)
Alternative: separate Recording and Processing screens (rejected after user feedback: abrupt transitions).

## ADR-017 Clarification answers are hands-free
After the spoken question the recorder starts by itself and stops on a 2 s pause after speech (level-based, no VAD
model); the stop button also works. A silent environment never auto-stops (user taps). "Skip and fill manually" is a
filled tonal button so the escape hatch is as visible as the mic.

# Phase 2: follow-up dialogue (authority: build prompt > `specs/FOLLOWUP_SPEC_v2.md` > `specs/APP_INTEGRATION_GUIDE.md` > Phase 1 docs)

## ADR-018 LLM = `LlmEngine` (Genie-backed later), no LiteRT; rephrase is a fresh conversation
Spec v2 mentions LiteRT-LM / Gemma. Ignored: wherever it says "LLM" read "the `LlmEngine` interface backed by Genie
(Qwen3-4B)", written by someone else. The dialogue package only sees `IncidentExtractionService` and `Rephraser`.
Each call (extraction, update, rephrase) starts a fresh dialog, so nothing leaks between calls.

## ADR-019 Ask-once wins; `incident_type` is never asked; thin input asks `description` once
The guide's "answer 'I don't know' twice -> stops" is replaced by spec v2: each field is asked at most once, "I don't know"
makes it `unknown_reporter_unsure`. The guide's "Hello, can you hear me?" -> "What kind of incident was it?" is replaced:
`incident_type` is never asked; the description is thin (no `incident_type`), so the planner asks `description`
("What did you see?") once.

## ADR-020 Keyword-list refinements to make the spec's own scenarios pass
`mode_keywords.json` follows spec 3.2 except: (1) bare `broke` is replaced by `broke my/his/her/their`, `broke a bone`,
`broken bone/arm/leg`: scenario 10 ("The door handle broke.") must start PLAIN and only escalate after "I cut my hand";
(2) added `injuries` ("no injuries" is a spec negation example), `harassing` (scenario 6 says "harassing"; spec lists
harass/harassed/harassment). Known false positive kept: `fell` ("a chemical bottle fell and broke", scenario 7)
escalates to CARE after the first answer; nothing else is asked then, so the scenario outcome is unchanged. `hi`/`te`
lists are copied as given and marked `needs_native_review`; negation words `koi`, `nahi`, `nahin`, `ledu` were added
so "koi chot nahi" works (also needs review). The negation window is 4 tokens, within one clause (commas and sentence
ends reset it), and applies only to the injury-related CARE keywords.

## ADR-021 Injured people live in `persons_involved` as `"<who> (injured)"`
The build prompt says "add entry with `role: injured`", but `persons_involved` is a `string[]` (UI, validation,
codec, mock all assume it). Changing it to objects would touch all of them. Instead an injured person is a string ending
in ` (injured)` (`DialogueFields.INJURED_SUFFIX`); `injured_person` is "filled" when such an entry exists,
`people_involved` when a non-injured entry exists. The Genie extraction must follow this convention.

## ADR-022 Statuses on `FieldEntry` are nullable; sources are report-level
`FieldEntry.status` is `String?` with `effectiveStatus` (null = `filled`/`empty` from the value) so every existing
`FieldEntry(value, confidence, source)` call site, the Review edit path (an edit clears the highlight) and old JSON keep
working; the dialogue stamps all 14 fields. `location_source` and `time_source` are top-level report keys instead of
keys inside the `incident_location` / `date_time` field objects (those objects stay uniform; the prompt text allows both readings).

## ADR-023 `injury` (bool) and `injury_outcome` (enum) coexist
Phase 1's `injury_outcome` is mandatory and drives severity; the dialogue needs a plain yes/no. `DialogueForm.reconcileInjury`
derives whichever is missing: outcome -> `injury = outcome != "No injury"`; `injury == false` -> "No injury";
`injury == true` -> "Minor injury" at confidence 0.5 (below the 0.6 threshold, so Review highlights it as uncertain and the
user can pick the real level). Without this a dialogue-only report with injury=true could never be submitted.

## ADR-024 Question bank: one `?` per question, fixed lines in the bank
Spec 6's `people_involved` CARE text has no question mark ("If you want, you can tell me who was involved..."), which
breaks the "exactly one `?`" rule and scenario 14. Replaced by "Who was involved? You can also skip this."
The fixed lines of spec 5.1 (opening, safety check, defer, read-back, closing, typing offer) are stored under `lines` in
the same JSON so they are translatable; they are still never LLM-generated. A field with no entry for the mode falls back to `CARE`
(URGENT per spec; PLAIN for `injured_person` etc., which only exist in CARE).

## ADR-025 "Thin" = fewer than 6 content words, where articles and fillers do not count
Spec 4.3 says "content words" without a definition. Scenario 3 ("I slipped in the pantry and I'm hurt.", 7 words) must not
ask `description`, so stop-words are not removed; only `a`, `an`, `the`, `uh`, `um`, `er`, `hmm` are (list in `field_keywords.json`).
Thin also when `incident_type` is missing or the description is empty. `description` is asked iff thin (and not yet asked).

## ADR-026 Emergency in this phase: banner flag + one safety question, nothing sent
URGENT sets `dialogue.emergency = true`, speaks the safety check once (no answer = assume not safe), asks at most 2 fields,
then speaks `defer` once if anything is unresolved. `{next_step}` is always empty; no line says "notified" / "help is on the way".
If the mode reaches URGENT later (also after the read-back), the safety check is spoken once at that point. The Review banner
tells the user to get safe and contact services themselves and says nothing was sent. Real alerting stays in `TODO.md`.

## ADR-027 Extraction contract for follow-up answers
No interface change: answers use `ExtractionRequest(previousReport, answerTranscript, answeredQuestion)` with
`targetFields = [<dialogue field key>]` (dialogue keys, not report keys; the extractor maps them with `DialogueFields.reportKey`).
A read-back correction has empty `targetFields`. The manager merges field by field (non-empty new value wins and becomes
`user_voice`; an empty new value never erases a known one), so an LLM that returns only changed fields works as well as one that
returns the full report. `MockIncidentExtraction` understands the dialogue keys so the Phase 1 mocks keep working.

## ADR-028 `ToolExecutor.fillIncidentFormWithHint`
`fillIncidentForm(report): IncidentReport` is unchanged (the Phase 1 ViewModel uses it). A new interface method with a default
implementation returns `ToolResult(ok, report, hint)`; the hint (`missing`, `next_field`, `mode`, `template`) is computed by the
dialogue, which owns `askedFields`, and travels with the result. `ToolResult.toJson()` is the spec 9 shape.

## ADR-029 Dialogue is built and tested but not wired into the ViewModel
`DialogueManager` has a clean API (`run`, `onMicTap`, `uiState`, `close`); `IncidentFlowViewModel` still runs the Phase 1
loop. Hook-up steps and the proposed merged state diagram are in `DIALOGUE.md` and `STATE_MACHINE.md`. `AppContainer.dialogueAssets`
(lazy) and a debug-build touch in `IncidentApp.onCreate` implement "validate assets at app start, fail loudly in debug builds".
`run(narration)` skips the greeting when the caller already captured the first utterance.

## ADR-030 Review: token-only highlights and a new `EmergencyBanner`
Fields with status `unknown_reporter_unsure` / `no_response` / `unverified` / `missing_flagged` get the warning border and a caption;
`dialogue.emergency` shows `EmergencyBanner` (errorContainer fill, error border and icon, not dismissible). No colours outside `Theme.kt`.

# Phase 2: dialogue wired into the app, real AI switch

## ADR-031 The dialogue owns the whole voice turn; the Phase 1 clarification loop is removed
`FlowState.Dialogue` replaces `RecordingVoice`, `AskingClarification` and the validate -> question -> answer loop (`QuestionSpoken`,
`ClarificationService`, `TemplateClarificationService`, the dev-menu "max rounds" slider are deleted; `IncidentConfig.maxClarificationRounds` stays
only as report metadata). Same flow for Mock AI and Real AI. "Add more by voice" from Review keeps the small transcribe -> extract -> validate loop
(`AwaitingClarificationAnswer`, `Transcribing`, `Extracting`, `Validating`) with an empty target list, because it needs no questions.
`FillingForm` no longer speaks: the dialogue's read-back already did (DialogueManager is the only thing that decides what is spoken);
submit speaks the dialogue's closing line through `manager.close()`.

## ADR-032 Camera path: transcribe first, then the dialogue without a greeting (superseded by ADR-053)
The camera screen still records while photos are taken. On Done the recording is transcribed (`SpeechToTextService`) and passed as
`DialogueManager.run(narration = ...)`, so the greeting is skipped (the user already spoke). The audio file is kept until the dialogue finishes so Retry can reuse it.
After any dialogue error, Retry re-enters `Dialogue` with `Session.greeted = true` (`run(greet = false)`): the greeting is never repeated.

## ADR-033 `MicSpeechIn`: energy VAD, stop button always works, errors
Level thresholds are the Phase 1 ones (speech >= 0.10, silence < 0.06, stop 2 s after speech). No speech within the timeout (8 s answers, 10 s narration) returns null
(= `no_response` / the "tap the mic" hint). The stop button (`stopNow`) ends the recording and transcribes it even when no speech was detected, so the silent emulator
microphone and the mock STT still work for demos. A recording under 1 s is treated as silence. For the first narration, mic/STT errors reach the user as error screens
with Retry; for answers they count as "no response" (ask-once: never retried).

## ADR-034 `FieldAwareSpeechIn`: telling the speech input what is being answered
`SpeechIn.listen(silence, max)` is the spec interface; the mock STT scripts answers by field, so an optional sub-interface `FieldAwareSpeechIn.expectAnswerFor(field)`
is called by the manager before each listen (`safety` / `readback` for the control lines, null for the narration). Real recognisers ignore it (like `SttHint`).

## ADR-035 Mock AI / Real AI switch; speech-to-text stays mock; typed transcript
`DevSettings.useRealAi` (default false, persisted) selects `GenieIncidentExtractionService` vs `MockIncidentExtraction`; `useLlmRephrase` (default true, persisted) enables
`LlmRephraser` in Real AI. Whisper is not available, so `SpeechToTextService` is the mock in BOTH modes; no Whisper wrapper was invented. To test the real LLM with chosen
sentences the developer menu has a typed transcript (one utterance per line; consumed by the mock STT instead of its scripts; safety/read-back replies never consume a line).
It is labelled in the menu and in the docs as a stand-in.

## ADR-036 Model loading, failure handling, threading
`AppContainer.ensureModelsLoading()` loads Qwen once at app start (and when the switch flips) on `Dispatchers.Default`, publishing `AiStatus` (Mock / Loading / Ready / Failed).
Home shows "Loading models..."; the mic stays tappable; the extraction service awaits `engine.load()`, so the recording is processed when the model is ready.
`ModelLoadException` -> `FlowError.MODEL_FAILURE`; model and extraction errors in Real AI mode show "Switch to Mock AI" next to "Try again" (Try again re-arms the load).
Genie is not thread-safe: `GenieLlmEngine` owns a single thread and resets the dialog before every call (extraction, update and rephrase never share history).
Known limit: `LlmRephraser`'s 1500 ms timeout stops waiting but cannot interrupt the native query, so the next call (usually the extraction of the answer) queues behind a slow rephrase.

## ADR-037 Interim prompts verbatim; our own parser, mapper and rules
`IncidentRules.kt` was not provided. `ai/Prompts.kt` holds the board prototype prompts verbatim (`eval_prompt.txt`, `upd_test.txt`; both force an assistant prefix so no `<think>` is produced) plus the
spec 8.2 rephrase prompt. `LlmOutputParser` (tolerant: think blocks, `<|im_end|>`, repeated `{`, trailing text, fences, truncated JSON) and `ModelOutputMapper` (model keys -> report fields) are ours.
The prototype schema has no injury key, so `injury` is derived by rules only: type `Injury` or a positive mention -> true, an explicit negation ("nobody hurt", "no injuries") -> false, otherwise empty (the dialogue asks once).
Yes/no, injured-person and injury-type answers are parsed deterministically without the LLM; location/time/actions/people/description answers and free corrections use the update prompt. Emergency keywords
bump severity to at least High (confidence 0.6). Spoken times are converted to ISO by `SpokenTime`; unparsable times stay empty (never invented). Fixtures in `app/src/test/resources/llm_outputs/` are hand-written
until real device outputs are saved.

# Phase 2: evaluation harness

## ADR-038 Eval set: one canonical `cases.json` in the assets, answers keyed by field, audio optional everywhere
- `app/src/main/assets/eval/cases.json` is the only case list (the app reads it from assets, the JVM tests from disk). There is no `eval/cases.json` copy: copying needs a Gradle step and a second file can drift.
  `eval/audio/` stays at the repo root (WAVs are pushed with adb to `<external files dir>/eval/audio`, `eval/README.md`); it holds only `.gitkeep` because no audio exists yet.
- Build prompt section 8's case schema is extended, not changed: `follow_up_answers` are objects keyed by the **dialogue field the question is about** (plus `safety` / `readback`), not an ordered list,
  because the planner chooses the order and a position-based script would silently answer the wrong question when the planner deviates. A question without an entry gets silence. Each entry has an optional `audio`
  and (typed input only) an `asr_conf` to simulate the "unclear" outcome. `expected` gains `asked_includes` / `asked_excludes` (looser than the exact `asked_fields`), `outcomes`, `emergency`, `empty_fields`
  (never invent) and `result` (`no_narration` for spec scenario 11). The Hindi "pata nahi" of scenario 7 is written as "I don't know" (English-only phase).
- Expectations follow Spec v2 section 12 and the guide checklist as resolved here (ADR-019), not the current behaviour of the app; a mismatch is a finding. Severity is app-policy-derived (`IncidentPolicies`), so it is not an expected field.
- Everything is ASR-optional. A **transcript run** types the transcripts (WER `n/a (no ASR)`); an **audio run** goes through `FileSpeechIn` and the injected `SpeechToTextService`. Without a real STT (Whisper is not on the board;
  `AppContainer.hasRealStt == false`) the menu offers no audio run and `EvalRunner` marks every case `skipped`: a mock STT ignores the audio, so a WER from it would be a fabricated number. A case whose WAV is missing is `skipped`, never run on silence.
- The runner drives the real `DialogueManager` (fresh manager per case, silent speech out, per-case timeout 180 s, errors become `error` cases) so the numbers describe the shipped dialogue. Extraction calls are timed by a decorator; prefill / decode
  come from `LlmMetricsProvider` and count only when the call produced a NEW metrics object (updates answered by Kotlin rules make no LLM call). "Total" = narration ASR + first extraction (end of speech to filled form). Rephraser
  numbers come from the dialogue's own JSONL turn log. `ToolExecutor` is not passed (its mock delay would only slow the run). The first mic tap of scenario 11 is simulated once.
- Metric definitions are in `eval/README.md` and unit-tested with hand-computed values: WER over normalised words (number words = digits); field match = exact / normalised / relaxed (location token containment, time of day within 15 min,
  60 % content-word overlap for descriptive fields); mapping placement rate = correct / (correct + misplaced); latency median and nearest-rank p90. The overlap rule for descriptive fields goes beyond the prompt's "location and time use relaxed matching"
  because free-text fields can never match exactly; it is documented and reported as `RELAXED`, separate from `EXACT` / `NORMALIZED`.
- Results are `files/eval_runs/<run id>/results.json` (+ `results.md`, schema `eval_results/1`); `tools/eval_report.py` adds git commit hash and renders the per-metric tables into `EVAL_RESULTS.md`, newest first. Nothing is estimated: a metric without data is `n/a`.
- Synthetic audio via `TextToSpeech.synthesizeToFile` and the "Record eval case" screen are not built: the TTS wrapper has no file synthesis and the engine writes its own sample rate (a resampler is needed), which does not fit the small-step budget (`TODO.md`).

## ADR-039 Spec-versus-code gaps visible while writing the eval expectations
Read off the code, not yet observed on a device run. They are not changed here (they belong to the dialogue / extraction owners); the cases keep the specified behaviour so the first device run shows them:
- Spec scenario 1 / the build prompt's E01 expect zero follow-ups for the water-leak sentence, but the first-pass mapper leaves `injury` empty unless the transcript mentions or negates an injury (ADR-037) and the planner asks `injury` whenever it is unfilled
  (Spec 4.2 priority 1), so "Was anyone hurt?" would be asked. The spec's scenario tests pass because their scripted JSON contains `injury = false`.
- "Hello, can you hear me?" (guide row 4, ADR-019): `description` is priority 5, after `injury` and `location`, and the PLAIN cap is 2, so the description question is not reached. Case E16 expects `[injury, location]` and says so in its notes.
- Spec scenario 3 expects only `injury_type`, but the mapper does not tag the speaker as `(injured)` on the first pass and the prompt rates "anyone hurt" High, which makes `injured_person` and `immediate_actions` eligible. E03 uses `asked_includes` / `asked_excludes`.

## ADR-040 whisper.cpp CPU stopgap for speech-to-text (NPU Whisper stays a TODO)
- Context: the board has no NPU Whisper model, so the whole audio path (record, ASR, extraction, dialogue) could only run with the mock STT.
- Decision: vendor whisper.cpp v1.7.6 (ggml CPU only, no network code) and implement `SpeechToTextService` as `ai/whisper/WhisperCppSpeechToText`, labelled everywhere as a CPU stopgap. English forced, transcribe mode.
  `AppContainer.speechToText` is a forwarding object that picks Mock or Whisper CPU per call from `DevSettings.sttChoice`; null choice = Whisper CPU when Real AI is on and the model file exists.
  `hasRealStt` = Whisper CPU selected and loaded. Whisper loads at app start with Qwen (one "Loading models..." state); a failure shows Retry and "Switch to Mock speech" (STT errors) or "Switch to Mock AI" (LLM errors).
- Model/threads chosen by measurement on the board (MODEL_INTEGRATION.md): small.en, 6 threads: WER 0 % on the 4 samples vs 11 % for base.en, 3.05 s vs 1.0 s per utterance. The place-name prompt is a dev flag, off by default (no measured gain).
- Consequences: ~3 s ASR per utterance on the CPU (RTF 0.3-0.9) and 488 MB of model on the device; CPU contention with the UI during recognition. Confidence is a constant 1.0 (the low-confidence "unclear" path never triggers from Whisper).
  The eval audio directory now falls back to the internal `files/eval` (boards that deny the external path). Replace the class, not the interface, when the NPU Whisper arrives.

## ADR-041 Severity is decided by a deterministic policy; the LLM severity is only a hint
- Context: on the device the model rated a fire "Medium", and `IncidentPolicies` then replaced the severity with the injury table (an unknown/minor injury gave Medium), so an URGENT fire or a bleeding person could be saved as Medium. The app must not decide urgency badly.
- Decision: `domain/service/SeverityPolicy.decide` = `max(hint, floors)`. Floors: the old injury/hazard table; mode URGENT (or the emergency flag) -> at least High; URGENT and (an un-negated escalation cue, or an injury) -> Critical; incident type Fire / Gas Leak / Electrical Hazard -> at least High. Cues and types are in `assets/dialogue/severity_cues.json` (English; negation words and window shared with `mode_keywords.json`). `IncidentPolicies.apply(report, now, cues, extraTexts)` calls it after every pass; the hint is the field's current value, so it is escalate-only. A `user_edit` value is never touched (not lowered, and not raised either). The dialogue uses the same function (`DialogueForm.severity`) for the planner's "severity at least High" and the mode classifier's "severity Critical -> URGENT".
- `ModeClassifier` treats "need help", "very urgent", "seriously bleeding", "bleeding badly" as URGENT cues, with the negation guard (`negatable` list in `mode_keywords.json`; the other URGENT keywords stay un-negated on purpose: a missed emergency is worse than a false one).
- Changed signature: `IncidentPolicies.apply` now REQUIRES the cues (no silent "no policy" default). `TextMatch` moved to `domain/service` (shared by classifier, validator, answer cues, severity policy).
- `injury_outcome` is only derived when `injury` is known (true: Minor at confidence 0.5 so Review flags it; false: No injury). The old code replaced a model severity with the table value even when that lowered it; that is gone.
- Ambiguities / limits: URGENT + any injury is Critical even for a scratch (the rule as specified); the outcome stays "Minor injury" at 0.5 for "seriously bleeding" (only severity is escalated; the user fixes the outcome on Review); non-English cues are not added without native review.

## ADR-042 STRICT cap of 3 questions per report, safety check included
- Decision: `DialogueConfig.maxQuestionsTotal = 3`. Planner fields plus the URGENT safety check count, read-back/closing/opening/defer do not; per-mode caps (PLAIN 2, CARE 3, URGENT 2) apply when lower, so URGENT = safety check + 2 fields. `FollowUpPlanner.capReached(mode, asked, otherQuestions, maxTotal)`; `DialogueManager.capReached()` / `questionsUsed()`, a debug `assert` before every question, `ensureSafetyCheck` refuses to ask once the total is used up. Fields still wanted are `missing_flagged`.
- Consequence (hard stop, ambiguous by nature): a CARE report that used its 3 questions and only then reveals an emergency (for example in the read-back reply) gets NO safety check. The emergency flag is raised immediately when the mode reaches URGENT (also when the question is skipped; log outcome `skipped_question_cap`), so the Review banner and the High/Critical floor still apply. The alternative (reserving a slot for the safety check in every mode) was not chosen because it would cut CARE to 2 questions.
- Spec v2 section 12 scenarios needed no change: none asks more than 3 in total (s05: safety check + injury + immediate actions = 3). The eval cases (`cases.json`) list at most 2 asked fields; nothing needed adjusting. The scenario test invariant (scenario 14) now also asserts the total.
- `emergency` is now raised in `reclassify()` the moment the mode becomes URGENT, not only when the safety check is spoken (D: an unanswered URGENT report still shows the banner and High/Critical on Review).

## ADR-043 Spoken form read-out on Review and confirmation on Success (offline Android TTS)
- Decision: when the form is filled (`FormFilled` -> `Reviewing`) the app speaks ONE short line built by the pure `dialogue/FormReadout.review` (type, place, time phrase, severity, injury yes/no if known, up to 3 missing or flagged fields, the "Emergency contact numbers are shown after you submit." sentence for High/Critical; at most 45 words, shortened in a fixed order to fit). After submit `FormReadout.submitted` speaks "Report submitted. Reference I N C, 2026, 6 6 5 9 4 8." (+ "You can call the emergency contacts on screen." when severe). Nothing says or implies that anything was notified, alerted or dispatched (`FormReadoutTest` checks a forbidden list and `blocked_phrases.json`).
- Once per Review entry: the text is chosen by `flow/ReadoutPolicy.textFor(event, newState)` from the EVENT (`FormFilled`, `SubmitSucceeded`), not from the state, because `Reviewing` is re-entered on every edit and when returning from a submit error.
- The speaker button (`ReviewScreen`) stops the speech or replays the CURRENT form (works even when "Read results aloud" is off). Speech stops when the flow leaves Review (`enter` stops TTS for every state except Reviewing/Submitted), when a field is edited, and before ANY recorder start (`startRecorder`): mic and TTS never overlap. The read-out is skipped while the microphone records.
- This replaces the old closing line: `DialogueManager.close()` is no longer called by the app (the method stays); "Report submitted. Reference ..." is spoken by the ViewModel. The read-outs are therefore the one place outside `DialogueManager` that speaks; they speak only fixed, rule-built text, never model output.
- `DevSettings.readResultsAloud` (default on, persisted) switches the automatic read-outs off.
- `AndroidTextToSpeechService`: default engine, English locale with fallbacks, an installed voice that needs no network (`TtsVoicePicker`: device country, then US, GB, any English; network-only engines are "unavailable" and show the banner), `KEY_FEATURE_NETWORK_SYNTHESIS=false` in the speak params, rate `TtsConfig.SPEECH_RATE` = 1.0, engine and voice logged once (tag `TtsService`). Engines that list no voices are used with their default voice.
- Known: the QIDK ships no TTS engine, so the banner "Voice read-out isn't available" is expected there until one is installed (`DEVICE_SETUP.md`).

## ADR-044 Production model: Gemma-4-E2B-it through llama.cpp JNI (not llama-server); Qwen + Whisper stay selectable
- Context: the user tested Gemma-4-E2B-it (q4_0 GGUF + mmproj) with the Snapdragon build of llama.cpp b11552 (`idk-test/MODELS_USAGE.md` 5b/5c): LLM on the Hexagon NPU (`--device HTP0`), mmproj (audio/image encoder) on the Adreno GPU (`-mmdev GPUOpenCL`), native audio + image + text input, ASR 0.9-1.9 s per clip, photos <= 512 px.
  The guide's easy integration is `llama-server` on localhost, but loopback sockets need the `INTERNET` permission and this app must be fully offline with NO `INTERNET` permission (the manifest has none).
- Decision: call the C API of the prebuilt `libllama.so` + `libmtmd.so` (+ `libggml*.so`) from `llama_jni.cpp` (`ai/llama/LlamaNative`, `LlamaGemmaEngine`). Libraries are pulled read-only from the board by `tools/pull_llama_libs.sh` into the git-ignored `jniLibs`; headers
  are vendored in `app/src/main/cpp/third_party/llama/include`. The server flags map one-to-one to API parameters (`MODEL_INTEGRATION.md`). Gemma becomes another selectable Real AI backend (`DevSettings.aiBackend`, dev menu, persisted); Qwen3-4B + whisper.cpp stay as the alternative.
  Models stay in the app's internal `files/gemma/` (the external path is unreadable for adb-created files on this board, `DEVICE_SETUP.md` section 3), pushed with `run-as` like Qwen.
- Consequences: ~85 MB more APK (debug); the build depends on the exact b11552 headers/libs (an update means re-pulling and a recompile; the structs `llama_*_params` / `mtmd_context_params` are passed by value, so headers and libs must match). Not run on the device yet. Risks that cannot be checked on the host:
  the `libggml.so` -> `libggml-opencl.so` -> `libOpenCL.so` load chain inside the app's linker namespace (declared with `uses-native-library`), FastRPC finding `libggml-htp-v79.so` via `ADSP_LIBRARY_PATH` from an unprivileged app, and the NPU being shared with another app (latency jitter, `MODELS_USAGE.md` section 1).
- Soname fix found while integrating: the vendored whisper.cpp built its own shared ggml (same sonames as llama.cpp's libs, so one of them could not be loaded). Whisper's ggml is now static inside `libwhisper_jni.so` (`BUILD_SHARED_LIBS` forced OFF in the CMake cache); `tools/check_apk_libs.py` verifies sonames and DT_NEEDED of every library of the APK on the host.

## ADR-045 Chat-format-neutral prompts; `LlmEngine.generate(ChatPrompt, maxTokens)`; abortable generation
- Context: Qwen needs ChatML (`<|im_start|>`), Gemma 4 needs `<|turn>` turns (and media markers), and the Gemma template is jinja that `llama_chat_apply_template` does not render. The old `LlmEngine.generate(prompt: String)` took pre-rendered ChatML and had no way to carry audio/images, a token budget, or a stop signal.
- Decision (clean cutover, no shim): `Prompts` builds `ChatPrompt(messages, assistantPrefix)` (roles system/user/assistant, media parts on user turns); each engine renders it in its model's format (`ChatFormat.CHATML` in `GenieLlmEngine`, `GEMMA4` in `LlamaGemmaEngine`). `ChatPrompt.render(CHATML)` is byte-identical to the previous Qwen text (pinned by `ChatFormatTest`).
  The Gemma format is implemented in Kotlin (not in C++) from the template read out of the GGUF (`tools/gguf_meta.py`) and verified against jinja2 renderings of the real template (`tools/gemma_render_reference.py`, golden JSON). `LlmEngine.generate(prompt, maxTokens)`; `LlmResult` gains `promptTokens`, `generatedTokens`, `stopReason`. `GenieIncidentExtractionService` is now
  `LlmIncidentExtractionService` (it never was Genie-specific), tests and docs updated. Cancelling the caller (timeout) aborts a Gemma generation between tokens (this fixes ADR-036 for the Gemma backend; Genie is unchanged and ignores `maxTokens`).
- Thinking is off for both: ChatML closes it with an empty think block when there is no forced prefix, Gemma uses the template's `enable_thinking = false` rendering and the output cleanup removes an empty `<|channel>thought` block.

## ADR-046 One resident backend; photos described before extraction; one-call narration is optional
- Decision: `AppContainer` builds a `QwenStack` or a `GemmaStack` on demand and releases the other when the selection changes (NPU/RAM are shared; both loaded at once is not needed). `SttChoice.GEMMA_AUDIO` is only valid with the Gemma backend and is the default speech-to-text there (no Whisper needed).
  Camera path with Gemma: photos stay attached to the report and are ALSO analysed (downscaled to <= 512 px, EXIF-rotated; one short sentence + tags) by `DialogueManager(imageAnalysis = ...)` before the first extraction; the descriptions travel in `ExtractionRequest.imageHints` (the existing plumbing, which was always passed empty) and appear in the extraction prompt as `PHOTO:` lines. A photo that cannot be analysed is skipped.
- Optional (off): one-call narration, audio (+ photos) -> transcript + 7-key JSON in one call (`GemmaNarrationExtractor`, `PrecomputedFirstPass`), using the guide's prompt shape with our keys. Kept behind a developer-menu switch because it replaces the dedicated transcription prompt and the dedicated extraction prompt by one combined prompt whose quality is not measured yet.
- Not done on purpose: no CPU fallback when the NPU/GPU device is missing (it would hide a broken setup; `LlamaGemmaConfig` can name `none`); no Gemma token counts in `count_tokens.py` (the GGUF vocabulary is not a `tokenizer.json`).

## ADR-047 The report is always English; Hindi input is read by cues, the LLM and a script safety net
- Context: on the board a Hindi narration produced an English description but a Devanagari location, `input.language = "en"`, a Devanagari read-back, and an unverified injury answer ("नहीं कोई एंजॉय नहीं है", ASR mishearing "injured").
- Decision: (1) prompts require English values with Latin transliteration of place names and acronyms (Hindi few-shots, budget still far under the caps); (2) deterministic safety net: a model value with non-Latin script is never stored (field empty, `unverified`, `raw_answer` = original, never overwriting a good value), and no spoken line (read-back, `FormReadout`, rephrased question) may contain such text because the English TTS voice cannot read it; (3) `LanguageDetect` (script only) sets `input.language` and the log; no schema change, the transcript is untouched; (4) Hindi cue lists (Devanagari + romanized, `needs_native_review`) for yes/no/unsure/skip, URGENT/CARE and severity escalation; the negation guard also looks after the cue because Hindi puts the negation behind the word ("चोट नहीं"); (5) a non-English answer the cues cannot classify (injury, injured person, injury type) gets ONE translation call whose English text goes through the English parsers, then `unverified` (ask-once is not violated; location/time/actions/people/description already use the update prompt).
- Consequences: an empty script field is asked once in English (the planner treats it as empty); a Hindi-only user answering that English question gets the same treatment. The questions and the spoken voice stay English: Hindi question banks must be human-translated (the spec forbids machine-translating the CARE lines) and a Hindi TTS voice (the board has eSpeak with 141 voices incl. Hindi) is a separate task. Latin-script Hinglish is detected as `en` and relies on the model. Word lists are a starting point and need native review; `needs_native_review` entries are tested for mechanics only.

## ADR-048 Absolute cap of 2 questions, relevance-driven planner, statement read-back (supersedes ADR-042 and spec v2 5.1 / 4.2)
- Context: a real URGENT run asked 4 things (safety check, injury, "What are the immediate safety actions for the fire?", and the read-back's "Anything you'd like to change?"). The user wants at most 2 questions and only relevant ones.
- Decision 1 (cap): `DialogueConfig.maxQuestionsTotal = 2` (was 3), one constant, absolute, every mode. Every spoken prompt that expects an answer counts: field questions and the URGENT safety check, and (formerly) the read-back question. URGENT = safety check + one field. Per-mode caps stay only where lower (PLAIN 2). Debug assertion + hard stop in `DialogueManager`.
- Decision 2 (read-back): the read-back becomes a statement ("Logged: ... Please review and tap submit."): no `?`, no listening, no correction merge; the user corrects on Review. This deliberately overrides spec v2 section 5.1. `DialogueOutcome.Review` lost `readbackAnswered`; `ANSWER_HINT_READBACK`, the eval `readback` answer key and the correction path are gone. The automatic Form read-out (ADR-043) is skipped right after a dialogue read-back to avoid saying the same thing twice.
- Decision 3 (relevance): `FollowUpPlanner` order is now location (missing or vague) > injury (unknown and plausible) > description (thin) > time (past cue) > immediate actions (High/Critical, none mentioned) > people (personal incidents) > injured person > injury type; the URGENT safety check precedes them. Injury plausibility: incident type in `relevance_cues.json` (Fire, Electrical Hazard, Gas Leak, Accident, Injury, Security, Harassment, Assault, Slip / Fall, Medical), OR a people / fall cue in anything the user said, OR URGENT; pure facility / property issues skip it. An ongoing hazard no longer triggers the immediate-actions question; severity High/Critical does. A `relevance` object in the JSONL (field -> ask/skip reason) audits every decision.
- Changed expectations (each was a spec v2 section 12 / eval expectation): s02 water on the floor: asks location then the thin description (was injury, location). s08 AC leak: the scenario now uses an accident ("a ceiling panel dropped") because a facility issue no longer asks the injury question. s09 "Something is broken": location then description (was injury, location), the past-cue time is flagged. s10 door handle: "Someone pulled the door handle..." (a people cue) asks injury then description in CARE wording (the old second question, location, is given in the narration). s12 / s12b: no listening after the read-back; the correction scenario is gone. s13: new read-back text. s05 URGENT smoke: safety check + injury only (was + immediate actions). s03, s04, s06, s07, s11 unchanged. Eval cases: E02 (location, description), E08 (nothing asked), E09 (location, description), E10 (location, description; the description answer escalates to CARE), E12 (read-back correction removed), E14 (description only), E16 (location, description), E18 (location, injury), E20 (description only), E22 and E23 (nothing asked). Mock scenario INJURY_FOLLOW_UPS now narrates "Someone slipped on the wet floor ..." (a people cue) and asks injury then immediate actions.
- Consequences / ambiguity: a facility-issue report with an unnoticed injury is no longer asked about it (the user can set the injury on Review; a later people cue brings the question back). A CARE or PLAIN report that spent both questions gets no safety check if it escalates later; only the emergency flag, banner and severity floors apply. "Vague location" is a word list (`vague_locations`), not a geocoder.

## ADR-049 User Settings screen; follow-ups can be switched off; UserSettings is separate from DevSettings
- Decision: a gear on the idle Home opens a user-facing Settings screen (the hidden developer menu stays for AI backend, STT, eval and mock controls). The user preferences live in `domain/config/UserSettings` (+ `UserSettingsStore`, SharedPreferences `user_settings`, pure defaults, unit-tested): Follow-up questions (default on), Hands-free answers (default on), Read results aloud (default on), Speech rate (Slow 0.8 / Normal 1.0 / Fast 1.25). `readResultsAloud` MOVED from `DevSettings` (one source of truth; an earlier developer-menu value is used as the default so nobody loses their choice); the dev menu points to Settings instead of repeating it.
- Follow-ups off is enforced in the dialogue, not the UI: `DialogueConfig.followUpsEnabled = false` means no question at all (planner loop, opening, safety check skipped), fields it would have asked are `missing_flagged`, and the emergency flag / severity floors for URGENT remain (safety must not depend on a convenience switch). The greeting and the read-back statement are still spoken. Hands-free off adds the `WAITING_FOR_ANSWER_TAP` state (a tap within 10 s starts the recording; otherwise `no_response`).
- Speech rate: `TextToSpeechService.setSpeechRate` (default no-op, so other engines can ignore it); `AndroidTextToSpeechService` applies it before every utterance; the ViewModel pushes it at start and on change. A Piper TTS service can implement it when it lands.
- "Delete all past reports": `ReportRepository.deleteAll()` (JSON files only), confirm dialog, history refreshed. The Settings screen is a Home sub-route (like Past reports), not a `FlowState`, and the gear is hidden unless the flow is idle, so a recording or dialogue is never interrupted.
- Ambiguity: "Active AI backend" in About shows the developer-menu choice (Mock AI or the Real AI backend) and the model load state; it is read-only. Emergency contacts are the dummy list from `EmergencyContacts.DUMMY` (replace with the organisation's directory).

## ADR-050 Injury is optional and never asked (supersedes the injury rows of ADR-048)
- Decision: `injury` / `injury_outcome` are removed from the mandatory fields (`IncidentConfig`, so `validation.mandatory_fields` = description, location, date_time), from the ask order, from the planner (`DialogueFields.PRIORITY`, `DialogueFields.INJURY`), from the question banks and from the read-back "still missing" list. They are filled when the narration (or an answer) says so, and the user sets them on Review (`injury_outcome` moved from the Required to the Situational group). `injured_person` / `injury_type` are asked only when the narration already says `injury == true` and slots remain (cap 2). The question budget goes to location, description (thin), time (past cue), immediate actions (High/Critical), people involved. URGENT = safety check + at most one of these.
- Why: device sessions showed the injury question spent a scarce slot (cap 2) on something people volunteer when it matters; making it mandatory flagged complete facility reports as incomplete.
- Removed as dead: `injury_plausible_types` / `people_cues` (`relevance_cues.json`), `DialogueForm.injuryPlausible`, `PlannerState.injuryPlausible`, `AnswerCues.yesNo` and the Hindi yes/no word lists, `ModelOutputMapper.parseYesNo`, the INJURY branches of the mapper, the mock extraction and `RealAi.translateIfNeeded`. Eval cases E06, E17, E18, E21, E26 changed. Mock scenario `INJURY_FOLLOW_UPS` now states "hurt his ankle" so it still shows the CARE follow-ups.
- Ambiguity: a report where nobody mentions an injury has `injury` empty (not "No injury"); the submitted report therefore does not say "nobody was hurt" unless the user said it.

## ADR-051 Language-agnostic speech: the model names the language; no per-language code in the STT path
- Decision: the Gemma STT instruction names no language. It asks for the spoken language, a verbatim transcript in that language's own native script (never converted to Devanagari/Hindi unless the speaker really speaks Hindi, no translation, code-mixed words kept as spoken) and a fixed reply `Language: <English name>` / `Transcript: <text>`. `GemmaText.parseTranscriptReply` parses it (fallbacks: no `Language:` line = whole reply is the transcript, language unknown). The language name is stored as the model reported it, lowercased (`en` for English; no ISO table) in `Transcript.language` and `input.language`; `DATA_SCHEMA.md` says it is free-form. The one-call narration returns `"language"` as the first JSON key (prefix `{"language":`).
- Why: device reports for Tamil, Assamese and Odia speech showed `language: hi` and Devanagari transcripts ("केआरबी ना टंकी ரொம்ப ओवरफ्लो है"): the old prompt ("Transcribe this audio exactly...") named no language so Gemma defaulted to Devanagari, and `LanguageDetect` turned every Devanagari transcript into `hi`.
- `LanguageDetect` is now generic: `detect` names the dominant non-Latin Unicode script (`Character.UnicodeScript`) or `en` and is only a fallback when the STT gave no language (Whisper, typed, mock); `hasNonLatin` (any letter outside Latin/Common/Inherited) is the single non-Latin test for the mapper, readouts, phrase validator and the one LLM translate call (now any script, not only Hindi/Telugu). The script is not the language (Assamese uses the Bengali script): the model's name wins.
- Extraction/update/translate prompts say generically that the speaker may use any Indian language (Tamil, Assamese, Odia, Bengali, Telugu, Hindi), possibly mixed, and that all values are English. The single Hindi few-shot of the extraction and update prompts was removed (extraction 666 -> 541 Qwen tokens, update 546 -> 502). Risk: removing the few-shot may lower how well Qwen keeps place names in Latin letters; not measured on the device.
- Unverified: how well Gemma 4 E2B hears Tamil, Assamese and Odia audio, whether it obeys the two-line format, and whether it keeps the native script. If a model returns no `Language:` line, the language falls back to the script of the transcript.

## ADR-052 No spoken greeting (overrides spec v2 section 4.6)
- Decision: `DialogueConfig.speakGreeting` (default false). The dialogue speaks nothing before the narration; the first tap on the Home mic starts recording at once (no TTS before it and no wait for a TTS timeout when TTS is unavailable). The question bank keeps `greeting` for the flag. The 10 s "tap the mic" hint after silence is unchanged. `DialogueState.GREETING` is entered only with the flag on.
- Why: the greeting delayed recording and, without a TTS engine, waited for a timeout.

## ADR-053 Gallery replaces the camera; photos are compressed to 512 px when picked (supersedes ADR-007 and ADR-032)
- Decision: the camera capture screen, CameraX (4 dependencies and the `camerax` version), the CAMERA permission and `uses-feature camera`, `FlowState.RecordingVoiceAndCamera`, `FlowEvent.StartVoiceAndCamera` / `PermissionDenied(camera)`, `FlowError.CAMERA_PERMISSION_DENIED` and `InputMode.VOICE_AND_CAMERA` are removed (clean cutover). Home has a GALLERY button that opens the Android system photo picker: `PickMultipleVisualMedia(3)` (images only), or `GetMultipleContents("image/*")` when the photo picker is not available (API < 33 without the backport). No storage / READ_MEDIA permission is needed or declared; cancel changes nothing; a picker or decoding failure shows a banner.
- Why: the camera did not work on the QIDK. The picker needs no permission and works offline.
- Compression on pick (`media/ImageCompressor`, maths in `ai/llama/ImageScaling`, pure and unit-tested): decode with `inSampleSize` (never the full-resolution bitmap), apply the EXIF orientation (rotation and mirror; the stored JPEG needs no EXIF), scale to a long side <= 512 px, JPEG quality 80, write `files/media/<uuid>.jpg`; the original is never copied into app storage. Per the guide (`MODELS_USAGE.md` 5c) a 2746x4032 photo is ~1279 prompt tokens / 23 s and the 512 px version ~276 tokens / 4.2 s on the QIDK, so the model does little work. The same compressed file is what `GemmaImageAnalysis` feeds to Gemma (it re-reads this small file; no second pipeline).
- Flow: photos picked on the idle Home are attached (max 3) and shown as thumbnails above the mic. The mic starts the usual voice dialogue with the photos (`InputMode.VOICE_AND_PHOTO`, wire `voice_and_photo`); "Continue with photos only" starts it with an empty narration (`PHOTO_ONLY`, `photo_only`) when a model can describe photos (Gemma, Mock AI). Gemma: `DialogueManager` describes the photos after the narration (sequential, not in parallel) and the hints ride in the extraction prompt (a new rule line: if the user said nothing, base the report on PHOTO alone). Photo-only AI fields are capped at confidence 0.5 (source `ai`), so Review shows them as low confidence, and the dialogue asks its usual questions (cap 2). Qwen has no vision model: photos are only attached, shown in Settings > About ("Photos: Attached only"), not as an error. Gallery photos never travel with the audio, so the one-call narration is audio only (`AppContainer.transcribeNarration` removed; one-call JSON is not used when photo hints exist).
- Old reports with `input.mode = voice_and_camera` still load (`InputMode.fromWire` reads them as `voice_and_photo`). Mock scenario `CAMERA_WITH_PHOTO` is now `GALLERY_WITH_PHOTO` ("Gallery with photo").
- Ambiguity: orphaned compressed files (a picked photo whose report was never started and the app was killed) are not cleaned up at start.

## ADR-054 "Spoken language" setting: forcing the language fixes unreliable language identification
- Decision: Settings > Voice > "Spoken language" (and a Home chip) with "Auto-detect" (default, prompts unchanged) plus the names in `assets/dialogue/languages.json` (English, Hindi, Tamil, Telugu, Kannada, Malayalam, Marathi, Gujarati, Punjabi, Bengali, Assamese, Odia, Urdu; data, no per-language code). It is stored in `UserSettings.spokenLanguage` (the name string; "auto" = nothing forced). With a language chosen the Gemma speech model gets `Prompts.transcriptionForced` for every transcription (narration, follow-up answers, add-more) and the one-call prompt gets the language line and a pre-filled `language` key. `Transcript.language` / `input.language` = the chosen name lowercased (`en` for English). Only effective with the Gemma speech model: with Whisper (`small.en`, English only) the row is disabled with a note and the chip is hidden.
- Why: device experiments (Gemma 4 E2B, real clips) showed that language identification is the weak link and transcription is good when the language is told. Auto named Assamese, Odia and Telugu correctly but called the Tamil clip Hindi (Devanagari phonetic); every identification prompt variant tried, with or without candidate lists, stayed wrong for Tamil and candidate lists made other clips worse. Forcing the language gave native-script, meaningful text for all four clips (table in `TESTING.md`).
- Ambiguities: the picker lists names as written in the file and an unknown stored name is still used as written; Auto-detect remains the default so nothing changes for users who do not touch the setting; the chip shows "Auto" or the name. The forced prompt was only measured as raw prompt text in the lab, not through the app.

## ADR-055 Offline knowledge graph for post-incident analysis: JSON packs instead of Room, audience flag, demo source
- Decision: the spec (`doc/specs/KNOWLEDGE_GRAPH_SPEC.md`) asks for Room/SQLite. The project has no Room or KSP and stores reports as JSON files, so the graph is kept as versioned JSON PACKS under `files/kg/` (`packs/<version>.json`, `active.txt` pointer), the active graph in memory (`GraphIndex`), the seed in `assets/kg/seed_pack.json` installed on first run. The model stays table-shaped (`KgNode`, `KgEdge`, `KgSource` = `kg_node`, `kg_edge`, `kg_source`) and sits behind `GraphRepository`, so a SQL migration is mechanical. Rollback = switch the pointer to an older version; every import or admin edit is a new version. Audit log = one JSON per incident in `files/kg/audit/` (`kg_recommendation_log`). The spec's `db/` package and Room entities, DAOs and migrations, and the `kg_incident_link` table are not built (the audit record carries the input facts instead).
- Pure Kotlin domain (`com.example.test_app.knowledgegraph`, no android imports) so everything is JVM-tested; the report is mapped to the graph in ONE adapter (`KnowledgeGraphInput`); unknown facts stay unknown and the engine abstains with one question.
- Audience flag: `REPORTER` sees only APPROVED recommendations on APPROVED, unexpired sources; `ADMIN_PREVIEW` also sees draft, reviewed and unverified-source guidance labelled `unverifiedDraft`. Safety, medical, phone-number and expiry filters apply to both.
- Demo source (a deliberate resolution of a conflict): every seed source is `NEEDS_ADMIN_VERIFICATION`, so the spec rule "unverified guidance must not be shown to ordinary users" would leave the reporter UI empty. The seed adds `sop:demo-seed:v1`, status APPROVED, `approvedBy = "demo-seed (not a real SOP)"`, with the visible label "Demo guidance - pending IIIT-H admin approval" in the card and the export. It is NOT a real IIIT-H SOP. The recommendations that need a real procedure (chemical-specific SOP, approved evacuation procedure, LPG isolation procedure, ...) cite the unverified placeholder sources as DRAFT, so only the admin preview shows them. Admins replace the demo source with real approved ones and then expire it (`doc/ADMIN_DASHBOARD_CONTRACT.md`, `assets/kg/README.md`).
- Contacts: none seeded; a number is only ever shown from a VERIFIED, enabled, unexpired contact node; teams are role labels (`NEEDS_ADMIN_VERIFICATION`).
- Not implemented from the spec: Room/SQLite and the `db/` package; `kg_incident_link`; same-utility-branch and repeated-incomplete-corrective-action recurrence (the reports have no utility or corrective-action data); the engine only traverses the spec section 10 paths (HAS_EXIT, CONNECTED_TO, SERVED_BY, BLOCKED_BY, REPLACES, SIMILAR_TO, OBSERVED_AT, ESCALATES_TO, AFFECTS, OCCURRED_AT, INVOLVES edges can be stored but are not used); Incident and Evidence nodes are not stored (the incident is the report). Choices the spec left open: the severity-relevance and evidence-completeness formulas, the consequence cap of 4, the 0.3 evidence abstention threshold, UTC hours for time clusters, `alternateForLocationTypes` / `capacity` properties for the "no invented replacement room" rule.

## ADR-056 Grounded rephrasing: template by default, LLM path wired but off
- Decision: `GroundedRecommendationRenderer` has `TemplateRenderer` (canonical graph text, the default and the only one the app uses) and `LlmGroundedRenderer` (a fresh conversation built by `Prompts.groundedRephrase`, the spec section 14 contract). The reply is validated by `LlmOutputValidator`: exactly one item per supplied id, no unknown, missing or duplicate ids (whole reply rejected), no added number or number word, no new capitalised name, no medical wording, no phone number, no claim that help was sent (that item rejected); on failure the canonical text is shown and `renderMode` says `FALLBACK`. The LLM sees only approved statements and ids, and the graph decides what exists.
- Not wired to a switch or the engine's LlmEngine: the UI never calls it (a dev switch was skipped; `KnowledgeGraphService(renderer = ...)` takes one). Prompt text: `PROMPTS.md`.

## ADR-057 One export document per incident for the future admin dashboard
- Decision: `IncidentAnalysisExport` (schemaVersion 1) is the hand-off contract: incident summary without reporter or injured-person identity (no exact place for residential locations), graph version, recommendations with id, phase, rank, score, source, provenance, canonical and rendered text, owner team and flags, suppressed candidates with reasons, unresolved question, history citations, warnings. It is queued as `files/kg/outbox/<incidentId>.json` (`AnalysisOutbox`; no network code). Packs travel as `GraphPack` JSON through `AdminGraphService`. Full description with examples: `doc/ADMIN_DASHBOARD_CONTRACT.md`.

## ADR-058 In-app admin dashboard, demo PIN gate, Canvas graph (no library)
- Decision: the admin dashboard is built INSIDE the app (a Home icon, PIN gate, seven tabs, `doc/ADMIN_DASHBOARD.md`) instead of waiting for a separate web dashboard. Why: the data (reports, audit logs, outbox, graph packs) is local and offline; an in-app view works on the QIDK without a server, shows the same files the future dashboard will consume (`ADMIN_DASHBOARD_CONTRACT.md`), and makes the graph, the logs and the editor demonstrable now. The web dashboard is still the plan for fleet-wide use; nothing here blocks it.
- PIN: a 4-digit PIN, default `0000`, changeable in Admin > Settings, stored unencrypted in SharedPreferences. It is a DEMO gate to keep casual users out, NOT real security (anyone with the device files can read everything). Five wrong entries pause the dialog for 30 s.
- Graph: drawn with a Compose Canvas and a pure Kotlin Fruchterman-Reingold layout (`ForceLayout`, O(n^2) with cooling, deterministic, JVM-tested) instead of a graph library: no dependency, full control over highlighting, dimming and gestures, and the visible subgraph (cap 400 nodes, local depth 1 to 3) keeps the seed pack (about 460 nodes) smooth. Positions are cached per graph version.
- Highlighting is derived from the audit log's provenance strings (`IncidentPath`); the audit and export schemas were NOT extended (old files keep working). Hops are matched to pack edges by (source, relation, target); the recommendation records, support edges, sources and owner teams of shown actions are added.
- Demo data: about 30 synthetic reports with the id prefix `DEMO-` (the report model has no spare field), analysed in date order; removal deletes exactly those ids and their analysis files. It shows in Past reports too until removed.
- Engine settings (abstention threshold, output limits, ranking weights) are now editable and persisted (`AnalysisSettings`); `KnowledgeGraphService` takes the config as a provider and reads it on every analysis. "Also delete analysis files when reports are deleted" (default on) closes the earlier gap: `deleteAllReports` removes the audit and outbox files of the deleted reports.
- Sharing uses ACTION_SEND with plain text (no FileProvider is configured); text over 200,000 characters is cut with a note in the subject.
- Cut or changed: no real security for the gate; no network sync; the header of the Graph data tab is a fixed column, not a sticky header; sharing sends text, not a file.

## ADR-059 Offline TTS with sherpa-onnx (Piper Amy + Rasa int8), per-text voice routing
- Decision: `SherpaTtsService` speaks with sherpa-onnx (CPU, onnxruntime): Piper `en_US-amy-medium` int8 for English, AI4Bharat VITS Rasa **int8** (`indic_model_int8.onnx`, 40 MB) for Indic scripts. The text picks the voice (`SherpaVoices`); a language without a voice, or a missing or failing model, falls back per text to the Android TTS. A developer-menu setting "TTS engine: System / Sherpa" (null = Sherpa when the files are on the device) and Rasa speaker / emotion fields. Details, measurements: `doc/TTS.md`.
- Why sherpa-onnx and not the planned `qai_hub_models` Piper: it needs no Python-exported QNN graphs or our own espeak-ng phonemizer JNI (the parked `piper-wip` attempt), ships a prebuilt Android JNI with its own espeak-ng, runs both models with the same API, and the only model that covers Indic languages (Rasa) exists as a sherpa export. Cost: CPU instead of NPU (Piper RTF 0.14 is fine; Rasa RTF about 1.0), +12 MB APK (27 MB of libraries, compressed) and 75 MB of model files on the device.
- Rasa details found on the way: the model has no language input, speakers are per language (20 trained, ids 0-19, upstream card), the sample rate is 24 kHz, `emotion_id` needs sherpa-onnx v1.13.8 or later and travels in `GenerationConfig.extra`. Hindi uses the Marathi female speaker (12); Odia and Gujarati have no usable speaker and use the fallback. The sherpa export README's language list (gu, hi, mni, or) does not match the upstream card and the measurements.
- int8 vs fp32: int8 loads and is intelligible but about twice as slow as fp32 on this CPU (RTF 1.0 vs 0.48 with 4 threads); chosen for size as the user asked, fp32 works by swapping the file (`SherpaModelFiles`).
- Rasa text is split and its sentence punctuation stripped by the app (the sherpa trailing-sentence noise note); the JNI callback must be a class, not a lambda (abort otherwise).
- Interface change: `TextToSpeechService.speak(text, language)` (default ignores the language); `TtsSpeechOut` passes the dialogue language.
- Not verified: how it sounds (no one has listened); only machine round trips (faster-whisper for English, Gemma STT for Indic) and amplitude statistics.

## ADR-060 Everything the app speaks follows the language that was heard (language packs, per-segment voices)
- Problem (user report: after a Hindi narration "the TTS still talks in English"): the voice routing of ADR-059 worked, but nothing handed it Hindi text. Causes found: (1) `DialogueManager` always spoke the English bank (`DialogueAssetsLoader.load` read `question_bank.en.json` only; the `hi` / `te` files were English stubs behind `TODO_TRANSLATE`) and passed `DialogueConfig.language` (always `en`) instead of the language of the session; (2) `FormReadout.review` / `submitted` were hard-coded English and `IncidentFlowViewModel.readOut` called `speak(text)` without a language; (3) report values are English by rule (ADR-047), so even a localized sentence needs English words inside it.
- Decision: a **language pack** per language = `question_bank.<code>.json` (questions, fixed lines, and a new `readout` object with the read-out sentences) + `glossary.<code>.json` (incident type, severity, field labels and the numbers 0..59 in that language), listed in `language_packs.json`. `DialogueAssets.pack(language)` resolves a name ("hindi"), a code (`hi`) or a script name (`devanagari`, via `LanguageNames`) to the pack, else the English pack. The dialogue switches to the pack of the language of the first narration (before it, the forced spoken language of the Settings, for the greeting); `FormReadout`, the read-back and the closing line use the same pack; the view model takes the pack from `report.input.language` (the session language; the forced setting when the report has none). No per-language code: a new language is two JSON files and one line in `language_packs.json`. hi and te ship as real translations marked `needs_native_review` (written by an AI, not by a native speaker).
- Free-text values (the location) stay English and are spoken as an **embedded English run**, not omitted: the TTS now splits every utterance into script runs (`SherpaVoices.segments`: Latin vs non-Latin, digits and punctuation stay with the run before them) and routes each run to its own voice (Piper for Latin, Rasa for the language's script) with a pause that depends on the punctuation, played through one `AudioTrack` (the second rate is resampled). This also fixes mixed-script text such as "KRB building" inside Telugu. Omitting the location would have left a Hindi read-out that says "in ..." with nothing; a short English place name in a Hindi sentence is how people speak. Enumerations (type, severity, labels) are translated by the glossary; a missing glossary entry or template key keeps the English word / sentence (which the English voice then speaks).
- The Rasa character frontend cannot be trusted with digit characters, so hours, minutes and id digits are spelled with the glossary's number words (every digit of the reference id one by one).
- Languages without a pack (Tamil, Kannada, Assamese, ...): questions and read-outs are spoken in English with the English voice (the narration is still understood and transcribed; only the app's own lines are English). The LLM rephrase of questions is switched off outside English (its validator and prompts are English-only); the fixed templates are spoken.
- Interface: `SpeechOut.speak(text, language)` now receives the language of the TEXT (the pack code), no longer the configured language; `ReadoutPolicy.textFor(..., pack)`.
- Tests that encoded the old rule ("no script in spoken lines") were rewritten for the new rule; the English behaviour is unchanged (same strings, checked by the existing tests).
- Cut / open: the Review, Success and Settings screens stay English (ADR-013); numbers over 59 are read digit by digit; a native speaker must review both packs; Rasa reads Hindi and Telugu mixed with English runs with a short pause at every voice change.

## ADR-061 Spoken lines were silent: the AudioTrack never started (start threshold)
- Report: follow-up questions appeared as text and were not spoken, in every language (the form read-out seemed fine). Reproduced through the real UI (Mock AI, scenario "Vague location", adb taps): `SherpaTts` logged `spoke ... audio_s=3.16` but `playback finished head=0 written=69632`: the `AudioTrack` was in PLAYING state and `playbackHeadPosition` stayed 0, `TtsSpeechOut.speak` returned after the 2 s deadline of `PcmPlayer.finish`, nothing was audible. Cause: ADR-060's change to a 30 s buffer. A MODE_STREAM track on this device starts consuming only when its buffer is full unless `setStartThresholdInFrames` is set (probe: 30 s / 8 s / 4 s buffers with 2 s of audio never started; 1 s and 2 s buffers started because 2 s of audio filled them). The ADR-059 one-second buffer worked for sentences over one second but would also have stayed silent for a line under one second. The generic path (`TtsSpeechOut`, `DialogueManager.speak`, the `AppContainer` wrapper, `stop()` epochs, the microphone) was not the cause; I checked the stop/epoch ordering and the logs show `epoch == now` for every dialogue line.
- Fix: `PcmBufferPolicy` (pure, JVM-tested): API 31+ = 30 s buffer + 100 ms start threshold; API 30 = 1 s buffer and silence padding at the end of an utterance. `PcmPlayer.finish` logs head versus written and warns when the track never started. `TtsSpeechOut` no longer swallows other exceptions: it logs a warning and shows the existing "voice read-out unavailable" banner for any failure (before, `DialogueManager.speak` ate everything but cancellation) and logs every spoken / cancelled line.
- Evidence after the fix (real UI, same scenario): `playback finished head=68864 written=68864` and `head=41472 written=41472`, `TtsSpeechOut spoke ms=4310` (model load + synthesis + 3.1 s of audio, no deadline wait). Not verified: that it is audible (no one has listened); the System engine has no engine on the board, so only the banner path exists there.

## ADR-062 Speaking is optional with photos: strict photo-only sessions, photo prompts, CPU image encoder option
- Product change (replaces a first idea of a "Speech-impaired mode" switch, dropped by the user): once photos are attached, audio is OPTIONAL. Home shows "Use photos only" as the primary button directly under the thumbnails (before: a secondary button below the mic, out of view); the mic below it still adds a spoken description. Photos only = a `PHOTO_ONLY` session that is strictly silent and mic-free: `DialogueManager.followUps` (setting AND somebody spoke) is false, so no greeting, opening, question, safety check, defer line and no `listen`; the emergency flag and severity floors still apply; only the read-back statement is spoken (read-outs stay governed by "Read results aloud"). Before, a photo-only report still asked "where" and "what happened" with the microphone (ADR-053's note "a photo-only report still asks its usual questions") - that rule is superseded; photo + narration keeps the 2-question cap. The report goes to Review with everything at confidence <= 0.5 (low-confidence highlight) and unsupported fields empty/flagged; after Submit the knowledge-graph analysis runs as usual.
- Vision quality was poor (6 images: 3 without a type, 1 read as "a person using a machine"). Tuning on the board (`PROMPTS.md` G2 / G2b, iteration table): the description now says what went wrong; a separate photo-only extraction prompt states that nothing was said, derives type and severity from visible danger, allows only a kind of room as location, and has four generic few-shot examples (none from the test scenes). Two lessons: example words in the DESCRIPTION instruction are copied by the model (v2 "kitchen", v4 "soot" produced wrong descriptions and were reverted), and nuance such as "soot is not a fire in progress" belongs in the extraction rules, not in the description prompt. A small deterministic filter drops generic "locations" the model still writes ("indoor", "wall").
- Result with the stable setting (below): 5 of 6 types as expected, 6 of 6 severity floors met, 0 questions, ~10 s per photo; the foam on a bathroom floor is read as Spill (expected Water Leak). One-call image-only extraction (`multimodalExtraction` with an image) takes ~4 s instead of ~5 s (GPU) but misread the burnt lab as a water leak, so it is not used.
- **The Gemma image encoder on the Adreno GPU is not reliable on this board**: several runs of the same prompt gave different descriptions per process (a pipe read as "a person's clothing", an AC leak as "no visible incident"), and the first generate of a process often aborted in OpenCL (`clSetKernelArg error -48`; the last eval run died three times in a row). The CPU encoder gave the same, correct descriptions in 4 of 4 runs, 3x slower for photos (7.5 s instead of 2.5 s) and 1.5-2x slower for speech (it also heard "pipe leaking" where the GPU heard "five leaking"). New developer-menu switch "Gemma: image / audio encoder on the CPU" (persisted, `gemmaEncoderOnCpu`, rebuilds the engine); the DEFAULT stays the GPU because speech was measured and shipped with it - recommendation: switch the default to the CPU encoder for the demo.
- Cut: a typed note ("Add a short note") as the narration is not implemented (a new input path through `FlowState`).
