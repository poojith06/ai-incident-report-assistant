# App documentation

Android app (Kotlin, Jetpack Compose, MVVM). The user **speaks** an incident (or only attaches photos); the app transcribes it,
extracts a structured report with an on-device model, asks at most 2 short follow-up questions for fields that are still missing
(`DIALOGUE.md`), reads a summary back, and lets the user review and submit. Everything is offline (no INTERNET permission).

The app starts in **Mock AI** mode. The developer menu switches to **Real AI** with one of two backends: Qwen3-4B on the Hexagon NPU
(Genie) with whisper.cpp speech-to-text on the CPU, or Gemma 4 E2B (llama.cpp; LLM on the NPU, audio/image encoder on the GPU), which
also handles speech and photos. See `SERVICES_AND_MOCKS.md` and `MODEL_INTEGRATION.md`.

Pipeline: `SPEAK -> UNDERSTAND -> EXTRACT -> REVIEW -> SUBMIT`.

## Reading order

1. This file.
2. `ARCHITECTURE.md` (layers, folders and how a turn works).
3. `DECISIONS.md` (why things are the way they are).
4. `DATA_SCHEMA.md` (the Incident JSON contract and the field rules).
5. `STATE_MACHINE.md`, `UI_FLOW.md` and `DIALOGUE.md` (behaviour and the follow-up dialogue).
6. `SERVICES_AND_MOCKS.md`, `INTEGRATION_GUIDE.md`, `MODEL_INTEGRATION.md`, `PROMPTS.md` and `TTS.md` (models, prompts and speech).
7. `KNOWLEDGE_GRAPH.md`, `ADMIN_DASHBOARD.md` and `ADMIN_DASHBOARD_CONTRACT.md` (post-incident analysis and the admin dashboard).
8. `TESTING.md`, `EVAL_RESULTS.md` and `DEVICE_SETUP.md` (tests, measurements and running on the QIDK).
9. `COMPONENTS.md` and `THEMING.md` (UI building blocks and design tokens).

## Build and run

Full setup on the QIDK (runtime libraries, models, on-device tests): `docs/SETUP.md` at the repository root. Quick start, from `android/`:

```sh
./gradlew assembleDebug testDebugUnitTest      # gradlew.bat on Windows
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.test_app/.MainActivity
```

Prerequisites: Android SDK, JDK 17+, `adb` and a device with USB debugging. Android Studio writes the SDK path to `local.properties`
(not in git). The first Gradle build downloads dependencies and is slow (several minutes); later builds are incremental.
Package / applicationId: `com.example.test_app`. minSdk 30, targetSdk 37.

## Using the app

- **Home**: big mic button (voice report), gallery button (attach up to 3 photos from the system photo picker, then talk or use
  "Use photos only"), "Past reports", Settings and the Admin dashboard.
- Tap the mic: recording starts at once (no spoken greeting by default) and stops by itself after a pause, or when the mic is tapped
  again. No speech for 10 s shows "Tap the mic when you're ready". With Mock AI the speech-to-text ignores the audio and returns a
  scripted transcript. Follow-up questions ask each missing field at most once and at most 2 per report, hands-free (no speech for
  8 s = skipped); then a spoken read-back and the Review screen.

## Mock scenario selector (developer menu)

**Long-press the "Incident Assistant" title on Home.** A bottom sheet opens with:

| Scenario | What happens |
|---|---|
| Complete report | No questions. Straight to Review. Severity suggested as Low. |
| Injury follow-ups | The narration mentions an injury (injury itself is never asked), so the gentle CARE tone is used; asks what has been done and who was hurt (2 questions). |
| Vague location | "Someone slipped near the stairs." Time not mentioned (defaults to now, said in the read-back). Injury is never asked; at most 2 questions. |
| Gallery with photo | Complete report; pick a photo from the gallery so it is attached. |
| STT failure | STT fails once; "Try again" succeeds. |
| Extraction failure | LLM step fails once; "Try again" succeeds. |

Also in the sheet: the **Mock AI / Real AI** switch, the Real AI backend, the speech-to-text and text-to-speech engines, the
LLM-rephrase switch, a typed-transcript box (for the mock speech-to-text: one utterance per line), **Evaluation** (Run eval set from
typed transcripts; Run eval set from audio once a real speech-to-text model is loaded; see `../eval/README.md` and `TESTING.md`
Level 4), and per-step mock delays (STT, LLM, JSON, validation, tool call). Changing a setting re-arms the one-shot failures.

## Known limits

GPS is not captured. There is no NPU Whisper: speech-to-text is whisper.cpp on the CPU or Gemma's audio input. The Hindi and Telugu
question packs are AI translations pending native review. The QIDK has no Android TTS engine installed, so spoken output needs the
sherpa-onnx voice files (`TTS.md`).

## Post-incident analysis (offline knowledge graph)
After a report is submitted, the Success screen shows a collapsible "Post-incident analysis" card: what could follow, what to do next, recovery, temporary arrangements and prevention, from an offline graph (`doc/KNOWLEDGE_GRAPH.md`). The shipped guidance is DEMO guidance (label "Demo guidance - pending IIIT-H admin approval"); administrators replace it. Switch: Settings > After a report. Hand-off to a future admin dashboard: `doc/ADMIN_DASHBOARD_CONTRACT.md`.
