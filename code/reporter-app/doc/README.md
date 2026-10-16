# AI Incident Report Assistant (frontend phase)

Android app (Kotlin, Jetpack Compose, MVVM). The user **speaks** an incident; the app turns it into a structured
report, asks short follow-up questions when something important is missing, fills a form, reads a confirmation
aloud, and lets the user review and submit. Everything is offline. A follow-up dialogue (`DIALOGUE.md`) greets, asks each missing field once and reads back.
AI is **mock by default**; the developer menu switches to **Real AI** (Qwen3-4B on the Hexagon NPU via Genie). Speech-to-text is mock or, when
selected (default with Real AI once `files/whisper/ggml-small.en.bin` is on the device), the **whisper.cpp CPU stopgap** (not the NPU Whisper, which does not exist on the board yet). See `SERVICES_AND_MOCKS.md`, `MODEL_INTEGRATION.md` and `INTEGRATION_GUIDE.md`.

Pipeline: `SPEAK -> UNDERSTAND -> EXTRACT -> REVIEW -> SUBMIT`.

## Reading order for a new agent

1. This file.
2. `DECISIONS.md` (why things are the way they are).
3. `ARCHITECTURE.md` and `FILE_MAP.md` (where things are).
4. `DATA_SCHEMA.md` (the Incident JSON contract and the field rules).
5. `STATE_MACHINE.md` and `UI_FLOW.md` (behaviour).
6. `SERVICES_AND_MOCKS.md` then `INTEGRATION_GUIDE.md` (swapping mocks for real models; `TTS.md` for the offline speech output).
7. `COMPONENTS.md`, `THEMING.md` (UI building blocks and design tokens).
8. `TODO.md`, then the latest entries of `AGENT_LOG.md`.

## Build and run (Windows)

Prereqs: Android SDK, JDK 17+ (JDK 24 works), `adb`, a device with USB debugging (or an emulator).
`local.properties` already points at the SDK.

```bat
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.test_app/.MainActivity
```

The first Gradle build downloads dependencies and is slow (several minutes). Later builds are incremental.
Package / applicationId: `com.example.test_app`. minSdk 30, targetSdk 37.

## Using the app

- **Home**: big mic button (voice report), small gallery button (attach up to 3 photos from the system photo picker, then talk or continue with photos only), "Past reports".
- Tap the mic: the assistant says "Hi, describe your incident." once, then listens (it stops by itself after a pause; tapping the mic
  also stops it). No speech for 10 s shows "Tap the mic when you're ready". The mock STT ignores the audio and returns a scripted
  transcript (real microphone capture and voice-activity detection still happen and are permission-gated; on a silent emulator tap
  the mic to finish). Follow-up questions are asked once each, hands-free (no speech for 8 s = skipped), then a read-back and the Review screen.

## Mock scenario selector (developer menu)

**Long-press the "Incident Assistant" title on Home.** A bottom sheet opens with:

| Scenario | What happens |
|---|---|
| Complete report | No questions. Straight to Review. Severity suggested as Low. |
| Injury follow-ups | Asks "Is anyone hurt?"; the scripted yes switches to the gentle CARE tone, then asks who was hurt and what kind of injury (each once). |
| Vague location | Time not mentioned (defaults to now, said in the read-back). Asks the injury question first, up to the mode's cap. |
| Gallery with photo | Complete report; pick a photo from the gallery so it is attached. |
| STT failure | STT fails once; "Try again" succeeds. |
| Extraction failure | LLM step fails once; "Try again" succeeds. |

Also in the sheet: the **Mock AI / Real AI** switch and the LLM-rephrase switch (both persisted), a typed-transcript box (stand-in for speech
recognition: one utterance per line), **Evaluation** (Run eval set: the English eval set through the real dialogue pipeline with the selected services, from typed transcripts; an audio run is offered only once a real speech-to-text model exists; see `../eval/README.md`, `TESTING.md` Level 4),
and per-step mock delays (STT, LLM, JSON, validation, tool call). Changing a setting re-arms the one-shot failures.

## Known limits

GPS is not captured yet, TTS is the Android engine, speech-to-text is mock or the whisper.cpp CPU stopgap (no NPU Whisper yet). See `TODO.md`.

## Post-incident analysis (offline knowledge graph)
After a report is submitted, the Success screen shows a collapsible "Post-incident analysis" card: what could follow, what to do next, recovery, temporary arrangements and prevention, from an offline graph (`doc/KNOWLEDGE_GRAPH.md`). The shipped guidance is DEMO guidance (label "Demo guidance - pending IIIT-H admin approval"); administrators replace it. Switch: Settings > After a report. Hand-off to a future admin dashboard: `doc/ADMIN_DASHBOARD_CONTRACT.md`.
