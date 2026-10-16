# Architecture

Single-module Android app, package `com.example.test_app`. MVVM + one explicit state machine.
No network code anywhere (no INTERNET permission).

## Layers

| Layer | Package | Role |
|---|---|---|
| UI | `ui/**` | Stateless Compose screens + components. Read state, call callbacks. No business logic. |
| Flow | `flow/` | `FlowState`/`FlowEvent`/`FlowReducer` (pure state machine) and `IncidentFlowViewModel` (runs side effects per state). |
| Domain | `domain/` | Data classes (Incident JSON), config, service **interfaces**, real logic (validation, policies, JSON codec). Pure Kotlin. |
| Mocks | `mock/` | Mock implementations of AI services and `DevSettings` (scenarios, delays). |
| AI | `ai/` | Prompts, output parser and mapper; LLM engines (`genie/` Qwen3-4B, `llama/` Gemma 4 E2B), `whisper/` speech-to-text, `tts/` sherpa-onnx. |
| Dialogue | `dialogue/` | Follow-up dialogue: mode classifier, planner, question banks, language packs, read-outs. Pure Kotlin. |
| Knowledge graph, admin | `knowledgegraph/`, `admin/` | Offline post-incident analysis and the in-app admin dashboard logic. Pure Kotlin. |
| Platform | `platform/` | Real Android implementations: `AudioRecorder`, `AndroidTextToSpeechService`, `JsonFileReportRepository`, `LocalToolExecutor`, `DeviceContext`. |
| DI | `AppContainer.kt` | The ONE place that picks implementations. Manual DI (see ADR-004). |

```mermaid
flowchart LR
  subgraph UI
    AR[AppRoot] --> Screens[Home / Dialogue / Processing / Add more / Review / Success / Error / Settings / History / Admin]
  end
  AR -- dispatch(FlowEvent) --> VM[IncidentFlowViewModel]
  VM -- StateFlow FlowState --> AR
  VM --> RED[FlowReducer pure]
  VM --> C[AppContainer]
  C --> STT[SpeechToTextService - mock, whisper.cpp CPU or Gemma audio]
  C --> LLM[IncidentExtractionService - mock, Qwen3-4B via Genie or Gemma 4 E2B via llama.cpp]
  C --> RPH[Rephraser - same LLM or none]
  C --> IMG[ImageAnalysisService - mock or Gemma]
  VM --> DLG[DialogueManager - owns what is spoken]
  DLG --> MIC[MicSpeechIn - mic + VAD + STT]
  DLG --> OUT[TtsSpeechOut - sherpa-onnx or Android TTS]
  C --> VAL[ValidationService - real]
  C --> TOOL[ToolExecutor - real local]
  C --> TTS[TextToSpeechService - sherpa-onnx Piper and Rasa, or Android TTS]
  C --> REPO[ReportRepository - JSON files]
  C --> REC[AudioRecorder - AudioRecord]
  VM --> POL[IncidentPolicies - real]
  VM --> KG[KnowledgeGraphService - offline post-incident analysis]
```

## How a turn works

1. UI calls `vm.dispatch(event)`.
2. `FlowReducer.reduce(state, event)` returns the next state (pure; unit-tested).
3. The ViewModel publishes the state and runs the effect for the state it just entered
   (`IncidentFlowViewModel.enter`): transcribe, extract, validate, speak, fill form, submit.
4. The effect finishes by dispatching the next event (`TranscriptReady`, `ExtractionReady`, ...).
   Dispatching is always the last statement of an effect (dispatch cancels the current effect job).

## Per-pass pipeline in the ViewModel

Voice path (with optional gallery photos, ADR-053): `Dialogue` (`DialogueManager`: greeting, listen, extract, classify, ask-once follow-ups, read-back; see `DIALOGUE.md`) ->
the ViewModel adds device info, `IncidentPolicies` and `ValidationService` -> `DialogueFinished`. "Add more by voice": `Transcribing` -> `Extracting` ->
`Validating` -> `FillingForm`.

## Folder structure

Top level:

```
app/src/main/java/com/example/test_app/
  AppContainer.kt  MainActivity.kt (also IncidentApp)
  admin/  ai/{genie,llama,tts,whisper}  dialogue/  domain/{model,config,service}  eval/
  flow/  knowledgegraph/  media/  mock/  platform/
  ui/{admin,analysis,clarification,components,dialogue,error,home,processing,review,settings,success,theme}
app/src/main/cpp/  JNI wrappers (Genie, llama.cpp, whisper.cpp) and vendored headers
app/src/test/...   JVM unit tests
app/src/androidTest/...   on-device tests and benchmarks
doc/               documentation
```

## Threading

All state lives on the main thread (`viewModelScope`). `AudioRecorder` writes on `Dispatchers.IO`.
Repository IO uses `Dispatchers.IO`. Services are `suspend` so real models can run on any dispatcher.

## Offline guarantee

No `INTERNET` permission in `AndroidManifest.xml`. TTS uses the on-device engine. Photos and reports live in
app-private storage (`filesDir/media`, `filesDir/reports`); recorded audio in `cacheDir` and deleted after STT.
