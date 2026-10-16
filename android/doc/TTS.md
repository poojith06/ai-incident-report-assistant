# Offline text-to-speech: Sherpa (Piper + Rasa)

The app can speak with sherpa-onnx instead of the Android `TextToSpeech`. The QIDK has no Android TTS engine installed, and the Android engine path is English only. Choice and reasons: ADR-059.

## What speaks what
| Text | Model | Notes |
|---|---|---|
| Latin script (English) | Piper `en_US-amy-medium`, int8, 22.05 kHz, espeak-ng phonemes | one speaker |
| Telugu, Tamil, Kannada, Malayalam, Assamese / Bengali script, Punjabi, Devanagari (Hindi, Marathi, Nepali, Bodo, Dogri, Maithili, Sanskrit) | AI4Bharat VITS Rasa, int8, **24 kHz**, character frontend | speaker id + emotion id, no language input |
| Odia, Gujarati | Android TTS (fallback) | the Rasa model has no trained speaker for them (measured: garbage with 5-6 borrowed speakers) |
| Arabic, Cyrillic, CJK, anything else | Android TTS (fallback) | |

Rasa facts that differ from the Hugging Face card of the sherpa export (which says "13 languages incl. gu hi or mni"): the upstream model card (`ai4bharat/vits_rasa_13`) lists 13 languages as, bn, brx, doi, kn, mai, ml, mr, ne, pa, sa, ta, te and **20 trained speakers** (ids 0-19, one language each: ASM_F 0, ASM_M 1, BEN_F 2, BEN_M 3, BRX_F 4, BRX_M 5, DOI_F 6, DOI_M 7, KAN_F 8, KAN_M 9, MAI_M 10, MAL_F 11, MAR_F 12, MAR_M 13, NEP_F 14, PAN_F 15, PAN_M 16, SAN_M 17, TAM_F 18, TEL_F 19). The other speaker ids up to 1023 exist in the embedding table but are not documented. Styles (emotion ids): ALEXA 0, ANGER 1, BB 2, BOOK 3, CONV 4, DIGI 5, DISGUST 6, FEAR 7, HAPPY 8, NEWS 10, SAD 12, SURPRISE 14, UMANG 15, WIKI 16. The sample rate is 24000, not 22050 (the ONNX metadata says so; the code uses the rate sherpa reports).

Voice choice is data: `SherpaVoices` (pure Kotlin, JVM-tested) maps text to plans, one per script run (below). Script comes from `LanguageDetect.detect`; between the languages of one script the speech model's own language name (`TextToSpeechService.speak(text, language)`, passed by `TtsSpeechOut` from the dialogue language, e.g. "assamese") decides, else the script's first language (Devanagari = Hindi, Bengali script = Bengali). Hindi has no speaker of its own: it uses the Marathi female voice (speaker 12), see results.

## Architecture
| Piece | File |
|---|---|
| Kotlin API (verbatim copy of sherpa-onnx v1.13.8 `Tts.kt`; the JNI looks classes and fields up by name) | `com/k2fsa/sherpa/onnx/Tts.kt` |
| Routing, Rasa text preparation, speed mapping (pure) | `ai/tts/SherpaVoices.kt` (`VoicePlan`, `RasaVoiceChoice`) |
| `SherpaModelFiles` (where the files are), `SherpaSynthesizer` (lazy model load, streaming synthesis, timings), `SherpaTtsService` (`TextToSpeechService`: one worker thread, `AudioTrack` float PCM stream, stop in about 150 ms, falls back per text) | `ai/tts/SherpaTtsService.kt` |
| Engine choice | `AppContainer.effectiveTts()`, forwarding `textToSpeech`; `DevSettings.ttsEngine` (null = Sherpa when the model files are on the device, else System), `rasaSpeaker`, `rasaEmotion` |
| Developer menu | "Text-to-speech engine": System / Sherpa (Piper + Rasa), Rasa speaker id and emotion id fields (Sherpa only) |

Behaviour: an utterance is split into script runs and every run is spoken by its own voice (next section); models load on the first use of each (Piper about 0.9 s, Rasa about 0.5 s), stay loaded, and are freed by `shutdown()` (the view model calls it when it is cleared). `stop()` only stops playback. Synthesis runs on one background thread; each audio piece goes to the `AudioTrack` as soon as sherpa produces it, so sound starts after the first sentence. `speak` suspends until the audio has played. The Speech rate setting is the sherpa `speed` (`SherpaVoices.speed`: 0.8 / 1.0 / 1.25, bounded 0.5 to 2.0). If a model's files are missing or fail to load, or the text's language has no voice, `SherpaTtsService` hands that text to the Android TTS service it was given (`AppContainer` passes `AndroidTextToSpeechService`); the Android service then reports its own `TtsUnavailableException` (banner) when the device has no engine.

Rasa text preparation (`SherpaVoices.rasaChunks`): sherpa splits on `.` `?` `!` and a trailing short sentence can synthesize as noise (model README). The app therefore splits sentences itself (an ender followed by whitespace; the Devanagari danda too), strips sentence-final and edge punctuation from every chunk, drops dots inside a word ("K.R." becomes "KR", "3.5" becomes "3 5"), joins a chunk of fewer than 6 sound-carrying code points (letters and vowel signs) to the next one, and puts 220 ms of silence between chunks. One chunk is one sherpa call, so the first chunk plays while the next is synthesized. Piper gets the text as it is (whitespace collapsed); espeak handles its punctuation.

JNI pitfall: the callback passed to `generateWithCallback` must be a class that implements `(FloatArray) -> Int`, not a Kotlin lambda. The native code looks up `invoke([F)Ljava/lang/Integer;`; indy lambdas do not have it and the process aborts (`NoSuchMethodError` inside JNI). See `SherpaSynthesizer.TimedSink`.

## One utterance, several voices (ADR-060)
Mixed text is normal now: a Hindi sentence that contains an English place name ("... KRB washroom, third floor में ..."), the reference id ("I N C" inside a Telugu sentence), "K R B" in a Telugu line. `SherpaVoices.scriptRuns` splits the text into runs of one script (Latin / non-Latin; digits, spaces and punctuation stay with the run before them), `SherpaVoices.segments` plans every run (Latin -> Piper; the non-Latin run -> Rasa with the speaker of `LanguageNames.forText(run, hint)`; the hint, the language code of the app's line, only matters for the non-Latin runs), and `SherpaTtsService` plays the segments one after another through ONE `AudioTrack` (the rate of the first segment; the other voice is resampled linearly 22.05 <-> 24 kHz). Pause after a run: 280 ms when it ends a sentence, 140 ms for a comma / colon, 40 ms for a plain voice change. If any run has no voice (a script the models do not cover) the whole utterance goes to the Android TTS. The AudioTrack buffer is long (30 s on API 31+) so the write of a finished sentence returns at once and the next sentence is synthesized while the previous plays. **A stream track does not start playing before its buffer is full unless a start threshold is set** (measured on the QIDK, `AudioTrackProbeTest`: 30 s buffer with 2 s of audio, playbackHeadPosition stayed 0 for 3 s; 4 s and 8 s buffers likewise; 1 s and 2 s buffers started only because 1-2 s of audio filled them; with `startThresholdInFrames` = 100 ms the 30 s buffer plays at once). The first version of this paragraph's 30 s buffer had no threshold and silenced every spoken line (ADR-061); `PcmBufferPolicy` now sets the threshold on API 31+ and on API 30 (no threshold API) uses a 1 s buffer and pads the end of each utterance with silence. `SherpaTtsService` logs `playback finished head=... written=...` per utterance and warns when the head is 0.

What the app now speaks per language (`doc/DIALOGUE.md` "Languages"): hi and te have their own questions, read-back, closing line, form read-out and confirmation (language packs); every other language gets the English lines in the English voice. The Review / Success screens' text stays English.

### Spoken lines per language and engine (device session, `LocalizedSpeechTest`, tags `TTSLOC` + `SherpaTts`)
| Session language | Pack | Line | Segments (engine / language / speaker) |
|---|---|---|---|
| hindi | hi | read-back "दर्ज किया: पानी का रिसाव, KRB washroom, third floor, ..." | Rasa hi sid 12, Piper en ("KRB washroom, third floor,"), Rasa hi |
| hindi | hi | Review read-out "आपकी रिपोर्ट तैयार है। ... {location} में, अभी-अभी। गंभीरता मध्यम है। ..." | Rasa hi, Piper en, Rasa hi |
| hindi | hi | Success "रिपोर्ट सबमिट हो गई। संदर्भ संख्या I N C, दो शून्य दो छह, ..." | Rasa hi, Piper en ("I N C,"), Rasa hi |
| telugu | te | opening, question "మీరు ఏమి చూశారు?" | Rasa te sid 19 |
| telugu | te | read-back, Review read-out, Success (same shapes as Hindi) | Rasa te, Piper en, Rasa te |
| tamil | none -> en | opening, question, read-back, read-out, Success, all English | Piper en |
| english | en | read-back, read-out, Success | Piper en |

Round trip of the rendered lines (Gemma STT with the language forced, word match to the spoken text; the English run is transcribed in the script of the language, so lines with a location score lower: "KRB" came back as "के आर पी" / "కేర్ బీ"):
| Line | Hindi | Telugu |
|---|---|---|
| question (location) | 1.00 | 0.79 |
| safety check | 1.00 | 0.90 |
| Review read-out without a location | 0.89 | 0.95 |
| Review read-out with the English location | 0.71 | 0.75 |
| read-back with the English location | 0.70 | 0.66 |
| Success with the reference id | 0.93 | 0.69 |
The spelled times and digits were understood ("सुबह 10:00 बजे", "शाम 3:30 बजे", Hindi digits one by one); the weakest Telugu results are the start of the Success line and "తీవ్రత" (heard as "అడ్జస్ట్" once).

## Install
1. Libraries (once per checkout; `app/src/main/jniLibs` is git-ignored): `sh tools/pull_sherpa_libs.sh` downloads the v1.13.8 Android prebuilt and copies `libsherpa-onnx-jni.so` (4.7 MB) and `libonnxruntime.so` (22 MB, arm64-v8a). The release includes emotion_id support (sherpa-onnx PR 3849): the JNI `GenerationConfig.extra["emotion_id"]` reaches the model. No clash with the existing libraries (`python tools/check_apk_libs.py`).
2. Models on the board: `doc/DEVICE_SETUP.md` section 9 (`adb push`, then `run-as cp` into `files/tts/piper-amy` and `files/tts/rasa`). Files read: `piper-amy/{en_US-amy-medium.onnx, tokens.txt, espeak-ng-data/}` and `rasa/{indic_model_int8.onnx (else model.onnx), tokens.txt}`.
3. Developer menu (long-press the Home title) -> "Text-to-speech engine". With the files on the device Sherpa is the default.

## Device test and round trip
Build and run (never `connectedAndroidTest`, it wipes the models): `./gradlew.bat :app:installDebug :app:installDebugAndroidTest`, then
```sh
adb -s <serial> shell am instrument -w -e class com.example.test_app.ai.tts.SherpaTtsTest com.example.test_app.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> logcat -d -s TTSLAB SherpaTts          # one line per clip: engine, language, load_ms, synth_ms, audio_s, rtf, peak, rms, silent, text
```
`SherpaTtsTest` writes the WAVs to `files/tts_out/`. Pull them (adb-shell-created folders are not writable for the app, so read through `run-as`):
```sh
mkdir -p build/tts/out
for f in $(adb -s <serial> shell "run-as com.example.test_app ls files/tts_out" | tr -d '\r'); do adb -s <serial> exec-out "run-as com.example.test_app cat files/tts_out/$f" > build/tts/out/$f; done
```
Intelligibility (nobody has listened to the audio) is checked by round trip: English with faster-whisper on the PC (`build/tts/roundtrip_en.py`, `build/asr` environment), the Indic clips with the Gemma speech model on the board, language forced from the file name (`SherpaTtsRoundTripTest`, logs `TTSRT`; `WavPcm` resamples 24 kHz to 16 kHz), compared with the source text by word-level `difflib` ratio (`build/tts/roundtrip_indic.py`, scratch scripts, not in git).

## Results (QIDK, Snapdragon 8 Elite, CPU, 4 threads, 2026-10-11)
Load = first synthesis on a fresh process (reading the ONNX file). RTF = synth time / audio time. Match = word-level similarity of the transcript to the source text (1.00 = identical after normalizing case and punctuation; Gemma and Whisper are not perfect listeners, so 0.8-0.9 is mostly fine). Peak / RMS / near-silent (|x| < 0.01) show non-empty audio in every case: peak 0.29-0.75, RMS 0.046-0.126, near-silent fraction 0.23-0.43.

| Clip | Engine (speaker) | Load ms | Synth ms | Audio s | RTF | Round trip (match) |
|---|---|---|---|---|---|---|
| en_1 fire, K R B, severity | Piper | 908 | 741 | 5.31 | 0.14 | 1.00 (faster-whisper small) |
| en_2 greeting | Piper | - | 314 | 2.07 | 0.15 | 1.00 |
| en_3 safe? | Piper | - | 260 | 1.89 | 0.14 | 1.00 |
| en_4 read-back | Piper | - | 993 | 7.14 | 0.14 | 0.93 |
| en_5 review read-out | Piper | - | 1688 | 12.45 | 0.14 | 0.95 |
| en_6 submitted + id | Piper | - | 1413 | 10.24 | 0.14 | 0.93 |
| te_1 fire / send help | Rasa (19 TEL_F) | 508 | 5611 | 5.59 | 1.01 | 0.81 (Gemma STT, Telugu forced) |
| te_2 K.R. building water leak | Rasa (19) | - | 2939 | 2.63 | 1.12 | 0.90 |
| ta_1 | Rasa (18 TAM_F) | - | 5326 | 5.31 | 1.00 | 0.66 (characters 0.87) |
| hi_1 | Rasa (12 MAR_F) | - | 3421 | 3.61 | 0.95 | 1.00 |
| as_1 | Rasa (0 ASM_F) | - | 3633 | 3.83 | 0.95 | 0.90 |
| kn_1 | Rasa (8 KAN_F) | - | 5646 | 5.66 | 1.00 | 0.98 |
| ml_1 | Rasa (11 MAL_F) | - | 4538 | 4.44 | 1.02 | 0.78 |
| or_1 | Android TTS | - | - | - | - | not synthesized by sherpa |

On the English side the same Gemma check gives 1.00 for en_2, en_3, en_5 (character match 0.98-1.00 for all six); faster-whisper is the reference for English.

Rasa speakers and emotions (same Telugu sentence, `rasaSpeakersAndEmotions`): all 15 combinations of speaker 0, 1, 19, 100, 500 and emotion 0, 1, 5 load and give non-silent audio (peak 0.40-0.73, near-silent 0.23-0.38). Round trip: mean match 0.87, minimum 0.76. Speaker 19 (TEL_F) is best (0.93 / 1.00 / 0.88 for emotions 0 / 1 / 5), speakers 0 and 1 (Assamese voices) read Telugu well (0.79-1.00), the undocumented speakers 100 and 500 are intelligible too (0.76-0.88, louder: peak 0.64-0.73). All emotion ids tried worked (emotion changes the speaking style; the check cannot say how it sounds).

Speakers for languages without one (`speakersForLanguagesWithoutOne`, Gemma round trip): Hindi sentence with speaker 12 (Marathi F) 1.00, 6 (Dogri F) 0.97, 10 (Maithili M) 1.00, 14 (Nepali F) 1.00, 17 (Sanskrit M) 0.89, 4 (Bodo F) 0.79 -> default 12. Odia with speakers 0, 2, 3, 8, 12, 19: 0.00-0.24, garbage; Gujarati with 0, 2, 12, 15, 19: 0.00-0.16, garbage (for speaker 12 Gemma's transcript degenerated into one repeated syllable) -> not served by Rasa.

Thread count (`threadScaling`): the int8 Rasa model does not get faster with more threads (RTF 0.98 / 1.05 / 1.01 / 0.99 for 1 / 2 / 4 / 6 threads); Piper 0.15 at every count. Default is 4. Diagnostic with the fp32 `model.onnx` (123 MB): RTF 0.69 with 1 thread and 0.48 with 4 threads, i.e. the int8 file is about twice as slow here (dynamic-quantized convolutions run slowly in onnxruntime on this CPU) while 83 MB smaller. To use fp32, copy `model.onnx` into `files/tts/rasa` and delete `indic_model_int8.onnx` (`SherpaModelFiles` takes the first of the two).

Service on the device (`serviceSpeaksStopsAndFallsBack`): English and Telugu speak through the `AudioTrack` and return after the audio; Arabic goes to the fallback service; `stop()` returns in 159 ms.

## Known issues
- Localized lines (hi, te) are AI translations marked `needs_native_review`; the Review / Success text stays English; other languages hear English (no pack).
- A voice change inside a sentence (Hindi -> English place name -> Hindi) is audible: two different voices, 40-280 ms pause, and the English run is read with an American accent.
- Rasa runs at RTF about 1.0: a long read-out (about 14 s of Telugu) takes about 10-12 s to synthesize, so the next sentence is ready just in time and short gaps between sentences are possible (fp32 would remove them, see above).
- **Audio was never listened to.** Everything above is machine round trip plus amplitude statistics. Naturalness, accent, loudness and the sound of the `AudioTrack` stream on the loudspeaker are unverified.
- Rasa runs at about real time (RTF 1.0, int8): the first sound of an Indic read-out comes about 2.5 s after the request, later sentences keep up. fp32 would halve that (see above).
- Odia and Gujarati are spoken by the Android TTS (which has no engine on the QIDK: the existing banner appears). The developer-menu speaker field can force any speaker for them, but the output is not intelligible.
- Numbers, abbreviations, dates and names are not normalized for the Rasa character frontend ("9:30 a.m." is read as characters); Piper (espeak) reads them better. English read-outs speak "K R B" and reference ids letter by letter on purpose (`FormReadout.speakableId`).
- Hindi, Marathi and Nepali share the Devanagari script: without a language name from the speech model the text is read with the Hindi (Marathi female) voice; Assamese versus Bengali likewise needs the language name.
- Tamil (0.66) and Malayalam (0.78) round trips are the weakest; Gemma STT itself is weaker on them, so this does not separate model error from listener error.
- Rasa output is mildly non-deterministic (noise scale 0.667 / 0.8); the same sentence read back 0.81 and 0.93.
- Both Rasa and Piper loaded models stay in memory (about 60-150 MB) until `shutdown()`.
- Meetei Mayek (Manipuri), Urdu and other scripts are not covered (fallback).
