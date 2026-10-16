# AI Incident Report Assistant: Final Code Submission

Megathon X, Qualcomm Problem Statement 1. A voice-driven incident reporting assistant that runs **entirely on-device** on the Qualcomm Innovators Development Kit (QIDK: Snapdragon 8 Elite SM8750P, Hexagon V79 NPU, Android 15, QAIRT 2.45). There are no cloud calls and no `INTERNET` permission; the app works in airplane mode.

The user speaks (or attaches photos). The app transcribes the speech, extracts the report with an on-device model, asks only for what is still missing, fills a review form, reads the result back and saves it.

**Design principle: the model proposes, the code verifies.** The LLM only turns language into fields. Grounding, severity, which question to ask, when to stop and what to say about emergencies are deterministic, unit-tested Kotlin.

## Folder layout

```
code_final/
├── reporter-app/            The Android application (Kotlin, Jetpack Compose, JNI/C++)
├── rules_and_evaluation/    Rule-layer prototype, on-device test harness, 35-case test set, results
├── severity_classifier/     Severity rulebook, framework, labelled dataset
├── benchmarking/            Multilingual office-incident severity benchmark
└── LICENSE                  MIT
```

## Pipeline and models

| Stage | Component | Runtime |
|---|---|---|
| Speech to text | Gemma 4 E2B native audio input (hi, te, ta, as, or, en); whisper.cpp `small.en` on CPU as the alternative backend | llama.cpp JNI / whisper.cpp JNI |
| Extraction | Gemma 4 E2B (q4_0), speech, photos and text in one model: prefill on the Hexagon NPU, decode on CPU+GPU. Alternative backend: Qwen3-4B (w4a16) on the NPU via Genie | llama.cpp / Genie JNI |
| Rules and dialogue | Grounding, `SeverityPolicy`, mode classifier (PLAIN / CARE / URGENT), follow-up planner (each empty field asked once, cap 3), phrase validator | Pure Kotlin, JVM-tested |
| Speech output | Piper (English) and AI4Bharat Rasa (Indic) via sherpa-onnx; Android TTS fallback | sherpa-onnx JNI |
| Post-incident guidance | Offline knowledge graph (458 nodes, 872 edges), repeat-incident detection | Kotlin |
| Memory and admin | PIN-gated admin dashboard, persistent report history, graph visualization, knowledge editor | Kotlin |

## What is in `reporter-app/`

| Path | Contents |
|---|---|
| `app/src/main/java/com/example/test_app/ai/` | Prompts, LLM output parser, output mapper; `genie/` (Qwen on NPU), `llama/` (Gemma: text, speech, photos), `whisper/`, `tts/` (sherpa) |
| `.../dialogue/` | Follow-up dialogue: mode classifier, planner, question banks, language detection and packs, rephraser, phrase validator, form read-out |
| `.../domain/` | Incident model and JSON schema 1.1, validation, `SeverityPolicy`, emergency contacts |
| `.../flow/` | State machine and `IncidentFlowViewModel` |
| `.../knowledgegraph/` | Graph engine, rules and safety guards, history detection, LLM grounding, admin graph tools |
| `.../admin/` | Admin dashboard state, filters, stats, import/export |
| `.../ui/` | Compose screens: home, dialogue, review, success, analysis, admin, settings, history |
| `.../eval/`, `.../mock/`, `.../platform/`, `.../media/` | In-app evaluation harness (WER, field match, dialogue, latency), mock AI for demos, mic/TTS/storage, photo handling |
| `app/src/main/cpp/` | JNI wrappers (`genie_jni.cpp`, `whisper_jni.cpp`, `llama_jni.cpp`), Genie headers, vendored whisper.cpp and llama headers |
| `app/src/main/assets/` | `dialogue/` question banks and cue lists (en, hi, te), `kg/` seed knowledge graph, `eval/cases.json` |
| `app/src/test/` | JVM unit tests (dialogue, severity, parser, flow, eval metrics, knowledge graph, admin) with captured model outputs |
| `app/src/androidTest/` | On-device tests: Genie and Gemma LLM benchmarks, whisper.cpp, sherpa TTS, audio and photo eval runs |
| `eval/audio/` | Recorded WAV samples for the eval run |
| `tools/` | Library pull scripts (`pull_qairt_libs.sh`, `pull_llama_libs.sh`, `pull_sherpa_libs.sh`), APK check, eval report, knowledge-graph seed builder |
| `doc/` | Architecture, state machine, dialogue, prompts, testing, device setup, knowledge graph, admin dashboard, TTS, eval results. Start with `doc/README.md` |

### Other folders

- `rules_and_evaluation/`: `rules/IncidentRules.kt` (prompts, parser, rule engine, follow-up manager) and `IncidentRulesCheck.kt` (24 self-checks on real device outputs); `eval/` (`testset.json` with 35 cases across 7 categories, `eval.ps1`, `tests.ps1`, `whisper_test.py`); `results/` (one Qwen3-4B and one Llama 3.2 1B run, Whisper results).
- `severity_classifier/`: 5-tier severity framework and LLM prompt, mapping rulebook (JSON and markdown), 100 labelled narratives, combined dataset, and `feature_context_layers.md` (severity x urgency, live entity extraction, duplicate detection).
- `benchmarking/office_severity_benchmark.json`: 30 spoken office cases in 6 languages with gold type, severity, urgency, status and traps.

## Key results (measured on the QIDK)

| Measurement | Result |
|---|---|
| Model choice, 35-case set (earlier extraction model) | Qwen3-4B 92.1% field accuracy vs. Llama 3.2 1B 47.9%; 100% valid JSON |
| Rule layer, development set | 93.6% -> 100% field accuracy, hallucinated fields 1 -> 0 (development-set figure, optimistic) |
| Gemma 4 E2B prefill / decode | NPU prefill 1,450 tok/s (CPU 320); CPU+GPU decode 35 tok/s (NPU 19) |
| Speech end to filled form | About 4 s end to end |
| Photos-only reports (6 photos) | 6/6 runs ok, field accuracy 11/12, 0 questions asked |

## Setup and run

Requirements: Android SDK, JDK 17+, Android NDK 28.2 (installed by Gradle on first build), `adb`, and a QIDK connected over USB. About 8 GB free on the device. Below, `<serial>` is the adb serial.

Models and native runtime libraries are **not** in the repository (large, git-ignored). They are pulled from or pushed to the board.

```sh
cd reporter-app

# 1. Runtime libraries (once per checkout)
tools/pull_qairt_libs.sh <serial>      # QAIRT runtime for Qwen3-4B (Genie)
tools/pull_llama_libs.sh <serial>      # llama.cpp libraries, for the Gemma backend
sh tools/pull_sherpa_libs.sh           # sherpa-onnx libraries (downloads from GitHub), for spoken output

# 2. Build, unit-test, install
./gradlew assembleDebug testDebugUnitTest     # gradlew.bat on Windows
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Unit tests need no device: `./gradlew testDebugUnitTest`.

Copy the model files into the app's internal storage with `run-as` (debug build). Uninstalling the app deletes them.

```sh
# Qwen3-4B Genie bundle (push to /data/local/tmp/qwen4b/ first)
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/qwen4b && cp /data/local/tmp/qwen4b/* files/qwen4b/'"

# Gemma 4 E2B (optional backend: speech, photos and text)
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/gemma && cp /data/local/tmp/models/gemma/gemma-4-E2B_q4_0-it.gguf /data/local/tmp/models/gemma/gemma-4-E2B-it-mmproj.gguf files/gemma/'"
```

Whisper `small.en` and the sherpa-onnx voices follow the same pattern. Full commands are in `reporter-app/doc/DEVICE_SETUP.md`. Without the TTS files the app falls back to Android TTS.

Run:

```sh
adb -s <serial> shell am start -n com.example.test_app/.MainActivity
```

Tap the mic and describe an incident. To switch models or run the eval set, long-press the "Incident Assistant" title on Home, turn on **Real AI**, choose the backend, then **Run eval set**. With **Mock AI** (the default) the UI is demoable without any model files.

## Not included

Model weights, QAIRT and sherpa libraries, research PDFs, slide decks and report PDFs.
