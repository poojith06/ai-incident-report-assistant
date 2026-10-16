# Research

Work done alongside the app: the first rule-layer prototype and its evaluation, the severity framework, and a
multilingual benchmark.

| Folder | Contents |
|---|---|
| `rules_and_evaluation/` | `rules/IncidentRules.kt`: prototype rule layer (prompts, tolerant JSON parser, grounding rules, follow-up manager) and `IncidentRulesCheck.kt` (21 self-checks). `eval/`: 35-case labelled test set (7 categories) and the scripts that ran it on the QIDK. `results/`: Qwen3-4B and Llama 3.2 1B runs, Whisper notes |
| `severity_classifier/` | 5-tier severity framework (from 8 studies), mapping rulebook (JSON and Markdown), 100 labelled narratives, design notes on urgency and duplicate detection |
| `benchmarking/` | Office-incident benchmark: 30 cases × 6 languages (English, Hindi, Assamese, Odia, Telugu, Tamil), 180 narrations with gold type, severity, urgency and known traps. No model results are stored yet |

The Android app has its own parser and mapper (`android/app/src/main/java/com/example/test_app/ai/`); the prototype
here is not compiled into the app. The `eval/` scripts talk to the board over adb: copy them next to `adb.exe`
(see `rules_and_evaluation/eval/README.md`).
