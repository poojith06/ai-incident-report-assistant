# Evaluation scripts

These scripts talk to the QIDK board over `adb`. `eval.ps1` and `tests.ps1` call `.\adb`,
so copy them (with `testset.json`) into your Android Platform-Tools folder, next to `adb.exe`, and run from there.

| File | Purpose |
|---|---|
| `eval.ps1` | Runs `testset.json` on the board and scores raw vs. with-rules output; writes `results_<model>_<timestamp>.csv` |
| `testset.json` | Labelled test cases (clean, messy, missing info, non-incident, Hinglish, ASR noise, negation) |
| `tests.ps1` | Quick helpers: `. .\tests.ps1` then `Test-Qwen "<text>"` / `Test-Update ...` |
| `whisper_test.py` | Generates TTS clips (Telugu, Hindi, Indian English) and runs Whisper-Small transcribe vs. translate |

```powershell
powershell -ExecutionPolicy Bypass -File .\eval.ps1 -Model qwen -Limit 3   # quick check
powershell -ExecutionPolicy Bypass -File .\eval.ps1 -Model qwen            # full run
powershell -ExecutionPolicy Bypass -File .\eval.ps1 -Model llama           # compare with Llama 3.2 1B
```

`whisper_test.py` needs `pip install edge-tts openai-whisper`; it writes clips to `audio_tests/` and output to `whisper_results.txt`.
Past outputs are in `../results/`. The Kotlin rule layer and its self-check are in `../rules/`.
