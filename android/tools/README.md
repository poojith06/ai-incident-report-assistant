# tools

## count_tokens.py

Counts the real Qwen tokens of the worst-case prompts (90-word transcript) and checks the hard caps
(extraction 2000, update 1500, rephrase 500; targets 1200 / 800 / 300).

1. Render the prompts (the Kotlin `Prompts` object is the only template source):
   `./gradlew :app:testDebugUnitTest --tests "com.example.test_app.ai.PromptBudgetTest"`
   writes `app/build/rendered_prompts/{extraction,update,rephrase}.txt`.
2. Get the tokenizer from the phone (read-only pull):
   `adb -s <serial> pull /data/local/tmp/qwen4b/tokenizer.json <tmp>/tokenizer.json`
3. `pip install tokenizers`, then:
   `python tools/count_tokens.py --tokenizer <tmp>/tokenizer.json [--prompts app/build/rendered_prompts]`

Prints a table (prompt, tokens, target, hard cap). Exit code 0 = within hard caps, 1 = a hard cap exceeded,
2 = rendered prompts, tokenizer file or the `tokenizers` package missing.

## eval_report.py

Adds an evaluation run to `doc/EVAL_RESULTS.md`, newest first: date, device, model versions, git commit hash, one table per metric (ASR/WER, extraction, mapping, dialogue, rephraser, latency, prompt size) and a per-case table.
Input is the `results.json` that the app's developer menu "Run eval set" writes to `files/eval_runs/<run id>/` (pull with `adb exec-out run-as com.example.test_app cat files/eval_runs/<run id>/results.json > results.json`).

`python tools/eval_report.py results.json [--doc doc/EVAL_RESULTS.md] [--commit <hash>] [--device <name>] [--date YYYY-MM-DD] [--note "speaker B"] [--dry-run] [--force]`

Exit code 0 = added (or printed), 1 = unreadable / not an `eval_results/1` file, 2 = that run id is already in the document. Only numbers present in `results.json` are printed. See `eval/README.md`.

## pull_llama_libs.sh
`tools/pull_llama_libs.sh [serial]`: read-only `adb pull` of the llama.cpp b11552 Snapdragon libraries (libllama, libmtmd, libggml*, libggml-htp-v79) into `app/src/main/jniLibs/arm64-v8a` (git-ignored) and the headers into `app/src/main/cpp/third_party/llama/include`. See `doc/DEVICE_SETUP.md` section 8.

## check_apk_libs.py
`python tools/check_apk_libs.py [apk] [--readelf <llvm-readelf>]`: host-only check of every `lib/arm64-v8a/*.so` of the APK: unique SONAMEs, every DT_NEEDED resolved by another library of the APK or by an allowed system library. Exit 1 on a problem. Run it after `assembleDebug`.

## gguf_meta.py, gemma_render_reference.py
`gguf_meta.py <partial.gguf> [--key tokenizer.chat_template]` reads GGUF metadata from the first bytes of a model file (pull them with `adb exec-out "head -c 60000000 <file>"`).
`gemma_render_reference.py` renders reference prompts with the Gemma 4 jinja template (`pip install jinja2`) into `app/src/test/resources/chat_golden/gemma4.json`, which `ChatFormatTest` compares `ChatFormat.GEMMA4` against (`doc/PROMPTS.md`).
