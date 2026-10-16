# Model integration

## Status
| Model | Status |
|---|---|
| Qwen3-4B via Genie (Hexagon NPU) | **Real, verified on QIDK** (`GenieLlmBenchmarkTest`) |
| ASR: whisper.cpp small.en on the CPU (**stopgap, not NPU**) | **Real, measured on QIDK** (`WhisperCppBenchmarkTest`), see the Whisper section below |
| Whisper on the Hexagon NPU | **Not integrated**: no model/runtime available (see DEVICE_SETUP.md section 6, `TODO.md`) |
| **Gemma 4 E2B (q4_0 GGUF + mmproj) via llama.cpp b11552 JNI**: LLM on the Hexagon NPU, audio/image encoder on the Adreno GPU, native speech, photo and text input | **Implemented, compiles and links; NOT yet run on the device** (the board was reserved for live testing): see the "Gemma 4 E2B" section below and `TESTING.md` "Gemma on the device" |
| Android TextToSpeech | Real (`AndroidTextToSpeechService`) |

## Native code
No teammate sample app or `.so` sources were available, so the wrapper was written from the QAIRT 2.x Genie C headers pulled from the board
(`/data/local/tmp/qairt/include/Genie` -> `app/src/main/cpp/include/Genie`).
- `app/src/main/cpp/genie_jni.cpp`, built by `CMakeLists.txt` (NDK 28.2, arm64-v8a) into `libgenie_jni.so`, linked against the prebuilt `libGenie.so`.
- Prebuilt QAIRT libs: see DEVICE_SETUP.md section 2.

## JNI (class `com.example.test_app.ai.genie.GenieNative`)
```
long   nativeCreate(String configJson, String libDir)   // sets ADSP_LIBRARY_PATH/LD_LIBRARY_PATH, GenieDialogConfig_createFromJson, GenieDialog_create; binds a logger -> logcat tag GenieNative
String nativeQuery(long h, String prompt)                // GenieDialog_query(SENTENCE_COMPLETE); returns concatenated text; throws on status < 0
long[] nativeLastTimings(long h)                         // [firstChunkMs (~prefill), totalMs]
void   nativeReset(long h)                               // GenieDialog_reset
void   nativeFree(long h)
```

## Load sequence
`GenieLlmEngine.load()` (single genie thread): find `genie_config.json` (external files dir, then internal) -> patch config in memory ->
`nativeCreate` (about 3.7 s measured) -> ready. Called once at app start by the AppContainer (Real AI mode); Home shows "Loading models...".

## Threading
One dedicated single-thread executor ("genie-llm"). Genie is not thread-safe. Every call: `nativeReset` then `nativeQuery`, so no history leaks between
extraction, update and rephrase calls. A rephrase timeout (1500 ms) stops waiting but cannot interrupt the native query (ADR-036; the Gemma backend CAN be interrupted, see below).

## Failure handling
Load/call exceptions surface as `MODEL_FAILURE` / `EXTRACTION_FAILURE` with Retry and "Switch to Mock AI". Native logs: tag `GenieNative`.

## Limits
Per-call sampler settings (rephrase temp 0.3, ~40 tokens) are not exposed by the JNI layer yet: all calls use temp 0.1 / top-k 1 and the model's stop token.
Prompt token counts are not exposed by Genie; the runtime logs prompt characters, prefill ms and decode ms (tag `GenieLlmEngine`).

## Swapping a model
Put the new bundle in `files/<dir>`, pass `modelDirName` to `GenieLlmEngine`, keep context-binary names in `genie_config.json`. A different tokenizer/chat format
needs new strings in `ai/Prompts.kt` (prompts are chat-format-neutral `ChatPrompt`s, rendered per engine: `ChatFormat.CHATML` / `GEMMA4`). A different SoC needs the matching `libQnnHtpVxxStub.so` / `libQnnHtpVxxSkel.so` in jniLibs.

## Whisper (ASR): whisper.cpp on the CPU. STOPGAP, NOT THE NPU
The board has no NPU Whisper model (DEVICE_SETUP.md section 6). So the real audio pipeline can run end to end, the app has a clearly labelled
**CPU stopgap**: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) v1.7.6 (ggml, CPU only), English forced, transcribe mode (never translate).
It is NOT the production ASR; the NPU Whisper stays a TODO (`TODO.md`) and replaces it behind the same `SpeechToTextService`.

- Source: vendored (trimmed, MIT licence kept) in `app/src/main/cpp/third_party/whisper.cpp`; `CMakeLists.txt` builds it as static libs (ggml CPU backend only: no
  OpenMP, BLAS, GPU backend, curl or network code; `-march=armv8.2-a+dotprod+fp16`, NEON; always `-O3`, also in Gradle debug builds) and links `libwhisper_jni.so`.
  `genie_jni` is untouched.
- JNI (`ai/whisper/WhisperNative`, `whisper_jni.cpp`): `long nativeCreate(String modelPath)`, `String nativeTranscribe(long h, float[] pcm16k, int threads, String language, String initialPrompt?)`
  (greedy, no timestamps, `no_context`, language fixed, `translate=false`), `void nativeFree(long h)`. whisper/ggml logs go to logcat tag `WhisperNative`.
- `ai/whisper/WhisperCppSpeechToText` implements `SpeechToTextService`: reads the WAV the app records (`WavPcm`: PCM16, any channel count averaged, resampled to 16 kHz if needed),
  one dedicated thread `whisper-cpp`, model loaded once (`load()`, ~0.9 s in the app for small.en), returns `Transcript(text, "en")` (confidence is always 1: whisper.cpp reports none here).
  Logs `asr_ms`, `audio_ms`, `rtf`, model, threads under tag `WhisperCpp`. Failure to load or run -> `SpeechToTextException` (error screen: Try again / Switch to Mock speech).
- Model: `files/whisper/ggml-small.en.bin` (external files dir first, then internal `files/`; `ggml-base.en.bin` is the smaller alternative). Push commands: DEVICE_SETUP.md section 7.
- Selection: developer menu "Speech-to-text" (`DevSettings.sttChoice`: Mock / Whisper CPU). Default (nothing chosen): Whisper CPU when Real AI is on and the model file exists, else Mock.
  Whisper loads once at app start off the main thread (the "Loading models..." state covers Qwen and whisper); `AppContainer.hasRealStt` is true when Whisper CPU is selected and loaded,
  which offers "Run eval set from audio". The typed transcript is ignored while Whisper CPU is selected.
- Optional place-name prompt (`DevSettings.whisperPlacePrompt`, dev-menu switch, off by default): initial prompt "Incident report. Places: conference room, server room, pantry, lab, canteen, Block B."

### Measured on the board (SM8750P, `WhisperCppBenchmarkTest`, samples/test-1..4.wav = eval cases E27..E30, 2 timed rounds after warm-up)
The reference transcripts come from a PC faster-whisper small run, so WER against them is approximate (small.en is the same model family as the reference: biased in its favour). WER is the app's `eval/Wer.kt`.

| Model | Threads | Prompt | WER corpus (round 0) | asr_ms median / max | RTF mean |
|---|---|---|---|---|---|
| base.en | 4 | off | 11.1 % | 1021 / 1066 | 0.17 |
| base.en | 4 | on | 22.2 % | 1169 / 1243 | 0.20 |
| small.en | 2 | off | 0.0 % | 5053 / 5177 | 0.86 |
| small.en | 4 | off | 0.0 % | 3662 / 3794 | 0.62 |
| small.en | 4 | on | 0.0 % | 3872 / 4020 | 0.66 |
| small.en | **6** | off | 0.0 % | **3052 / 3351** | 0.52 |
| small.en | 8 | off | 0.0 % | 3202 / 3772 | 0.52 |

Clips are 3.9 to 13.2 s. Latency is nearly flat in clip length (whisper always encodes a 30 s window), so short answers have RTF up to 0.9 with small.en. Model load: 85-155 ms (benchmark),
~0.9 s in the app at start.
base.en errors: "Windia/windia" for Vindhya, "three years" for "3 hours", "his snake", "Kalumba", "AC card fire". small.en got all four right (it only differs from the references in "three" vs "3", which the normaliser equates).
**Defaults chosen: small.en, 6 threads, prompt off.** The place prompt did not help on these samples (no change with small, worse with base: "snake that came in its ago") and costs about 0.2-0.3 s; it was measured only on 4 clips of campus names, not on the conference-room/pantry vocabulary it targets.
Not measured: other speakers, noise, accents, thermal throttling over a long session, concurrency with Qwen (they do not overlap in the dialogue: ASR finishes before the LLM call).


## Gemma 4 E2B via llama.cpp (JNI): LLM on the NPU, encoder on the GPU
Production model decision (ADR-044): **Gemma-4-E2B-it** (q4_0 GGUF + mmproj) with native audio, image and text input, run through the Snapdragon build of
llama.cpp (**b11552**, the one the user tested, `idk-test/MODELS_USAGE.md` sections 3, 5b, 5c). Qwen3-4B + Whisper stay as the other selectable Real AI backend.
**Status: written against the headers/libs of the board and compiled and linked on the host; no line of it has run on a device yet.** Everything below
under "Measured" is copied from the user's guide and was measured with `llama-server` there, not with this app.

### Why JNI and not `llama-server`
`llama-server` needs a loopback socket, and an Android app can only open sockets with the `INTERNET` permission. This app must stay fully offline with NO
INTERNET permission (the manifest has none), so the C API of `libllama.so` + `libmtmd.so` is called directly through JNI (ADR-044).

### Libraries (`app/src/main/jniLibs/arm64-v8a`, git-ignored; `tools/pull_llama_libs.sh [serial]`, read-only `adb pull` from `/data/local/tmp/models/llama_snap`)
| Library | Why |
|---|---|
| `libllama.so`, `libmtmd.so` | LLM and multimodal (audio/image) C APIs; the only ones `llama_jni.cpp` calls (plus `libggml-base.so` for device lookup). |
| `libggml.so` (0.4 MB shim), `libggml-base.so`, `libggml-cpu.so` | ggml core + CPU backend. |
| `libggml-hexagon.so` | NPU backend (device `HTP0`). Loads `libcdsprpc.so` (manifest `uses-native-library`, already there for Genie). |
| `libggml-htp-v79.so` | The **DSP-side** skel for HTP v79 (SM8750). Not loaded by the Android linker: FastRPC opens `file:///libggml-htp-v79.so?htp_iface_skel_handle_invoke` and searches `ADSP_LIBRARY_PATH` (read in `ggml-hexagon.cpp`, `ggml_hexagon_session` ctor). v73/v75/v81 are not shipped (the backend picks the arch it queries from the DSP). |
| `libggml-opencl.so` | GPU backend (device `GPUOpenCL`, Adreno 830). Needs the vendor `libOpenCL.so` (declared in the manifest, `required=false`). |
| `libggml-rpc.so` | NOT used (no sockets), but `libggml.so` and `libllama.so` list it in DT_NEEDED, so it must ship. |
Not shipped: `libllama-common.so` (67 MB; `libllama`/`libmtmd` do not need it), the `llama-*-impl.so` tool libraries, v73/v75/v81 skels.
`ADSP_LIBRARY_PATH` is set by `llama_jni.cpp` (`nativeCreate` / `nativeListDevices`) to `<nativeLibraryDir>;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp` (the same value as `genie_jni`,
so both backends find their skel: `libQnnHtpV79Skel.so` and `libggml-htp-v79.so` live in the same extracted directory; `useLegacyPackaging = true` was already set).

**Soname / DT_NEEDED check (done on the host, evidence in `AGENT_LOG.md`)**: `python tools/check_apk_libs.py` unpacks the APK and runs `llvm-readelf -d` on every
`lib/arm64-v8a/*.so`: all 20 sonames are unique and every DT_NEEDED resolves to another library of the APK or to `libc/libm/libdl/liblog/libandroid/libOpenCL/libcdsprpc`.
The llama libs link libc++ statically (no `libc++_shared.so` anywhere). Bug found and fixed on the way: the vendored whisper.cpp build produced its OWN shared
`libggml.so`/`libggml-base.so`/`libggml-cpu.so`/`libwhisper.so` (ggml's `option(BUILD_SHARED_LIBS)` defaults to ON on Android and the old `set(BUILD_SHARED_LIBS OFF)` was only a normal
variable), which collided by soname with llama.cpp's libs (an APK can hold one `libggml.so`; the other ggml version won and `System.loadLibrary("whisper_jni")` failed with
`library "libggml-rpc.so" not found`). Now `CMakeLists.txt` forces `BUILD_SHARED_LIBS=OFF` in the cache for the whisper subtree (ggml/whisper are static inside `libwhisper_jni.so`) and
`libwhisper_jni.so` / `libllama_jni.so` export only their `Java_*` symbols (`--exclude-libs,ALL`, `-fvisibility=hidden`). Qwen+Whisper and Gemma can load in ONE process.

### How the `llama-server` flags map to the C API (read from the b11552 sources: `common/arg.cpp`, `tools/mtmd/mtmd-cli.cpp`, `common/common.cpp`)
| Guide flag | `llama_jni.cpp` |
|---|---|
| `--device HTP0` | `ggml_backend_dev_by_name("HTP0")` -> `llama_model_params.devices = {dev, nullptr}` |
| `-ngl 99` | `llama_model_params.n_gpu_layers = 99` |
| `-mmdev GPUOpenCL` | `ggml_backend_dev_by_name("GPUOpenCL")` -> `mtmd_context_params.use_gpu = true`, `.device = dev` (`mmproj_use_gpu`/`mmproj_device` of `common_params`) |
| `-c 4096` | `llama_context_params.n_ctx = 4096`, `n_seq_max = 1`, `n_batch 2048` / `n_ubatch 512` (server defaults), flash attention AUTO, `swa_full = false` |
| `-t 6` | `n_threads = n_threads_batch = 6`, `mtmd_context_params.n_threads = 6` |
| `--reasoning off` | chat template rendered with `enable_thinking = false` (no `<|think|>` in the system turn): see `PROMPTS.md` "Gemma chat format" |
| `temperature: 0` | `llama_sampler_chain` with `llama_sampler_init_greedy()` |
| `--mmproj` | `mtmd_init_from_file(mmproj, model, params)`, warm-up on |
| `ggml_backend_load_all()` | called once (a no-op for this statically linked build; the server binaries call it too) |
Device names verified in the sources: `"HTP0"` (ggml-hexagon, `"HTP" + index`) and `"GPUOpenCL"` (`ggml_backend_opencl_device_get_name`). The LLM must NOT go to `GPUOpenCL`
(guide 5c: `clEnqueueNDRangeKernel error -54`); a missing device throws `RuntimeException` listing the devices ggml does see. `"none"` selects the CPU (`LlamaGemmaConfig`).

### JNI (class `com.example.test_app.ai.llama.LlamaNative`, `app/src/main/cpp/llama_jni.cpp`, `libllama_jni.so`)
```
String  nativeListDevices(String libDir)                  // sets ADSP_LIBRARY_PATH, inits ggml; "name|description|type|freeBytes|totalBytes" per line (expect HTP0, GPUOpenCL)
long    nativeCreate(String model, String mmproj, String libDir, String llmDevice, String mmprojDevice, int nCtx, int nThreads, int nGpuLayers)
String  nativeGenerate(long h, String prompt, int[] kinds, float[][] audio, byte[][] images, int[] imageDims, int maxTokens)
long[]  nativeLastStats(long h)   // [promptTokens, promptPositions, generatedTokens, prefillMs, decodeMs, stop(0 end of turn,1 max tokens,2 aborted), mediaChunks]
void    nativeAbort(long h)       // thread-safe: stores an atomic flag
void    nativeFree(long h)
```
`nativeGenerate` (every call is independent): clear the KV memory and reset the sampler -> build mtmd bitmaps (`mtmd_bitmap_init_from_audio` for 16 kHz mono float PCM,
`mtmd_bitmap_init` for tightly packed RGB; the i-th `<__media__>` marker of the prompt binds to `kinds[i]`) -> `mtmd_tokenize(add_special = true, parse_special = true)` (adds BOS; expands the marker to
`<|image>..<image|>` / `<|audio>..<audio|>`) -> refuse a prompt that does not fit `n_ctx` with `max_tokens` -> `mtmd_helper_eval_chunk_single` per chunk (media encode on the GPU + prompt decode on the NPU;
the abort flag is checked between chunks) -> greedy loop: sample, stop on end-of-generation (and on a literal `<turn|>`), `llama_token_to_piece(special = true)` so `<|channel>` blocks reach the Kotlin cleanup,
abort flag checked before every token, `llama_decode` of one token. Strings are converted UTF-16 <-> standard UTF-8 by hand (JNI uses modified UTF-8). All native logs go to logcat tag `LlamaNative`
(backend/device lines, load times, one `generate:` summary per call).

### Load sequence and threading
`LlamaGemmaEngine.load()` (single thread `llama-gemma`): find `gemma-4-E2B_q4_0-it.gguf` + `gemma-4-E2B-it-mmproj.gguf` in `files/gemma` (external files dir first, then internal; `DEVICE_SETUP.md` section 8) ->
`nativeCreate` (model onto HTP0, context, mmproj onto GPUOpenCL with a warm-up encode) -> ready. Called once at app start by `AppContainer.ensureModelsLoading()` off the main thread behind the existing
"Loading models..." state; Gemma needs no second model for speech (it replaces Whisper). Load time in the app: **not yet measured** (guide: ~5-6 s for `llama-server` with the NPU/GPU setup, `incident_report.json` `load_s`).
The llama context is not thread-safe: one executor thread serialises transcription, photo analysis, extraction, update and rephrase. Every call clears the KV cache first (no history leaks).
**Abort / timeout (fixes ADR-036 for this backend)**: cancelling the calling coroutine (for example the 1500 ms rephrase timeout, `withTimeoutOrNull`) makes `generate` call `nativeAbort` repeatedly until the native call returned
(it returns within one token, ~35 ms at 29 tokens/s; during a media prefill only between chunks), then rethrows the cancellation. The next call therefore never queues behind a zombie query.

### Failure handling
Missing files, unknown device, `mtmd_init_from_file` / `llama_model_load_from_file` failure, an oversized prompt and `llama_decode` errors all throw `RuntimeException` with the reason (also in logcat). A missing or
incompatible native library surfaces as `UnsatisfiedLinkError` (`LinkageError`), caught in `AppContainer.ensureModelsLoading()` and in the services. Model load failure -> `AiStatus.Failed` / `ModelLoadException` -> the existing
error screen with Retry and "Switch to Mock AI"; STT failures -> `SpeechToTextException` ("Switch to Mock speech"). A failed photo analysis is skipped (the report never depends on it).
Risk that cannot be checked without the device: `libggml.so` (DT_NEEDED) pulls `libggml-opencl.so` -> `libOpenCL.so`; if the app's linker namespace cannot resolve it, `System.loadLibrary("llama_jni")` fails for the whole backend
(then the message says so; the libs would have to be rebuilt without the OpenCL backend).

### Kotlin side (`ai/llama/`)
| Class | Role |
|---|---|
| `LlamaNative` | JNI declarations. |
| `LlamaGemmaEngine` / `LlamaGemmaConfig` | `LlmEngine` (text + audio + image). Defaults = the guide's command: `HTP0`, `GPUOpenCL`, ctx 4096, 6 threads, ngl 99, `files/gemma`. |
| `GemmaSpeechToText` | `SpeechToTextService`: the guide's instruction "Transcribe this audio exactly. Output only the transcript, nothing else.", greedy, 256 tokens, `GemmaText.cleanTranscript` (thought channel, turn tokens, label, quotes, whitespace). Audio cut at 90 s. Numbers are NOT rewritten ("a2" vs "a two" is a model/run difference; `eval/TextNorm` and `SpokenTime` treat digits and words alike). Confidence 1.0. |
| `GemmaImageAnalysis` | `ImageAnalysisService`: decode (`inSampleSize`), EXIF rotation, downscale to **<= 512 px** long side (`ImageScaling`), RGB -> one short sentence + up to 5 tags (JSON, forced prefix `{"description":`), 96 tokens. |
| `GemmaNarrationExtractor` | Optional ONE-call path (below). |
| `ImageScaling`, `GemmaText` | Pure maths / cleanup (JVM tests). |
Both backends are built on demand by `AppContainer` (`QwenStack` / `GemmaStack`); selecting one releases the other (the NPU is shared, only one backend is resident).

### Photos
Gallery photos (ADR-053; compressed to <= 512 px when picked): the photos are attached to the report AND, with the Gemma backend, `DialogueManager` describes up to 3 of them (`ImageAnalysisService`, 512 px) before the first extraction and passes the
`ImageHint`s in every `ExtractionRequest`; `Prompts.extraction(transcript, hints)` adds one rule and one `PHOTO: <description>` line to the user turn (without hints the prompt is unchanged). Qwen/Mock never get hints.
Measured by the user with `llama-server` (guide 5c): 512 px photo ~276 prompt tokens and 4.2 s end to end vs ~1279 tokens and 23.2 s at 2746x4032; detail is lost (the resized toilet photo was described as a bathtub of foam).

### Optional one-call narration (developer menu "Gemma: one-call narration", off by default)
Audio (+ up to 3 photos) -> ONE call that returns `transcript`, `image_description` and the 7 extraction keys (`Prompts.multimodalExtraction`, the guide's `bench_report.py` prompt shape with this app's keys,
forced prefix `{"transcript":`). The transcript goes to the dialogue as the STT result; the JSON is parsed by the existing `LlmOutputParser` and consumed by `LlmIncidentExtractionService` through
`PrecomputedFirstPass` when the first-pass extraction is requested for exactly that transcript (no second call). Follow-up answers use the normal STT and update prompts. An unreadable combined reply or an empty transcript falls
back to the dedicated transcription prompt. Not measured: the quality of the transcript in the combined call versus the dedicated prompt, and the app-level latency (guide: audio + photo -> JSON 7.0 s with `llama-server`).

### Measured by the user with `llama-server` (NPU + GPU; not by this app) - `idk-test/MODELS_USAGE.md` 5b/5c, `incident_report.json`
ASR 0.9-1.9 s per clip (CPU: 6-20 s), prefill 1482 tok/s on the NPU vs 178 on the CPU, decode ~29 tok/s, audio+photo -> JSON 7.0 s, photo -> JSON 8.4 s (4.2 s at 512 px), transcripts equal to the CPU run except number
formatting. Gemma is not a dedicated ASR model: expect paraphrase errors (e.g. "his snake" for "a snake" on the CPU run, "Wendya A2" for "Vindhya A2"). A one-off CLI run without `--reasoning off` gave a worse transcript: spot-check accuracy.
Per-call numbers inside this app: **not yet measured** (the app logs them: tags `LlamaNative`, `LlamaGemmaEngine`, `GemmaStt`, `GemmaImage`, `GemmaNarration`).
