# Follow-up dialogue

Source spec (never edited): `specs/FOLLOWUP_SPEC_v2.md` (what to build).
Deviations and conflict resolutions: `DECISIONS.md` ADR-018 onward. Package: `app/src/main/java/com/example/test_app/dialogue/`.
Tests: `TESTING.md`. Proposed state diagram merged with the Phase 1 states: `STATE_MACHINE.md`.

## What it does

The assistant lets the user narrate at once (no greeting is spoken since ADR-052; `DialogueConfig.speakGreeting` brings back "Hi, describe your incident."), then extracts a report, decides a **tone mode**,
then asks **only for fields that are still empty, each at most once**, reads the result back once and hands over to the
Review screen. It never repeats, never retries ("Sorry, I didn't catch that" does not exist), and never claims an action
that did not happen (no real alert exists in this phase, so the read-back's `{next_step}` is always empty and nothing says
"notified" or "help is on the way").

All decisions are deterministic Kotlin. The LLM may only (1) extract, (2) optionally reword ONE question
(`Rephraser`, validated, template fallback). It never picks the field, the mode, or when to stop, and its free text is
never spoken.

## Pieces

| File | Role |
|---|---|
| `DialogueMode.kt` | `DialogueMode` (PLAIN < CARE < URGENT, caps 2/3/2, rephrase style text); `DialogueFields` (8 dialogue keys, priority order, mapping to report keys, `(injured)` marker). |
| `DialogueConfig.kt` | `useLlmRephrase` (default true), `maxQuestionsTotal` (2, the STRICT per-report cap), `language`, 10 s greeting silence, 8 s answer silence, 1500 ms rephrase timeout, ASR confidence floor 0.5. |
| `DialogueAssets.kt` | Loads and validates all JSON assets; pure Kotlin (a reader function is injected). |
| `QuestionBank.kt` | One question per field x mode + fixed lines. Rejects any `clarify` key, any question without exactly one `?` or over 20 words. `URGENT`/`PLAIN` fall back to the `CARE` text when a field has no entry. `TODO_TRANSLATE:` prefixes are stripped (English until translated). |
| `ModeClassifier.kt` | Keyword rules (word-boundary regex from `mode_keywords.json`) + model signals (`injury == true` -> CARE, severity Critical -> URGENT, CLIP fire/smoke >= 0.5 -> URGENT, CLIP absent in this phase). Negation guard: a negation word within 4 tokens before an injury-related CARE keyword (same clause) cancels it. |
| `AnswerCues.kt` | "I don't know" / "skip" / "pata nahi", "here" / "yahin", past cues, no-change replies, content-word count. |
| `FollowUpPlanner.kt` | Pure Kotlin (no Android imports). `next(state, mode, askedFields)` walks the priority table of spec 4.2. `capReached(mode, asked, otherQuestions, maxTotal)` (per-mode cap or the per-report total), `missing`, `remaining`. |
| `DialogueForm.kt` | Reads the report for the planner (`filled`, thin description, past cue, personal incident) and reconciles `injury` / `injury_outcome`. |
| `Rephraser.kt` | `Rephraser`, `RephraseRequest`, `RephrasePrompt` (spec 8.2 text, transcript trimmed to 60 words), optional `LlmMetricsProvider`. |
| `PhraseValidator.kt` | Spec 8.3 checks 1-5 (the 1500 ms timeout is in the manager). |
| `DialogueLogger.kt` | JSONL per turn: `files/dialogue_logs/<reportId>.jsonl`; `prompt_tokens`, `prefill_ms`, `decode_ms` are null unless the rephraser exposes them. |
| `SpeechIo.kt` | `SpeechIn`, `SpeechOut`, `AsrResult`. |
| `DialogueManager.kt` | The state machine. The ONLY thing that decides what is spoken. |
| `platform/DialogueAssetsLoader.kt` | Android `AssetManager` reader; `AppContainer.dialogueAssets` (lazy); `IncidentApp.onCreate` touches it in debuggable builds so a bad asset fails loudly. |

Assets: `app/src/main/assets/dialogue/` (`question_bank.{en,hi,te}.json`, `mode_keywords.json`, `blocked_phrases.json`,
`field_keywords.json`). `hi`/`te` banks carry `TODO_TRANSLATE:` markers; their keywords in `mode_keywords.json` and
`field_keywords.json` are marked `"needs_native_review": true` and are not tested.

## Flow (`DialogueManager.run`)

1. No greeting (ADR-052: `speakGreeting` is false by default; with the flag on it is spoken once, skipped when `run(narration = ...)` is given a transcript): the first listen starts at once. Silence for 10 s: `uiState.showMicHint = true`,
   state `WAITING_FOR_TAP`. `onMicTap()` then listens again; silence again = `DialogueOutcome.NoNarration`.
2. Describe the attached photos when an `ImageAnalysisService` was given (Gemma backend: up to 3, 512 px, failures skipped), then extract the narration (`IncidentExtractionService.extract`, no previous report; the `ImageHint`s ride in `ExtractionRequest.imageHints`, also in later requests). A failure here is thrown as `ExtractionException`.
3. Classify the mode (rules over all user words so far + model signals). Escalate-only: the manager keeps the highest mode seen.
4. URGENT: safety check "Are you somewhere safe right now?" once; no answer = assume not safe; `emergency = true` (banner
   flag only). Also triggered once if the mode reaches URGENT later (including after the read-back).
5. Opening line only if the planner will ask at least one field (PLAIN: "Got it. Just one quick detail." / "a couple of quick details"; CARE: sympathy line; URGENT: none).
6. Ask loop until the planner returns nothing or a cap is reached: the per-mode cap (PLAIN 2, CARE 3, URGENT 2) or the **STRICT total of
   `DialogueConfig.maxQuestionsTotal` = 2 questions per report, the URGENT safety check included** (see "Question cap" below). Per field: pick template,
   optionally rephrase (validated; template on any failure or timeout), add to `askedFields`, speak, listen once (8 s),
   decide the outcome, merge, re-classify.
7. Fields still wanted when the cap hit are marked `missing_flagged`. URGENT with anything unresolved speaks the `defer` line once.
8. Read-back once: a statement (fixed template; `{next_step}` empty) with no question, and nothing is listened for afterwards (ADR-048).
   Corrections are made on the Review screen.
9. `DialogueOutcome.Review(report)`. After submit the ViewModel speaks the confirmation (`FormReadout.submitted`, ADR-043); the app no longer calls `close()`.

### Answer outcomes (spec 4.5)

| Outcome | Detection | Status |
|---|---|---|
| answered | extraction fills the asked field | `filled` |
| unsure / skip | `AnswerCues.isUnsure` (no extraction call) | `unknown_reporter_unsure` |
| no response | `SpeechIn.listen` returned null | `no_response` |
| unclear | ASR confidence < 0.5 (no extraction call), extraction error, or the field is still empty | `unverified` + `raw_answer` |
| other fields filled instead | same as unclear for this field | `unverified` |

A `no_response`/`unverified` field that a later answer fills becomes `filled` (the default `date_time` is the exception).

### Location "here"

If the answer to `location` is "here" / "right here" / "yahin" / "ikkada" and `gps()` returns a fix, the report stores
`GPS lat, lon`, `location_source = gps`. `DeviceInfo.gps` is still always null, so in the app "here" falls through to
extraction and is stored as spoken text (`location_source = spoken`). Pass a real provider as `DialogueManager(gps = ...)` when GPS lands.

### Extraction contract used by the manager

Answers go through the existing `IncidentExtractionService.extract(ExtractionRequest)`; no interface change:
`previousReport` = the current merged report, `answerTranscript` = the user's words,
`answeredQuestion = ClarificationQuestion(targetFields = [<dialogue field key>], text = <spoken question>)`.
For a read-back correction `targetFields` is empty. The returned JSON is merged field by field: a non-empty returned value replaces the
current one (and becomes `user_voice`); empty returned values never erase what was already known. `MockIncidentExtraction` understands the dialogue keys.
The real (Genie) implementation must do the same and must tag injured people in `persons_involved` with ` (injured)`.

### Tool hint

If a `ToolExecutor` is passed, the manager calls `fillIncidentFormWithHint(report, hint)` after every extraction
(`PlannerHint(missing, nextField, mode, template)`; `ToolResult.toJson()` is the `{ok, missing, next_field, mode, template}` shape of spec 9).
`nextField` is never an asked field and is null once a cap is reached.

## Question cap (STRICT, ADR-048; details in "Relevance and the cap of 2" at the end)

At most `DialogueConfig.maxQuestionsTotal` (2) questions are asked per report in total, in every mode: the planner fields (`askedFields`) plus the
URGENT safety check count; the opening line, defer line, read-back and closing do not. The per-mode caps (PLAIN 2, CARE 3, URGENT 2) still apply
when they are lower; with a total of 2, URGENT = safety check + at most 1 field, PLAIN and CARE at most 2 fields. Enforced in `DialogueManager`: `capReached()` stops the ask
loop, `ensureSafetyCheck()` does not ask once the total is used up, and a debug `assert` fires before any question that would exceed it.
Fields still wanted after the cap are `missing_flagged`. If the mode reaches URGENT after the total is used up (for example a CARE report whose
second answer mentions smoke), the safety check is **not** asked (hard stop; logged as `skipped_question_cap`), but the emergency flag is raised and
the Review banner and severity floor apply anyway.

## Severity policy (deterministic, ADR-041)

The LLM severity is only a hint. `domain/service/SeverityPolicy.decide` computes `final = max(hint, floors)`; `IncidentPolicies.apply` runs it after every
pass (dialogue report build, finish, Review edit, add-more) and the dialogue uses the same function for the planner and the mode classifier
(`DialogueForm.severity`). Cues come from `assets/dialogue/severity_cues.json` (`DialogueAssets.severityCues`). A value the user edited on Review is never touched.

| Floor | Severity |
|---|---|
| injury outcome / hazard table (`suggestSeverity`): No injury Low (Medium with active hazard), Minor Medium (High), Serious High (Critical), Fatal Critical; an injury answer without outcome counts as Minor, "no" as none | Low .. Critical |
| mode URGENT (or `dialogue.emergency`) | at least **High** |
| mode URGENT and (an un-negated escalation cue in anything the user said, or an injury: `injury == true` or an injured outcome) | **Critical** |
| incident type Fire / Gas Leak / Electrical Hazard (`high_incident_types`) | at least **High** |

Escalation cues: urgent, very urgent, emergency, need help, please help, help me, hurry, come fast, come quickly, trapped, not breathing, unconscious,
heavy bleeding, serious bleeding, seriously bleeding, a lot of bleeding, bleeding a lot, bleeding badly. They use the negation guard of `mode_keywords.json`
(a negation word within 4 tokens before the cue in the same clause: "it's not urgent", "I don't need help"). Outside URGENT mode they do nothing.
The hint is the current value of `severity_level`, so a later pass can never lower it (escalate-only like the mode). `ModeClassifier` treats "need help",
"very urgent", "seriously bleeding" and "bleeding badly" as URGENT cues (listed under `negatable` in `mode_keywords.json`, same negation guard).
`injury_outcome` is never invented: it is only derived when `injury` is known (true: "Minor injury" at confidence 0.5; false: "No injury").

## Wiring in the app (done)

`IncidentFlowViewModel` runs the dialogue for BOTH Mock AI and Real AI (`STATE_MACHINE.md` has the merged diagram):

- State `FlowState.Dialogue(session)`. `enter` -> `runDialogue`: builds one `DialogueManager` per report with `platform/MicSpeechIn`
  (`SpeechIn` + `FieldAwareSpeechIn`: mic + energy VAD, 8 s silence = null, then the `SpeechToTextService`), `platform/TtsSpeechOut`
  (`SpeechOut` over the Android TTS; a TTS failure only shows the banner), `AppContainer.extraction` (mock or Genie by the dev-menu switch),
  `AppContainer.rephraser()` (null in Mock AI or when the rephrase switch is off), `LocalToolExecutor`, `DialogueLogger(filesDir/dialogue_logs)`,
  `gps = { DeviceContext.snapshot().gps }` (null today), and the default `IncidentConfig`.
- Gallery photos (ADR-053) ride in `run(media = ...)`; photo only passes an empty narration (`AsrResult("")`) so the first extraction runs on the photo descriptions alone;
  voice path: `run()` listens at once (no greeting). Retry after an error uses `run(greet = false)`.
- UI: `manager.uiState` + `TtsSpeechOut.speaking` + `MicSpeechIn.phase` are combined into `DialogueView` (`flow/IncidentFlowViewModel.kt`);
  `HomeScreen` shows greeting / listening / first extraction / the 10 s hint (`showMicHint`), `DialogueScreen` shows the follow-ups and the
  read-back. The mic button is `onMicTapped()`: "ready" during the hint (`manager.onMicTap()`), "skip speech" while speaking, "done" while listening.
- Result: `DialogueOutcome.Review(report)` -> device info + `IncidentPolicies` + `ValidationService` -> `DialogueFinished` -> `FillingForm` (no speech)
  -> `Reviewing`. "Skip and fill manually" uses `manager.currentReportOrNull()`. After submit the VM calls `manager.close()` (closing line, once).
- `SpeechIn.listen` errors: for the first narration they surface as an error screen (STT / mic) with a retry that does not greet again;
  for answers (`listenSafe`) they count as "no response" (ask-once, never retried).
- `FieldAwareSpeechIn.expectAnswerFor(field)` tells the speech input which dialogue field the next utterance answers (`safety`, `readback` for
  the control lines, null for the narration); real recognisers ignore it, `MockSpeechToText` uses it to script answers.
- "Add more by voice" from Review keeps the small Phase 1 loop (`Transcribing` -> `Extracting` -> `Validating`) with an empty target list.

## Things NOT to do

See spec section 14. In code terms: never add a second `speak` outside `DialogueManager`; never loop on `listen`; never add a
retry/clarify string to the bank (the loader rejects it); never put a model-generated string into a fixed line.

## Read-outs after the dialogue (ADR-043)
`DialogueManager` is still the only thing that decides what is spoken DURING the dialogue. After it, the ViewModel speaks two fixed lines built by the pure `FormReadout`
(`review(report)` when Review shows the filled form, `submitted(report)` on Success) through the offline Android TTS. The app no longer calls `DialogueManager.close()`.
Rules: at most 45 words, plain wording, up to 3 missing or flagged fields named, severe reports mention that the emergency contact numbers are shown after submit, and nothing
claims a notification, alert or help (the blocked-phrase list is reused in `FormReadoutTest`). `ReadoutPolicy` makes it once per Review entry; the dev setting "Read results aloud" turns it off.

## Hindi narrations and answers (ADR-047)
- **Report in English:** prompts tell the model to write every value in English (a Hindi few-shot in the extraction and update prompts); `ModelOutputMapper` drops any value that still has non-Latin script (field `unverified`, `raw_answer` = the original) and `LanguageDetect` sets `input.language` (`en`/`hi`/`te`, by script) and the `lang` field of the JSONL log. The transcript stays as spoken. The read-back and `FormReadout` never speak non-Latin text (generic "the place you mentioned").
- **Hindi cues** (Devanagari + romanized, all `"needs_native_review": true`): yes / no / unsure / skip / here / past / no-change words in `field_keywords.json` (`yes_words`, `no_words`, `no_leaders`, `unsure_phrases`, ...), URGENT and CARE cues in `mode_keywords.json` (आग, धुआं, गैस, बिजली का झटका, बेहोश, खून बहुत, मदद चाहिए, जल्दी / चोट, घायल, गिर गया, दर्द, परेशान), Hindi escalation cues in `severity_cues.json` (`escalation_cues_other`). The negation guard now also looks AFTER the cue for hi/te (`after_words`: "चोट नहीं", "chot nahi") besides the words in front (कोई, नहीं, बिना). `TextMatch` treats combining marks as word characters so Devanagari phrases match as whole words.
- **Injury answers:** `AnswerCues.yesNo` maps a Hindi answer ("नहीं कोई एंजॉय नहीं है" -> no, "हाँ ..." -> yes); `DialogueManager` hands the extractor a plain "yes"/"no" for it (the clarification history keeps the real words). "पता नहीं", "छोड़ो", "रहने दो" are unsure/skip (checked first, so "पता नहीं" is not a "no").
- **LLM fallback (once):** a NON-English answer to `injury`, `injured_person` or `injury_type` that the cues could not classify is translated by one model call (`Prompts.translateAnswer`) and read by the English parsers; an unreadable result stays `unverified`. Other fields (location, time, actions, people, description) already use the update prompt, whose output is English. This is not a re-ask: ask-once holds.
- **Questions are Hindi** since ADR-060 (language packs, see "Languages" below); before that the hi/te banks were English stubs.

## Relevance and the cap of 2 (ADR-048, supersedes ADR-042)
- **Absolute cap:** at most `DialogueConfig.maxQuestionsTotal = 2` questions per report, in every mode. A question is every spoken prompt that expects an answer: each planner field question and the URGENT safety check. URGENT = safety check + at most 1 field question. Greeting, opening, defer and closing are statements and do not count.
- **The read-back is a statement** ("Logged: Fire, at the lab, 10 AM. Please review and tap submit."): no `?`, no "Anything to change?", and the dialogue does NOT listen after it. The user corrects on the Review screen. `QuestionBank.parse` rejects a read-back line with a `?`. This overrides spec v2 section 5.1 ("the read-back ends with one question"). Because the dialogue already said "Please review and tap submit", the automatic Form read-out of ADR-043 is skipped after a dialogue (`ReadoutPolicy`'s `dialogueReadBack`); the speaker button still reads the form, and the add-more / skip paths keep it.
- **Planner policy** (`FollowUpPlanner`, deterministic; inputs come from `DialogueForm`):

| Priority | Field | Asked only when |
|---|---|---|
| 0 | safety check (URGENT only, `DialogueManager`) | the mode is URGENT and the total is not used up |
| 1 | `location` | missing, or too vague to route (only vague words: "near", "here", "the building", `relevance_cues.json`) |
| 2 | `injury` | unknown AND plausible: type Fire, Electrical Hazard, Gas Leak, Accident, Injury, Security/Harassment, Slip / Fall, ... or a people / fall cue in anything the user said (someone, worker, fell, hurt, ...) or URGENT. NOT for pure facility / property issues (Water Leak, Equipment Failure, a broken AC or printer, power cut, snake sighting, spill without a person) |
| 3 | `description` | the first description is thin |
| 4 | `time` | a past cue and no time (otherwise the time defaults to now) |
| 5 | `immediate_actions` | severity High/Critical (policy) and no action mentioned; an ongoing hazard alone no longer triggers it |
| 6 | `people_involved` | personal incidents (harassment, threat, assault) |
| 7 | `injured_person` | injury is true and nobody injured is named |
| 8 | `injury_type` | injury is true, CARE mode, no type |

- Fields still wanted after the cap are `missing_flagged`. If the mode reaches URGENT after the cap is used up the safety check is not asked (hard stop, logged `skipped_question_cap`), but the emergency flag, banner and severity floors still apply.
- **Audit log:** the JSONL has a `relevance` object (`field -> "ask: <reason>"` / `"skip: <reason>"`) on the CLASSIFY_MODE line (plan before asking), on every ASK_LOOP line (the reason for the asked field) and on the READBACK line (final reasons; a still-wanted field says `skip: question cap reached ...`).
- Tests: `RelevancePolicyTest` (20 transcripts, exact asked lists), `QuestionCapTest`, `FollowUpPlannerTest`.

## Follow-ups off and hands-free answers (user settings, ADR-049)
`DialogueConfig.followUpsEnabled` (user setting "Follow-up questions", default on). OFF: the dialogue extracts the narration (no greeting is spoken any more, ADR-052) and asks NOTHING: no opening line, no safety check (logged `skipped_follow_ups_off`), no field question, and the answer recording never starts (only the narration is listened for). `flagRemaining` marks every field it would have asked as `missing_flagged`, so Review highlights them. URGENT still raises the emergency flag and banner, the severity floors still apply (fire = High, with distress cues = Critical), the defer line (a statement) is spoken once when something is unresolved, and the read-back statement is spoken. (Injury is never asked either way, ADR-050.) `DialogueConfig.handsFreeAnswers` (user setting "Hands-free answers", default on, ignored when follow-ups are off): off = after each question (and the safety check) the dialogue waits up to 10 s (`greetingSilenceSec`) for a mic tap (`WAITING_FOR_ANSWER_TAP`; `onMicTap()`), then listens; no tap = `no_response`; a tap made while the question was still being spoken only skips the speech. Tests: `FollowUpsOffTest` (spec scenarios 1-10 with follow-ups off, hands-free gate).

## Language-agnostic speech and language (ADR-051)
The Gemma STT prompt ([Prompts.TRANSCRIBE_INSTRUCTION]) asks the model to identify the language actually spoken, transcribe verbatim in that language's own native script, never convert to Devanagari/Hindi unless the speaker really speaks Hindi, keep code-mixed words in the script they were spoken in, and answer in two lines `Language: <English name>` / `Transcript: <text>`. `GemmaText.parseTranscriptReply` (pure, tested) reads it: think/channel tokens, code fences and markdown labels removed, chatter before `Language:` ignored, multi-line transcripts joined, label order swapped or both labels on one line accepted, no `Language:` line = the whole reply is the transcript with the language unknown. The language name is stored as the model reported it (lowercased; `en` for English; no ISO table) in `Transcript.language` and `input.language`. The one-call narration prompt returns `"language"` as its first JSON key (the assistant prefix is now `{"language":`). When the STT gives no language (Whisper, typed transcript, mock) `LanguageDetect.detect` names the dominant non-Latin Unicode script (`devanagari`, `tamil`, `oriya`, `bengali`) or `en`. `LanguageDetect.hasNonLatin` (any letter outside Latin/Common/Inherited, via `Character.UnicodeScript`) is the single non-Latin test for `ModelOutputMapper`, `FormReadout`, `PhraseValidator` and the translate fallback: ANY non-Latin answer to `injured_person` / `injury_type` gets exactly one LLM translate call. Extraction/update/translate prompts say generically that the speaker may use any Indian language (Tamil, Assamese, Odia, Bengali, Telugu, Hindi), possibly mixed, and that every value is written in English; the single Hindi few-shot of each prompt was removed. Cue lists in the assets (Hindi, Telugu keywords) are data, not code.

## No greeting (ADR-052)
`DialogueConfig.speakGreeting = false` (default): nothing is spoken before the narration. The first tap on the Home mic starts recording at once (no TTS, no TTS timeout wait when TTS is unavailable); the 10 s "tap the mic" hint is unchanged. The question bank keeps the `greeting` line for the flag. Overrides spec v2 section 4.6.

## Injury is not asked (ADR-050, supersedes the injury row of ADR-048)
`injury` left `DialogueFields.PRIORITY` (and `injury_plausible_types` / `people_cues` left `relevance_cues.json`, the injury rows left the question banks): the planner never asks it and never flags it. Priority now: location (missing or vague), description (thin), time (past cue), immediate_actions (High/Critical), people_involved (personal incident), injured_person and injury_type (only when the narration already says `injury == true`). URGENT = safety check + at most one of these. `injury` / `injury_outcome` are not mandatory, not in `validation.missing_fields`, and not in the read-back "still missing" list; they stay filled when the narration mentions them and editable on Review. Hindi yes/no cues (`AnswerCues.yesNo`, `yes_words` / `no_words` / `no_leaders`) and `ModelOutputMapper.parseYesNo` were removed with the injury question (dead code).

## Spoken language (forced language, ADR-054)
`UserSettings.spokenLanguage` ("auto" by default, else a language NAME from `assets/dialogue/languages.json`; Settings > Voice > "Spoken language" and the Home chip). Only the Gemma speech model uses it; with Whisper (`small.en`, English only) or the mock the row is disabled with a note and the chip is hidden. With a language chosen `GemmaSpeechToText` sends `Prompts.transcriptionForced(language, audio)` for EVERY transcription of the session (the narration, follow-up answers and add-more): `This audio is spoken in {language}. Transcribe it verbatim in {language} using its native script. Do not translate. Output only the transcript.` (English: `Transcribe this audio exactly. Output only the transcript, nothing else.`). The reply is plain text (`GemmaText.cleanTranscript`; no `Language:` / `Transcript:` lines) and `Transcript.language` is the chosen name lowercased (`en` for English), which becomes `input.language`. The one-call narration prompt gets the line `The audio is spoken in {language}; write the transcript verbatim in {language} using its native script.` and its assistant prefix pre-fills the key: `{"language": "Tamil", "transcript":`. Auto-detect keeps every prompt exactly as before (ADR-051). No per-language code: the list is data and any name is told to the model as written.

## Languages: packs, glossary, spoken read-outs (ADR-060)
Everything the app SPEAKS follows the language that was heard. Per language (`hi`, `te` today):
| File (`assets/dialogue/`) | Content |
|---|---|
| `language_packs.json` | The codes that have a pack (`["hi", "te"]`). English is the primary bank and needs no entry. |
| `question_bank.<code>.json` | Greeting, one question per field x mode, the fixed lines (`opening_*`, `safety_check`, `defer`, `readback_*`, `closing_*`, `offer_typing`) with the same rules as English (one `?` per question, at most 20 words, no `?` in the read-back), plus the **`readout` object**: the sentences of `FormReadout` (`ready`, `summary`, `summary_at`, `when_now`, `time_h` / `time_hm` / `time_am` / `time_pm`, `severity`, `hurt`, `not_hurt`, `missing`, `emergency`, `tail`, `tail_flagged`, `submitted`, `submitted_severe`, joiners `sep` / `summary_join`, fallbacks `fallback_incident` / `fallback_place` / `fallback_report` / `time_set_now`). The English bank carries the complete key set with the English values (a test keeps it equal to `ReadoutTemplates.DEFAULTS`). A key a language leaves out is spoken in English. |
| `glossary.<code>.json` | `incident_type` (the ten types of the extraction prompt), `severity` (Low / Medium / High / Critical), `field_labels` (by JSON key), `numbers` ("0".."59"). Missing entry = the English word stays. |
Mechanics: `DialogueAssets.pack(language)` (`LanguageNames`: name, code or script name -> code; unknown or without pack -> English) gives a `LanguagePack(bank, readout, glossary)`. `DialogueManager` starts with the pack of `DialogueConfig.language` (the forced spoken language, for the greeting), switches to the pack of the first narration's language, speaks every line with `speak(text, pack.code)`, and renders the read-back through the pack (incident type through the glossary, time through `time_*` and the number words, the English location value as it is). The LLM rephrase is only used with the English pack. `IncidentFlowViewModel` speaks the Review read-out and the Success confirmation with the pack of `report.input.language` (else the forced setting, else English). Report values stay English (ADR-047): the location is spoken as an English run inside the sentence (the TTS gives every script run its own voice, `TTS.md`); hours, minutes and reference digits are spelled with the glossary's number words.
Languages without a pack (Tamil, Kannada, Assamese, Malayalam, ...): the narration is understood, but questions and read-outs are English, spoken by the English voice.
**Adding a language** (needs a native review before real use): (1) copy `question_bank.en.json` to `question_bank.<code>.json`, set `language`, translate the greeting, questions, lines and the `readout` object (same placeholders `{location}` `{severity}` `{fields}` `{id}` `{summary}` `{h}` `{mm}` `{time}`; one `?` per question; no `?` in `readback_*`); (2) write `glossary.<code>.json` (copy `glossary.hi.json`, translate the words, spell 0..59); (3) add the code to `language_packs.json`; (4) make sure the TTS has a voice for the script and language (`SherpaVoices.DEFAULT_SPEAKERS`, `LanguageNames`); (5) `LanguagePackTest` checks completeness (all keys, placeholders, scripts, glossary coverage). Tests: `LanguagePackTest`, `SherpaVoicesTest`, `HindiTest`.

## Photo-only reports (ADR-062)
Speaking is optional once photos are attached: "Use photos only" on Home starts a `PHOTO_ONLY` session. `DialogueManager.followUps` (= the "Follow-up questions" setting AND somebody spoke) is false for it, so: no greeting (the narration is the empty `AsrResult`), no opening line, no field question, no safety check (the emergency flag and the severity floors still apply), no defer line, no `listen` at all (the microphone is never opened), only the read-back statement is spoken; fields the photos cannot show are `missing_flagged` for Review. Extraction: `GemmaImageAnalysis` describes up to 3 photos (two short sentences each: the setting, what is wrong), then `Prompts.photoOnlyExtraction` turns the `PHOTO:` lines into the seven-key JSON (rules: from the photos alone, severity from the visible danger, location only a kind of room or area, people only when clearly visible, time and actions null, never invent). `ModelOutputMapper` caps every AI value at confidence 0.5 and drops generic locations ("indoor", "wall"). With a recorded narration (with or without photos) the normal flow applies: photos + narration, at most 2 questions. A typed note instead of a narration is not implemented (it needs a new input path through the flow state).
