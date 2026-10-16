# Setup and run

Android app (Kotlin, Jetpack Compose) in `reporter-app/`. Target device: Qualcomm Innovators Development Kit (QIDK), Snapdragon 8 Elite (SM8750P), Android 15, QAIRT 2.45. Everything runs offline.

## Requirements

- Windows, macOS or Linux host with Android SDK, JDK 17 or newer, and `adb` (USB debugging enabled on the device)
- Android NDK 28.2 (installed by Android Studio / Gradle on first build)
- A QIDK board connected over USB. `adb devices` must list it. Below, `<serial>` is its adb serial.
- About 8 GB of free storage on the device (Qwen3-4B 3 GB, Gemma 4 E2B 4.4 GB, Whisper 0.5 GB, TTS 0.1 GB; install only the backends you want)

## 1. Get the code

```sh
git clone <repository-url>
cd <repository>/reporter-app
```

## 2. Pull the runtime libraries (once per checkout, git-ignored)

```sh
tools/pull_qairt_libs.sh <serial>      # QAIRT runtime for Qwen3-4B (Genie)
tools/pull_llama_libs.sh <serial>      # llama.cpp libraries, only for the Gemma backend
sh tools/pull_sherpa_libs.sh           # sherpa-onnx libraries (downloads from GitHub), for spoken output
```

The `pull_*_libs.sh` scripts read the libraries from the board. They expect the QAIRT SDK in `/data/local/tmp/qairt/` and the llama.cpp build in `/data/local/tmp/models/llama_snap/`.

## 3. Build and install

```sh
./gradlew assembleDebug testDebugUnitTest     # use gradlew.bat on Windows
python tools/check_apk_libs.py                # optional: checks the APK's native libraries
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

The first Gradle build downloads dependencies and takes several minutes. Run unit tests alone with `./gradlew testDebugUnitTest` (no device needed).

## 4. Put the models on the device

Models are not in the repository. Copy each into the app's internal folder with `run-as` (needs the debug build). **Uninstalling the app deletes the models; copy them again.**

**Qwen3-4B (LLM on the NPU), required for the Qwen backend.** Push the Genie bundle to `/data/local/tmp/qwen4b/`, then:

```sh
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/qwen4b && cp /data/local/tmp/qwen4b/* files/qwen4b/'"
```

**Whisper `small.en` (speech to text on the CPU), for the Qwen backend:**

```sh
mkdir -p build/models
curl -L -o build/models/ggml-small.en.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin
adb -s <serial> shell mkdir -p /data/local/tmp/whisper
adb -s <serial> push build/models/ggml-small.en.bin /data/local/tmp/whisper/
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/whisper && cp /data/local/tmp/whisper/* files/whisper/'"
```

**Gemma 4 E2B (speech, photo and text in one model), optional:**

```sh
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/gemma && cp /data/local/tmp/models/gemma/gemma-4-E2B_q4_0-it.gguf /data/local/tmp/models/gemma/gemma-4-E2B-it-mmproj.gguf files/gemma/'"
```

**Spoken output (sherpa-onnx: Piper English + Rasa Indic), optional.** Put the files in `/data/local/tmp/tts/piper-amy/` (`en_US-amy-medium.onnx`, `tokens.txt`, `espeak-ng-data/`) and `/data/local/tmp/tts/rasa/` (`indic_model_int8.onnx`, `tokens.txt`), then:

```sh
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/tts && cp -r /data/local/tmp/tts/piper-amy /data/local/tmp/tts/rasa files/tts/'"
```

Without the TTS files the app uses the Android text-to-speech engine.

## 5. Run

```sh
adb -s <serial> shell am start -n com.example.test_app/.MainActivity
```

Tap the microphone and describe an incident. The app works in airplane mode. To switch models or run the evaluation set, long-press the "Incident Assistant" title on the Home screen to open the developer menu: turn on **Real AI**, choose the backend (Qwen3-4B + Whisper, or Gemma 4 E2B), then **Run eval set**.

## 6. On-device tests (optional)

```sh
./gradlew :app:installDebug :app:installDebugAndroidTest
adb -s <serial> shell am instrument -w -e class com.example.test_app.ai.GenieLlmBenchmarkTest \
  com.example.test_app.test/androidx.test.runner.AndroidJUnitRunner
```

Do not use `connectedAndroidTest`: it uninstalls the app and deletes the models.

## Externally hosted models and data

| Item | Where to get it |
|---|---|
| Qwen3-4B-Instruct-2507 (w4a16) Genie bundle for Snapdragon 8 Elite | Qualcomm AI Hub: https://aihub.qualcomm.com |
| QAIRT / Genie runtime 2.45 | Qualcomm AI Runtime SDK, preinstalled on the QIDK at `/data/local/tmp/qairt/` |
| Whisper `ggml-small.en.bin` (488 MB) | https://huggingface.co/ggerganov/whisper.cpp |
| Gemma 4 E2B `q4_0` GGUF and mmproj, llama.cpp b11552 (Snapdragon build) | Provided on the board under `/data/local/tmp/models/` |
| sherpa-onnx v1.13.8 Android libraries | https://github.com/k2fsa/sherpa-onnx/releases/tag/v1.13.8 (fetched by `tools/pull_sherpa_libs.sh`) |
| Piper `en_US-amy-medium` (int8) voice | https://github.com/k2fsa/sherpa-onnx/releases/tag/tts-models |
| AI4Bharat Rasa Indic voice (`vits_rasa_13`, int8) | https://huggingface.co/ai4bharat/vits_rasa_13 |
| Evaluation sets | In this repository: `reporter-app/app/src/main/assets/eval/cases.json`, `reporter-app/eval/audio/`, `code/benchmarking/office_severity_benchmark.json` |

## License

MIT, see `LICENSE`.
