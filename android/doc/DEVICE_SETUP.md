# Device setup (QIDK / Snapdragon 8 Elite, Hexagon v79)

Verified on: board `Sun_for_arm64` (SoC SM8750P, Android 15). Package: `com.example.test_app`.

## 1. What is already on the board
| Path | Content |
|---|---|
| `/data/local/tmp/qwen4b/` | Qwen3-4B Genie bundle: `genie_config.json`, `tokenizer.json`, `part1_of_4.bin` .. `part4_of_4.bin`, `htp_backend_ext_config.json` (3 GB) |
| `/data/local/tmp/qairt/` | QAIRT SDK libs (`lib/aarch64-android`, `lib/hexagon-v79/unsigned`) and headers (`include/Genie`) |

## 2. QAIRT runtime libraries (shipped inside the APK)
The app packages the libs in `app/src/main/jniLibs/arm64-v8a/` (git-ignored, 26 MB). Pull them once from the board:
```sh
tools/pull_qairt_libs.sh <serial>
```
Files: `libGenie.so`, `libQnnHtp.so`, `libQnnHtpV79Stub.so`, `libQnnSystem.so`, `libQnnHtpV79Skel.so`.
`libQnnHtpPrepare.so` (87 MB) is NOT needed: the model ships as precompiled context binaries.
`useLegacyPackaging = true` keeps them extracted, because the DSP loads the Skel from `ADSP_LIBRARY_PATH` (= `nativeLibraryDir`).
The manifest declares `<uses-native-library android:name="libcdsprpc.so">`; without it the HTP stub cannot load (`libcdsprpc.so not found` in logcat).

## 3. Copy the model into the app
The integration guide's path `/storage/emulated/0/Android/data/<pkg>/files/<dir>` is NOT readable by the app on this board when the
files are created by `adb shell` (they are owned by `shell`; the app gets `Permission denied`). Use the app's internal folder, which
`GenieLlmEngine` searches second (external first, then internal):
```sh
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/qwen4b && cp /data/local/tmp/qwen4b/* files/qwen4b/'"
adb -s <serial> shell "run-as com.example.test_app du -sh files/qwen4b"     # ~2.9G, 19 files
```
Requires a debuggable build (`run-as`). Uninstalling the app deletes the model: re-copy after every uninstall.
`./gradlew connectedAndroidTest` uninstalls the app at the end; use `installDebug installDebugAndroidTest` and run `am instrument` yourself instead.

## 4. genie_config.json: no on-disk edits
The integration guide edits `genie_config.json` with `sed`. This app does not: `GenieLlmEngine.patchConfig` rewrites in memory the
`tokenizer.path`, `ctx-bins[]` and `extensions` entries to absolute paths and forces `sampler.temp = 0.1`, `top-k = 1`.
Note: the file on the board has `temp 0.8, top-k 40, top-p 0.95`, which differs from what the guide says ("already deterministic").
The LLM side owns the config; confirm the override is acceptable.

## 5. Verify on the board
```sh
adb -s <serial> shell am instrument -w -e class com.example.test_app.ai.GenieLlmBenchmarkTest \
  com.example.test_app.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> logcat -d -s BENCH GenieLlmEngine GenieJni GenieNative
```
Success: `OK (1 test)` and a `BENCH SUMMARY ...` line. Native Genie/QNN logs appear under tag `GenieNative`.
Common failures: `libcdsprpc.so not found` (manifest entry missing), `Missing or unreadable genie_config.json` (step 3), `GenieDialog_create failed: -1` (read `GenieNative` errors).

## 6. Whisper (ASR)
No Whisper model or runtime was found on the board and none was provided with the repo. Needed from the ASR side: Whisper-Small multilingual
QNN context binaries (encoder + decoder), tokenizer/vocab, mel-filter data, and the runner library/JNI source.

## 7. Whisper CPU stopgap model (whisper.cpp, not NPU)
Download once (not in git; `build/models/` is ignored) and push. The app cannot read adb-shell-created files in `Android/data`, so copy into the app's internal folder with `run-as` like the Qwen bundle:
```sh
mkdir -p build/models
curl -L -o build/models/ggml-small.en.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin   # 488 MB, the default
curl -L -o build/models/ggml-base.en.bin  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin    # 148 MB, optional
adb -s <serial> shell mkdir -p /data/local/tmp/whisper
adb -s <serial> push build/models/ggml-small.en.bin build/models/ggml-base.en.bin /data/local/tmp/whisper/
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/whisper && cp /data/local/tmp/whisper/* files/whisper/'"
```
Eval recordings (used by `WhisperCppBenchmarkTest` and `EvalAudioRunTest`): the app reads `files/eval/audio/` of the external dir when it has files, else the internal dir:
```sh
adb -s <serial> shell mkdir -p /data/local/tmp/eval/audio && adb -s <serial> push eval/audio/. /data/local/tmp/eval/audio/
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/eval/audio && cp /data/local/tmp/eval/audio/* files/eval/audio/'"
```
Run the benchmark (builds with `./gradlew.bat :app:installDebug :app:installDebugAndroidTest`; never `connectedAndroidTest`, it uninstalls the app and deletes the models):
```sh
adb -s <serial> shell am instrument -w -e class com.example.test_app.ai.whisper.WhisperCppBenchmarkTest [-e model ggml-base.en.bin] [-e threads 6] [-e prompt true] \
  com.example.test_app.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> logcat -d -s BENCH WhisperCpp
```
Uninstalling the app deletes the models: re-copy. This replaces section 6 for the interim; the NPU Whisper is still needed. The Gemma backend (section 8) does not need Whisper: Gemma 4 hears the audio itself.

## 8. Text-to-speech engine check (offline read-out)
The app speaks with the native Android `TextToSpeech` (default engine, offline voices only). **The QIDK has no TTS engine installed**, so the banner
"Voice read-out isn't available on this device" is expected there until an offline engine is installed. Check:
```sh
adb -s <serial> shell cmd package query-services -a android.intent.action.TTS_SERVICE     # no result = no engine installed
adb -s <serial> shell settings get secure tts_default_synth                                # null = no default engine
adb -s <serial> logcat -d -s TtsService                                                    # engine package + chosen voice, logged once per start
```
The engine must have an English voice that is installed and does not need the network; a network-only engine is treated as unavailable.
The developer-menu switch "Read results aloud" (default on) turns the automatic read-outs on Review and Success off; the speaker button on Review still works.

## 8. Gemma 4 E2B (llama.cpp, NPU + GPU): libraries and model files
The board has the Snapdragon build of llama.cpp b11552 and the Gemma files under `/data/local/tmp/models/` (user's scratch area; read-only for us: never modify it):
`llama_snap/{include,lib}` and `gemma/gemma-4-E2B_q4_0-it.gguf` (3.3 GB) + `gemma/gemma-4-E2B-it-mmproj.gguf` (987 MB). `libllama-common.so` etc. stay there.

**Libraries and headers into the APK build (once per checkout; `app/src/main/jniLibs` is git-ignored, the headers in `app/src/main/cpp/third_party/llama/include` are tracked):**
```sh
tools/pull_llama_libs.sh <serial>        # read-only adb pull: libllama, libmtmd, libggml{,-base,-cpu,-rpc,-opencl,-hexagon}, libggml-htp-v79 + headers
./gradlew.bat assembleDebug               # builds libllama_jni.so against them
python tools/check_apk_libs.py            # host check: unique sonames, every DT_NEEDED resolves
```
(`libggml-htp-v79.so` is the DSP-side skel; it is found through `ADSP_LIBRARY_PATH`, which `llama_jni.cpp` sets from `nativeLibraryDir`. v73/v75/v81 are not needed for SM8750.)

**Model files into the app (internal files dir, like Qwen: the external path is not readable for adb-created files on this board, section 3):**
```sh
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/gemma && cp /data/local/tmp/models/gemma/gemma-4-E2B_q4_0-it.gguf /data/local/tmp/models/gemma/gemma-4-E2B-it-mmproj.gguf files/gemma/'"
adb -s <serial> shell "run-as com.example.test_app ls -l files/gemma"          # 2 files, 3349516256 and 986833664 bytes
```
Needs ~4.4 GB of free internal storage (on top of Qwen 2.9 GB and Whisper 0.5 GB if they are there). Uninstalling deletes the models: copy again. Use `installDebug installDebugAndroidTest` + `am instrument`, never `connectedAndroidTest` (it uninstalls).
Selecting the backend: developer menu (long-press the Home title) -> "Real AI" on -> "Real AI backend: Gemma 4 E2B"; the app loads Gemma at start (Home: "Loading models...") and releases the other backend.
Chat template used by Gemma (read from the GGUF with `tools/gguf_meta.py`): `PROMPTS.md` "Gemma chat format". Device test plan: `TESTING.md` "Gemma on the device".

## 9. Sherpa TTS (Piper English + Rasa Indic): libraries and model files
Replaces the missing Android TTS engine of the QIDK (section 8 stays valid for the System engine). Design, results, known issues: `TTS.md`.

**Libraries (once per checkout; `app/src/main/jniLibs` is git-ignored):**
```sh
sh tools/pull_sherpa_libs.sh        # curl: sherpa-onnx v1.13.8 Android prebuilt -> libsherpa-onnx-jni.so + libonnxruntime.so (arm64-v8a only, 27 MB)
./gradlew.bat assembleDebug
python tools/check_apk_libs.py      # unique sonames, every DT_NEEDED resolves
```

**Models (not in git; downloaded on the PC, about 75 MB in total):** two folders, each pushed to the scratch area, then copied into the app's internal folder (the app cannot read adb-created files under `Android/data`, section 3):
```sh
M=/path/to/tts-models   # folder that holds vits-piper-en_US-amy-medium-int8/ and rasa-onnx/
adb -s <serial> shell mkdir -p /data/local/tmp/tts/piper-amy /data/local/tmp/tts/rasa
adb -s <serial> push $M/vits-piper-en_US-amy-medium-int8/en_US-amy-medium.onnx $M/vits-piper-en_US-amy-medium-int8/tokens.txt /data/local/tmp/tts/piper-amy/
adb -s <serial> push $M/vits-piper-en_US-amy-medium-int8/espeak-ng-data /data/local/tmp/tts/piper-amy/
adb -s <serial> push $M/rasa-onnx/indic_model_int8.onnx $M/rasa-onnx/tokens.txt /data/local/tmp/tts/rasa/      # the INT8 file (40 MB), not model.onnx (fp32, 123 MB)
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell "run-as com.example.test_app sh -c 'mkdir -p files/tts && cp -r /data/local/tmp/tts/piper-amy /data/local/tmp/tts/rasa files/tts/'"
adb -s <serial> shell "run-as com.example.test_app du -sh files/tts"      # about 75M
```
(Git Bash on Windows: prefix with `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"` for device paths that start with `/`.) Required files: `files/tts/piper-amy/{en_US-amy-medium.onnx, tokens.txt, espeak-ng-data/}` and `files/tts/rasa/{indic_model_int8.onnx, tokens.txt}`. Uninstalling the app deletes them: copy again. With the files present Sherpa is the default engine; without them the app uses the System engine. Switch in the developer menu: "Text-to-speech engine".

Run the device test and the round trip: `TTS.md` "Device test and round trip".
