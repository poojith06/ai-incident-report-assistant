# English evaluation set

Repeatable evaluation of the whole pipeline (speech recognition, extraction, field mapping, follow-up dialogue,
rephraser, latency) from the Phase 2 build prompt, section 8. English only. Everything runs on the device with whatever
services the developer menu selects (Mock AI / Real AI). Results land in `doc/EVAL_RESULTS.md`.

```
app/src/main/assets/eval/cases.json   CANONICAL case list (the app reads it from assets, the JVM tests read it from disk)
eval/audio/                           16 kHz mono WAV recordings, one per case + one per spoken answer (empty: .gitkeep only)
eval/README.md                        this file
tools/eval_report.py                  results.json (pulled from the device) -> new section in doc/EVAL_RESULTS.md
```

There is deliberately no `eval/cases.json`: one source of truth, so the app and the tests can never disagree.
Audio exists only for the 4 human recordings (E27..E30: `samples/test-N.wav` copied to `eval/audio/`); no synthetic audio. Everything else runs from typed transcripts.

## What runs

`EvalRunner` (`app/src/main/java/com/example/test_app/eval/`) feeds every case through the REAL `DialogueManager`: greeting,
extraction, mode classification, planner (ask-once), optional rephraser, read-back, report. Speech out is silent (the spoken lines are
recorded and checked). Two input modes:

| Input | How the utterances arrive | WER |
|---|---|---|
| **transcript** ("Run eval set") | the case's `transcript` and `follow_up_answers[].transcript` are typed in; no speech recognition | `n/a (no ASR)` |
| **audio** ("Run eval set from audio") | `platform/FileSpeechIn` reads the WAV files and hands them to the injected `SpeechToTextService` | computed per case |

The audio run needs a real `SpeechToTextService`: the menu offers it when **Whisper CPU** (the whisper.cpp stopgap, not the NPU Whisper) is selected and loaded (`doc/DEVICE_SETUP.md` section 7),
and `EvalRunner` skips every case when it has no STT; nothing is faked. The recordings are read from the external `files/eval/audio/` if it has files, else from the internal `files/eval/audio/`
(`run-as com.example.test_app cp`, because adb-shell-created files in `Android/data` are unreadable by the app on the QIDK). A case whose WAV file is missing is `skipped` (with the missing file names) and the run continues.

## Case format (`cases.json`)

A JSON array of cases (or an object with a `cases` array; other keys such as `_comment` are ignored).

```json
{
  "id": "E02",
  "audio": "audio/E02_water_on_floor.wav",
  "transcript": "There's water all over the floor.",
  "tags": ["spec-2", "short", "plain"],
  "notes": "free text for humans",
  "follow_up_answers": [
    { "field": "injury",   "transcript": "no",   "audio": "audio/E02_a1_injury.wav" },
    { "field": "location", "transcript": "here", "audio": "audio/E02_a2_location.wav" }
  ],
  "expected": {
    "mode": "PLAIN",
    "fields": { "incident_type": "Water Leak", "injury": false },
    "empty_fields": ["injury_type"],
    "asked_fields": ["injury", "location"],
    "asked_includes": [], "asked_excludes": [],
    "outcomes": { "injury": "filled", "location": "filled" },
    "emergency": false,
    "result": "review"
  }
}
```

| Key | Meaning |
|---|---|
| `id` | unique, `E` + two digits. |
| `audio` | the narration recording, relative to the `eval` directory (`audio/E02_<name>.wav`). Null/absent only for the silent case. |
| `transcript` | what the narration says: the typed input of a transcript run AND the reference text for WER. Required unless `expected.result` is `no_narration`. |
| `follow_up_answers` | the user's replies, **keyed by the field the question is about**, not by position: `injury`, `injured_person`, `injury_type`, `location`, `description`, `time`, `immediate_actions`, `people_involved`, or `safety` / `readback` for the safety check and the read-back. A field that is never asked is never answered; a question without an entry gets silence. `"transcript": null` = the user stays silent on purpose. `audio` is the optional recording (named `audio/E02_a<n>_<field>.wav`). `asr_conf` (typed input only, default 1.0) simulates a recogniser confidence, e.g. 0.3 for the "unclear" outcome. One entry per field (ask-once). |
| `expected.mode` | tone mode at the end of the dialogue: `PLAIN`, `CARE`, `URGENT`. |
| `expected.fields` | report field key (`incident_type`, `incident_location`, `date_time`, `immediate_actions_taken`, `injury`, `injury_type`, `persons_involved`, `incident_description`, ...) to the expected value: string, boolean, number or a list of strings (joined by `; `). Scored by the rules below. Severity is derived by app policy from the injury outcome, so list it only when that result is certain. |
| `expected.empty_fields` | report fields that must stay empty (never invent). |
| `expected.asked_fields` | exact ordered list of asked dialogue fields. `asked_includes` / `asked_excludes` are looser checks (all must / none may be asked). Absent = not checked. |
| `expected.outcomes` | per asked field: `filled`, `unknown_reporter_unsure`, `no_response` or `unverified`. |
| `expected.emergency` | the `dialogue.emergency` flag of the report. |
| `expected.result` | `review` (default) or `no_narration` (nobody speaks: the runner simulates the single mic tap, then silence). |

Expectations follow Spec v2 section 12 and the integration-guide checklist as resolved in `doc/DECISIONS.md` (ADR-019), **not what the
app does today**; a mismatch is a finding, not a bug in the case.

## Metrics (what the numbers mean)

All computed by pure Kotlin in `eval/` (no Android imports), unit tested with hand-computed values (`app/src/test/.../eval/`).

- **ASR, WER**: word-level Levenshtein distance (substitution = deletion = insertion = 1) between reference and recognised text after
  normalisation (lower case, `a.m.` -> `am`, apostrophes removed, other punctuation removed, number words zero..twenty/thirty/forty/fifty -> digits:
  "Conference Room two" = "Conference Room 2"). WER = (S + D + I) / reference words; it can exceed 100 %. Per case over all its
  recognised utterances; reported as the mean over cases and as the corpus WER (total errors / total words). Transcript runs report
  `n/a (no ASR)`.
- **Extraction, per-field accuracy**: every expected field value is compared with the report's value. Levels: `EXACT` (equal after trimming),
  `NORMALIZED` (equal after the normalisation above), `RELAXED` (kind-specific, below), `NONE`. Matched = any of the first three.

  | Kind | Fields | Relaxed rule |
  |---|---|---|
  | strict | `incident_type`, `severity_level`, `injury`, `injury_outcome`, `hazard_still_active` | none |
  | location | `incident_location` | all location tokens of the expected value (without articles, prepositions of place such as near/in/at, and "area") occur in the actual value, stemmed: "near the Conference Room 2, second floor" matches "Conference Room 2"; "Conference Room" does not |
  | time | `date_time` | both parsed to a time of day (`10 AM`, `around 10 a.m.`, `ten am`, `10:00`, `15:30`, noon, midnight, ISO `...T10:00:00+05:30`; the date is ignored) and at most 15 minutes apart; unparsable text such as "this morning" falls back to word overlap |
  | overlap | descriptions, actions, people, injury type, equipment, witnesses | at least 60 % of the expected content words (stop words removed, light stemming) occur in the actual value: "Area isolated" matches "Maintenance has already isolated the area" |

- **Mapping, placement rate**: for each expected value: `CORRECT` (right field matches), `MISPLACED` (right field does not match but another field
  does; `incident_description` and `recommended_follow_up_action` are not searched, `true`/`false` never), `WRONG` (right field has something else, value nowhere
  else), `MISSING` (right field empty). Placement rate = CORRECT / (CORRECT + MISPLACED): of the values extracted at all, the share that landed in the right field.
- **Dialogue**: questions per report (mean, max); outcome mix per asked field (from the dialogue's JSONL log: `filled`, `unknown_reporter_unsure`, `no_response`,
  `unverified`); mode accuracy; asked-fields ok; outcomes ok; flow ok (review vs no narration); **ask-once violations** (a field asked more than once; must be 0);
  **invariant violations** (Spec v2 scenario 14 on every case: each question exactly one `?`, at most 20 words, never "why", nothing spoken twice, greeting / safety check /
  read-back / opening / defer once, no "notified" / "help is on the way" / retry phrases, the tone mode never decreases; must be 0).
- **Rephraser**: attempts (a validator verdict exists), validator pass rate, rejection reasons (`too_long`, `blocked_phrase`, `starts_with_why`, `timeout`, ...), rephrase latency.
- **Latency** (ms; median and nearest-rank p90, i.e. the value at rank ceil(0.9 n)): ASR = recognition of one recorded utterance (audio runs only); prefill and decode = per extraction /
  update LLM call as the engine reports them (time to first generated chunk, generation after it); total = narration ASR + first extraction (end of speech to filled form). Updates answered by
  Kotlin rules alone (injury, injured person, injury type) make no LLM call and have no prefill/decode.
- **Prompt size** is not part of the run: `tools/count_tokens.py` and `doc/PROMPTS.md`.

## Recording the audio (WAV, 16 kHz, mono, 16-bit PCM)

The file format is checked on every run (`FileSpeechIn` rejects anything else with a clear error).

- Record with any tool and convert: `ffmpeg -i take.m4a -ar 16000 -ac 1 -sample_fmt s16 audio/E01_water_leak.wav`
  (Audacity: Project Rate 16000, mono, File > Export > WAV, "Signed 16-bit PCM").
- Name every file as in `cases.json` (`audio/E01_water_leak.wav`, answers `audio/E02_a1_injury.wav`). Read the case's `transcript` aloud exactly as written,
  at normal speed, 5 to 30 s per file. Do not record the silent answers (`transcript: null`) or case E11.
- Special conditions: **noise** cases (tag `noise`: E05, E21): play a fan, street or TV sound in the room. **Fast** speech (tag `fast`: E17, E21): no pauses. **Place names**
  (tag `place-names`) are the ones Whisper may mishear (Block B, Conference Room 2, server room, pantry, canteen, lab 3): say them naturally, do not spell them.
  E09's first answer is mumbled ("uh").
- Aim for at least two speakers. One recording per case is used per run: keep each speaker's set in its own folder outside the repo, copy one set into `eval/audio/` for a
  run and say which speaker it was with `python tools/eval_report.py ... --note "speaker B"`.
- Check a file: `ffprobe -hide_banner audio/E01_water_leak.wav` shows `pcm_s16le, 16000 Hz, mono`.

## Adding a case

1. Append an entry to `app/src/main/assets/eval/cases.json` (next free id, tags, expectations as above; keep `expected` honest to the spec, not to today's output).
2. Record its WAV files (above) into `eval/audio/`.
3. `./gradlew.bat testDebugUnitTest --tests "com.example.test_app.eval.*"`: `EvalCasesTest` validates the file (unknown fields, duplicate ids, answer fields, unique well-formed
   audio names, coverage of the spec scenarios and guide rows).

## Running it

1. Install the app and open it once (this creates its storage folder).
2. Push the recordings (only needed for audio runs):
   `adb shell mkdir -p /sdcard/Android/data/com.example.test_app/files/eval` then
   `adb push eval/audio /sdcard/Android/data/com.example.test_app/files/eval/`
   (the app reads `files/eval/audio/*.wav` of its external files directory).
3. Developer menu (long-press the Home title): choose **Real AI** (Mock AI only proves the plumbing: its extraction is scripted), then **Run eval set**. The menu shows the progress and a summary.
   Real AI needs the model loaded first (`doc/DEVICE_SETUP.md`); the first case waits for it.
4. Results: `files/eval_runs/<run id>/results.json` (data) and `results.md` (tables) inside the app's private storage. Pull one (debuggable build):
   `adb exec-out run-as com.example.test_app cat files/eval_runs/<run id>/results.json > results.json`
5. `python tools/eval_report.py results.json` adds the run (date, device, model versions, git commit, one table per metric) above the older runs in `doc/EVAL_RESULTS.md`.
6. Airplane-mode check (guide row 7): turn airplane mode on and run the same set; the numbers must not change (nothing here touches a network). Not automated.

## Synthetic audio (planned, not implemented)

A repeatable offline baseline: generate each WAV from the case's transcript with Android `TextToSpeech.synthesizeToFile(text, params, file, utteranceId)`
(fully offline, English locale), convert to 16 kHz mono PCM16 (the engine writes its own sample rate, often 22.05 or 24 kHz, so a resample step is needed), and write to
the file names in `cases.json` under the app's `files/eval/audio/`. The app's TTS wrapper (`AndroidTextToSpeechService`) has no file-synthesis method, so this needs a new
method plus a resampler (more than the 60 lines allowed for this step); it is listed in `doc/TODO.md`. A "Record eval case" screen (human recordings straight into the right file
name) is also not built yet; record with a phone or PC and push the files.

## Coverage map

| Source | Cases |
|---|---|
| Spec v2 section 12 scenarios 1..11 | E01..E11 (same numbers) |
| Scenario 12 (read-back not repeated) | E01, E08 (no read-back reply); E12 (a read-back correction) |
| Scenario 13 (no "notified") | E03, E06, E13; checked as an invariant on every case |
| Scenario 14 (invariants) | every case |
| Guide row 1 water leak | E01 |
| Guide row 2 "Something is broken in the lab." | E14 (resolved: description is asked, not time) |
| Guide row 3 cafeteria stairs ankle | E15 (resolved: no "when" question) |
| Guide row 4 "Hello, can you hear me?" | E16 (resolved by ADR-019; description is outside the PLAIN cap of 2, see the case notes) |
| Guide row 5 fire in the canteen kitchen | E17 |
| Guide row 6 "I don't know" twice | E18 (resolved: ask-once) |
| Guide row 7 airplane mode | manual: run any case with airplane mode on |
| Short / long descriptions | E02, E08, E09, E14, E18, E20 / E19 |
| Background noise, fast speech | E05, E21 / E17, E21 |
| Place names Whisper may mishear | E01, E03, E05, E07, E12, E15, E17, E19..E26 |


## Photo-only cases (ADR-062)
A case with `"images": ["images/<name>.jpg"]` (paths relative to the eval directory, `files/eval/images/` on the board) is run as a photo-only session: no `transcript`, no `audio`, no `follow_up_answers`; the photos go through the real photo analysis (Gemma vision, or Mock AI) and the extraction, and `asked_fields: []` checks that nothing was asked. `expected.fields` and `empty_fields` work as usual; `expected.severity_min` (`Low` / `Medium` / `High` / `Critical`) adds a result row "severity_level >= <min>" (the severity policy floors can only raise it). The runner skips such a case when the build has no photo analysis or an image is missing. Cases `P01`..`P06` are the six sample photos; device run: `EvalPhotoRunTest`.
