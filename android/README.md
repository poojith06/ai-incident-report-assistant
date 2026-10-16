# Android app

Gradle project for the incident-report app (Kotlin, Jetpack Compose, C++/JNI). Package `com.example.test_app`,
minSdk 30, targetSdk 37, arm64-v8a only.

| Path | Contents |
|---|---|
| `app/src/main/java/com/example/test_app/` | `ai/` (prompts, parser, Qwen/Genie, Gemma/llama.cpp, whisper.cpp, sherpa TTS), `dialogue/` (mode classifier, follow-up planner, language packs), `domain/` (report schema, validation, severity policy), `flow/` (state machine), `knowledgegraph/`, `admin/`, `eval/`, `ui/` |
| `app/src/main/cpp/` | JNI wrappers (`genie_jni.cpp`, `llama_jni.cpp`, `whisper_jni.cpp`), vendored whisper.cpp and llama.cpp headers |
| `app/src/main/assets/` | dialogue question banks and cue lists, knowledge-graph seed pack, eval cases |
| `app/src/test/` | JVM unit tests (no device needed) |
| `app/src/androidTest/` | on-device tests and benchmarks (QIDK) |
| `doc/` | technical documentation; start with [doc/README.md](doc/README.md) |
| `eval/` | eval-set format and audio recordings |
| `tools/` | runtime-library pull scripts, APK check, token counter, eval report, knowledge-graph seed builder |

Build and unit tests: `./gradlew assembleDebug testDebugUnitTest`. Device setup: [../docs/SETUP.md](../docs/SETUP.md).
Model files and native runtime libraries are not in the repository.
