# Evaluation results

English evaluation set results, one section per run, **newest first** (so progress is visible). Every number here comes from a
`results.json` written by the app; nothing is estimated or typed in by hand.

## How a run gets here

1. In the app: long-press the Home title, open the developer menu, pick Mock AI / Real AI, press **Run eval set**
   (typed transcripts) or, when a real speech-to-text model exists, **Run eval set from audio** (`eval/README.md`).
2. Pull the result: `adb exec-out run-as com.example.test_app cat files/eval_runs/<run id>/results.json > results.json`
   (the run id is shown in the menu; the directory also holds `results.md`).
3. Add it: `python tools/eval_report.py results.json` (date, device, model versions, git commit hash and one table per metric are
   written right below the marker, above the older runs; `--dry-run` only prints).

Metric definitions: `eval/README.md` and `TESTING.md` (Level 4). Spec: build prompt section 8.

## Runs (newest first)

<!-- eval_report:insert -->

## 2026-10-10 - QUALCOMM Sun for arm64 - run 20261010-204252

- Date: 2026-10-10 (run started 2026-10-10T20:42:52.392010Z)
- Device: QUALCOMM Sun for arm64, Android 15
- App version: 1.0; git commit: f0e9ad2 (+ uncommitted changes)
- Input: audio (WAV files through the speech-to-text service)
- AI mode: real; LLM: Qwen3-4B (Genie, Hexagon NPU); ASR model: whisper.cpp ggml-small.en.bin (CPU stopgap, not NPU); rephraser: on
- Cases: 30 (ok 5, error 0, skipped 25)
- Note: AndroidTest EvalAudioRunTest (same AppContainer.runEval as the dev menu), Real AI (Qwen3-4B) + Whisper CPU stopgap (whisper.cpp ggml-small.en, 6 threads, no place prompt); only the 4 human recordings E27-E30 (+E11 silent) have audio, 25 cases skipped; speaker: campus speaker of samples/test-N.wav

**ASR (word error rate)**

| Cases | Utterances | Mean WER | Corpus WER |
|---|---|---|---|
| 4 | 4 | 0.0% | 0.0% |

**Extraction (field accuracy; exact / normalised / relaxed match)**

| Field | Expected | Matched | Exact | Normalised | Relaxed | Accuracy |
|---|---|---|---|---|---|---|
| date_time | 2 | 0 | 0 | 0 | 0 | 0.0% |
| incident_location | 4 | 4 | 2 | 0 | 2 | 100.0% |
| incident_type | 3 | 2 | 2 | 0 | 0 | 66.7% |
| **all** | 9 | 6 | 4 | 0 | 2 | 66.7% |

Fields that had to stay empty: 0/0 were empty.

**Mapping (value in the right field)**

| Correct | Misplaced | Wrong | Missing | Placement rate |
|---|---|---|---|---|
| 6 | 0 | 3 | 0 | 100.0% |

**Dialogue**

| Reports | Questions per report | Max questions | Mode accuracy | Asked fields ok | Outcomes ok | Flow ok | Ask-once violations | Invariant violations |
|---|---|---|---|---|---|---|---|---|
| 5 | 1.00 | 2 | 3/4 | 1/1 | 0/0 | 5/5 | 0 | 0 |

Outcome mix per asked field:

| Field | no_response |
|---|---|
| immediate_actions | 1 |
| injury | 4 |

**Rephraser**

| Attempts | Validator pass rate | Rejection reasons | Latency median / p90 ms |
|---|---|---|---|
| 5 | 40.0% | timeout x3 | 2171 / 4713 |

**Latency (ms; median and nearest-rank p90)**

| Stage | Count | Median | p90 | Mean | Min | Max |
|---|---|---|---|---|---|---|
| ASR | 4 | 3074 | 3128 | 3052 | 2933 | 3128 |
| Prefill | 4 | 978 | 1008 | 928 | 747 | 1008 |
| Decode | 4 | 8231 | 11838 | 8771 | 6784 | 11838 |
| Total (end of speech to filled form) | 4 | 12313 | 15982 | 12772 | 10480 | 15982 |

**Prompt size**

Not measured by the eval run. Tokens per call type are measured with Qwen's tokenizer by `tools/count_tokens.py` and recorded in `doc/PROMPTS.md`.

**Cases**

| Case | Status | Mode | Asked | Fields matched | WER | Ask-once | Notes |
|---|---|---|---|---|---|---|---|
| E01 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E01_water_leak.wav |
| E02 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E02_water_on_floor.wav, audio/E02_a1_injury.wav, audio/E02_a2_location.wav |
| E03 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E03_slipped_in_pantry.wav, audio/E03_a1_injury_type.wav |
| E04 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E04_nobody_hurt_spark.wav |
| E05 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E05_smoke_server_room.wav |
| E06 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E06_harassment_parking_lot.wav, audio/E06_a1_injury.wav, audio/E06_a2_people_involved.wav |
| E07 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E07_something_happened_lab3.wav, audio/E07_a1_description.wav, audio/E07_a2_time.wav |
| E08 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E08_ac_block_c.wav |
| E09 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E09_something_is_broken.wav, audio/E09_a1_injury.wav |
| E10 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E10_door_handle_broke.wav, audio/E10_a1_injury.wav, audio/E10_a2_location.wav |
| E11 | ok | PLAIN | - ok | 0/0 | n/a | 0 |  |
| E12 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E12_leak_block_b.wav, audio/E12_a1_readback.wav |
| E13 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E13_fell_from_ladder.wav, audio/E13_a1_injured_person.wav, audio/E13_a2_injury_type.wav |
| E14 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E14_something_broken_lab.wav, audio/E14_a1_injury.wav, audio/E14_a2_description.wav |
| E15 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E15_slipped_cafeteria_stairs.wav, audio/E15_a1_injured_person.wav, audio/E15_a2_injury_type.wav |
| E16 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E16_hello_can_you_hear_me.wav, audio/E16_a1_injury.wav, audio/E16_a2_location.wav |
| E17 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E17_fire_canteen_kitchen.wav, audio/E17_a1_safety.wav, audio/E17_a2_injury.wav |
| E18 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E18_something_fell_off_shelf.wav, audio/E18_a1_injury.wav, audio/E18_a2_location.wav |
| E19 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E19_long_leak_block_b.wav |
| E20 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E20_spill_canteen.wav, audio/E20_a1_injury.wav, audio/E20_a2_description.wav |
| E21 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E21_noisy_fire_alarm_block_b.wav, audio/E21_a1_safety.wav, audio/E21_a2_injury.wav |
| E22 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E22_dripping_conference_room_two.wav, audio/E22_a1_injury.wav |
| E23 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E23_server_room_overheating.wav, audio/E23_a1_injury.wav |
| E24 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E24_coffee_burn_pantry.wav, audio/E24_a1_injured_person.wav, audio/E24_a2_injury_type.wav |
| E25 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E25_beaker_lab_three.wav |
| E26 | skipped | - | - | 0/0 | n/a | 0 | audio missing: audio/E26_gas_smell_canteen.wav, audio/E26_a1_safety.wav, audio/E26_a2_injury.wav |
| E27 | ok | PLAIN ok | injury | 1/2 | 0.0% | 0 |  |
| E28 | ok | URGENT (expected PLAIN) | injury | 2/3 | 0.0% | 0 |  |
| E29 | ok | PLAIN ok | injury | 1/2 | 0.0% | 0 |  |
| E30 | ok | URGENT ok | injury,immediate_actions | 2/2 | 0.0% | 0 |  |

> Note: `eval/audio/test-1..4.wav` are 4 real human recordings supplied by the team (cases E27-E30). Their reference transcripts were produced on a PC
> with faster-whisper small. No board ASR exists yet, so no WER has been measured.

## Photo-only cases (ADR-062)
`assets/eval/cases.json` now also holds `P01`-`P06` (the six sample photos, no speech). The numbers above are from the 30 speech cases and are unchanged; the photo cases are run separately (`EvalPhotoRunTest`, results table in `TESTING.md` "Photo-only reports": 6 ok, field accuracy 11/12, 0 questions).
