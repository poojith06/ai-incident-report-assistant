# Prompts

All LLM input of the app. Code: `ai/Prompts.kt` (single source of truth; the prompts are chat-format-neutral `ChatPrompt`s, rendered as ChatML for Qwen below
and as Gemma 4 turns in "Gemma chat format"), rendered by `PromptBudgetTest` (ChatML) into
`app/build/rendered_prompts/*.txt`, measured by `tools/count_tokens.py` with Qwen's own `tokenizer.json`
(`/data/local/tmp/qwen4b/tokenizer.json` on the board, pulled to a temp path; `tokenizers` 0.23.3). Tokens include the
chat-template tokens (`<|im_start|>`, `<|im_end|>`, newlines).

**Status of the text:** the extraction and update prompts are the *interim prototype prompts* copied verbatim from the board
(`/data/local/tmp/qwen4b/eval_prompt.txt` and `upd_test.txt`). The team's `IncidentRules.kt` was not provided; when it arrives it
replaces the text in `ai/Prompts.kt` (keep `Prompts.extraction/update/rephrase`, the prefix constants, and re-run the budget checks).
The rephrase prompt is the spec v2 section 8.2 text.

## Budget (build prompt section 4)

Rendered with the worst case: a 90-word transcript (extraction), a fully populated CURRENT_FORM (long values are cut to 160 characters)
and a 90-word answer (update), a 90-word transcript so far (rephrase; trimmed to the last 60 words).

| Call | Measured tokens (Qwen tokenizer) | Target | Hard cap | Status |
|---|---|---|---|---|
| Extraction | 462 | <= 1200 | 2000 | ok |
| Update | 458 | <= 800 | 1500 | ok |
| Rephrase | 232 | <= 300 | 500 | ok |

Also enforced in plain unit tests without a tokenizer: `PromptBudgetTest` (1 token per 3 characters, conservative; the rephrase
target is not asserted there because the estimate is too pessimistic, only its hard cap). To re-measure:

```bat
gradlew.bat testDebugUnitTest --tests "com.example.test_app.ai.PromptBudgetTest"
adb -s 1d9c8f40 pull /data/local/tmp/qwen4b/tokenizer.json %TEMP%\qwen_tokenizer.json
python tools\count_tokens.py --tokenizer %TEMP%\qwen_tokenizer.json
```

`count_tokens.py` exits 1 over a hard cap, 2 when the rendered prompts are missing. Prefill and decode times per call are logged at
runtime (`GenieLlmEngine` logcat tag `GenieLlmEngine`: `promptChars`, `prefillMs`, `decodeMs`; the dialogue JSONL log carries
`prefill_ms` / `decode_ms` for rephrase calls). No device timings are recorded in this file: none were measured by the author of this change.

## Rules shared by all calls
- Every call starts a fresh conversation (`GenieLlmEngine.generate` resets the dialog, `LlamaGemmaEngine` clears the KV cache); no history leaks.
- Thinking is off: extraction and update end with a forced assistant prefix (`{"incident_type":` / `{`) so Qwen3 never opens `<think>`;
  the rephrase prompt ends with the empty think block `<think>\n\n</think>\n\n`. `LlmOutputParser` still strips `<think>...</think>`
  (also unclosed) and `<|im_end|>` from any output.
- Deterministic sampler (temperature 0.1, top-k 1) from the engine config for extraction/update; the rephrase call uses the same
  engine config (per-call sampler settings are not exposed by `GenieLlmEngine`; max length is bounded by the 1500 ms timeout in `LlmRephraser`).
- User text is sanitised (`Prompts.sanitize`): chat-template tokens and `<think>` tags are removed and whitespace is flattened.
- The model's reply continues after the prefix; `LlmOutputParser.parseObject(raw, prefix)` prepends it (and tolerates a model that repeats it).

## 1. Extraction (first pass)

`Prompts.extraction(transcript)`. Few-shot, three examples, the final user turn is the narration. Model keys: `incident_type, severity,
location, datetime, persons_involved, actions_taken, description` (mapped to report fields by `ModelOutputMapper`; `injury` is derived by
rules, never invented). Verbatim, rendered with a 90-word transcript:

```text
<|im_start|>system
Extract incident report fields as JSON. Use null for anything not said. Never invent details.
Copy location, datetime and persons_involved using the user's exact words.
incident_type: Water Leak, Fire, Injury, Accident, Equipment Failure, Electrical Hazard, Gas Leak, Spill, Security, Other, or null if no incident. If a person got hurt, use Injury.
severity: Critical (deaths, many injured, emergency needed), High (anyone hurt, fire, gas), Medium (contained or isolated), Low (minor), or null if no incident.<|im_end|>
<|im_start|>user
There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.<|im_end|>
<|im_start|>assistant
{"incident_type": "Water Leak", "severity": "Medium", "location": "Conference Room 2", "datetime": "around 10 AM", "persons_involved": null, "actions_taken": "Maintenance isolated the area", "description": "Water leak near Conference Room 2, area isolated."}<|im_end|>
<|im_start|>user
uh a worker cut his hand on the machine in the workshop just now<|im_end|>
<|im_start|>assistant
{"incident_type": "Injury", "severity": "High", "location": "workshop", "datetime": "just now", "persons_involved": "a worker", "actions_taken": null, "description": "A worker cut his hand on a machine in the workshop."}<|im_end|>
<|im_start|>user
Good morning, is this working?<|im_end|>
<|im_start|>assistant
{"incident_type": null, "severity": null, "location": null, "datetime": null, "persons_involved": null, "actions_taken": null, "description": null}<|im_end|>
<|im_start|>user
technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt<|im_end|>
<|im_start|>assistant
{"incident_type":
```

## 2. Update (one follow-up answer or a correction)

`Prompts.update(currentForm, asked, user)`. Only fields that are non-empty appear in CURRENT_FORM (no confidences, no history). `ASKED`
is the model key of the question (`location`, `datetime`, `actions_taken`, `persons_involved`, `description`) or `none` for a free
correction. The yes/no and injury questions (`injury`, `injured_person`, `injury_type`) do **not** call the LLM: `ModelOutputMapper` parses them.
Verbatim, rendered with a long form and a 90-word answer:

```text
<|im_start|>system
You update an incident report form. You get CURRENT_FORM, the field the app ASKED about, and what the USER said.
Return JSON with ONLY the fields the user gave or corrected, in English. Never repeat unchanged fields. Never invent details. If the user gave nothing useful, return {}.<|im_end|>
<|im_start|>user
CURRENT_FORM: {"incident_type": "Water Leak", "location": null, "datetime": "around 10 AM"}
ASKED: location
USER: uh it's in block B second floor near the lift<|im_end|>
<|im_start|>assistant
{"location": "block B second floor near the lift"}<|im_end|>
<|im_start|>user
CURRENT_FORM: {"incident_type": "Fire", "location": "Room 2", "datetime": null}
ASKED: datetime
USER: actually it was room 3 not room 2, and it started like 5 minutes ago<|im_end|>
<|im_start|>assistant
{"location": "room 3", "datetime": "5 minutes ago"}<|im_end|>
<|im_start|>user
CURRENT_FORM: {"incident_type": "Water Leak", "location": "Conference Room 2 second floor block B near the lift", "datetime": "2026-10-11T10:00:00+05:30", "persons_involved": "Ravi (injured), Sam, the security guard", "actions_taken": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "description": "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy"}
ASKED: location
USER: technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt<|im_end|>
<|im_start|>assistant
{
```

## 3. Rephrase (one question, optional)

`Prompts.rephrase(request)` = spec v2 section 8.2 as the system turn. Verbatim for `field = injury_type`, `mode = CARE`:

```text
<|im_start|>system
You rewrite ONE follow-up question for a voice incident-report assistant.
Rules:
- Ask exactly one question, about the field: injury_type. Nothing else.
- Tone: warm and gentle; the user may be hurt or upset; make clear they can skip.
- 20 words or fewer. End with a question mark.
- You may refer to something the user already said.
- This question is asked only once. Never say you are asking again or that an answer is needed.
- Never ask "why". Never blame. Never give medical advice. Never promise help.
- Output only the question text. No quotes, no explanation.
Template to adapt: "What kind of injury was it?"
User's words so far: "technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt technician reported water leaking near the second floor corridor around ten and nobody was hurt"<|im_end|>
<|im_start|>user
Write the question now.<|im_end|>
<|im_start|>assistant
<think>

</think>


```

The result is cleaned by `LlmOutputParser.cleanLine`, then must pass `PhraseValidator` (one `?`, <= 20 words, no blocked phrase,
no "why", mentions the field) within 1500 ms or the template is spoken. See `DIALOGUE.md`.

## Replacing the prompts (when `IncidentRules.kt` arrives)
1. Put the new text in `ai/Prompts.kt`, keep the function signatures and the prefix constants (or update `LlmOutputParser` calls).
2. Run `ai/*` unit tests (parser fixtures in `app/src/test/resources/llm_outputs/`), the scenario tests, `PromptBudgetTest`, `tools/count_tokens.py`.
3. Do not silently rewrite them: record the new token counts here.

## Measured latency on the QIDK (Qwen3-4B, Genie, HTP v79)
Measured by `GenieLlmBenchmarkTest` (androidTest, production prompts from `ai/Prompts.kt`), 2026-10-10, board `Sun_for_arm64` (SM8750P). Model load: 3.1-3.7 s.
`prefill` = time to first generated chunk; `decode` = remaining generation time. Genie does not expose token counts, so characters are given.

| Call | Prompt chars | Prefill ms (median / p90) | Decode ms | n |
|---|---|---|---|---|
| Extraction (8 transcripts x 3 rounds) | ~1,660 | 758 / 1,028 | 6,859 / 7,668 (~60 output tokens) | 24 |
| Update ("it's in block B second floor near the lift") | 1,053 | ~499 | ~1,576 | 3 |
| Rephrase | 791 | ~505 | ~1,177 | 3 |

Observation: decode dominates extraction (about 7 s for the 7-key JSON). The rephrase call (about 1.7 s end to end) exceeds the 1,500 ms budget in `LlmRephraser`, so with the
current model and sampler it will usually time out and fall back to the template: see TODO.md. Raw outputs of the 8 extraction transcripts are saved as
`app/src/test/resources/llm_outputs/captured_qwen_extract_*.txt`.


## Gemma chat format (Gemma 4 E2B through llama.cpp)
The template is `tokenizer.chat_template` of `gemma-4-E2B_q4_0-it.gguf`, read read-only from the board (`tools/gguf_meta.py`, 18.9 KB, "Google Gemma 4 Canonical Chat Template", published 2026-07-09). Findings:
- Turns: `<|turn>system\n..<turn|>\n`, `<|turn>user\n..<turn|>\n`, `<|turn>model\n..<turn|>\n` (the assistant role is called `model`). The system turn exists only when the first message is a system message. Message text is `| trim`med.
- Generation prompt: `<|turn>model\n`. With `enable_thinking = false` (llama-server `--reasoning off`) the template adds no `<|think|>` line to the system turn. The model may still start with an empty `<|channel>thought\n<channel|>` block
  (llama.cpp's Gemma 4 parser consumes it), so `LlmOutputParser.cleanText` strips `<|channel>...<channel|>`, stray `<channel|>` and `<turn|>` / `<|turn>`.
- `<bos>` is the first token of the template output. llama.cpp strips it from the rendered text when the vocabulary adds BOS itself (`common_chat_templates_apply`), and the server tokenizes with `add_special = true`.
  We do the same: Kotlin renders WITHOUT `<bos>`, the native side passes `add_special = true` (`tokenizer.ggml.add_bos_token = True`, BOS id 2).
- Media: the jinja template itself would print `<|image|>` / `<|audio|>`, but llama-server replaces every media part by libmtmd's marker `<__media__>`, joins the text parts with newlines and puts NO separator around markers, so a user turn is
  `text<__media__><__media__>`. libmtmd then expands the marker to `<|image>` .. `<image|>` / `<|audio>` .. `<audio|>` around the embeddings. We render the same: text first, then one marker per media item, in order.
- End of turn: `<turn|>` (end-of-generation). The native loop also stops on a literal `<turn|>`.
`ChatFormat.GEMMA4` implements this; `ChatFormatTest` compares it with golden strings that `tools/gemma_render_reference.py` renders with the model's own jinja template via jinja2
(`app/src/test/resources/chat_golden/gemma4.json`, 6 cases: transcription, system + few-shot, photo + audio, rephrase, whitespace trimming, user only). Not verified on the device: that llama.cpp's own jinja engine produces exactly the same text
as jinja2 for these cases (the sources and the template were read, nothing was run).

The sampler is greedy (temperature 0); thinking is off. Forced assistant prefixes (`{"incident_type":` etc.) are appended after `<|turn>model\n`; whether Gemma continues them as cleanly as Qwen is **not yet verified on the device**
(the user's runs used an instruction prompt without a prefix). If it does not, drop the prefix for Gemma in `Prompts` and keep `LlmOutputParser` (it also accepts the full JSON).

### G1. Transcription (`Prompts.transcription`, Gemma only)
The guide's instruction, verbatim, with the audio. Rendered (BOS added natively; the marker is replaced by `<|audio>`, the audio embeddings and `<audio|>`):
```text
<|turn>user
Transcribe this audio exactly. Output only the transcript, nothing else.<__media__><turn|>
<|turn>model

```
Max 256 tokens. Reply cleaned by `GemmaText.cleanTranscript`. Latency in the app: not yet measured (guide: 0.9-1.9 s per clip with `llama-server`, NPU + GPU).

### G2. Photo analysis (`Prompts.imageDescription`, Gemma only) - rewritten in ADR-062
Rendered (this text is pinned by `src/test/resources/prompts/image_description.gemma4.txt` and `PhotoPromptsTest`, which also checks that this document holds the instruction verbatim):
```text
<|turn>user
This photo was taken to report an incident at a workplace or campus. Look for what went wrong: fire, flames, smoke, soot or burn marks, damaged electrical parts, water leaking or flooding, a burst or broken pipe, spilled liquid or foam, broken equipment, structural damage, or a person who is hurt.
Respond with ONLY a JSON object, no markdown, in exactly this shape:
{"description": "<two short sentences: first the place or setting and what is visible, then what is wrong or dangerous>", "tags": ["<up to 5 lowercase keywords>"]}<__media__><turn|>
<|turn>model
{"description":
```
The photo is downscaled to at most 512 px on the long side first. Max 160 tokens. Measured on the QIDK: 2.3-4.6 s per photo (the first call of a process is the slowest), image encoder on the Adreno GPU.
The reply is read by `GemmaText.parsePhoto` (`description` + up to 5 tags); the description (cap 300 characters) goes into the extraction prompt as a `PHOTO:` line.

### G2b. Photo-only extraction (`Prompts.photoOnlyExtraction`, ADR-062)
Used instead of the narration prompt when NOBODY SPOKE and photos were described (`LlmIncidentExtractionService.firstPass`: blank transcript and image hints). The reply has the same seven keys; every AI value is capped at confidence 0.5 afterwards (`ModelOutputMapper`), so Review shows them as low confidence; the severity policy floors still apply. Rendered with the Gemma format for two photo descriptions (golden `photo_only_extraction.gemma4.txt`):
```text
<|turn>system
Nobody described the incident: you only get descriptions of PHOTOS. Extract incident report fields as JSON from the photos alone. Use null for anything the photos do not clearly show. Never invent names, room numbers or times.
incident_type: Water Leak, Fire, Injury, Accident, Equipment Failure, Electrical Hazard, Gas Leak, Spill, Security, Other, or null if no incident is visible. Fire, flames, smoke or burn damage: Fire (or Electrical Hazard when the damage is on electrical parts). Water leaking, dripping, spraying, overflowing or flooding (an overflowing toilet, sink or bath too): Water Leak. A person visibly hurt: Injury.
severity: judge the visible danger. Critical (flames described on a building or a large area, flames spreading, or people in danger), High (smoke, soot or burn damage, a smaller or contained fire, damaged electrical parts, a person hurt), Medium (an active leak, flooding, spill or broken equipment), Low (a small drip, a stain or minor damage), or null if no incident is visible.
location: the kind of room or area that is visible, for example office, bathroom, kitchen, laboratory, corridor, outdoors, building exterior; null when the photos do not show it (a part of a room such as a wall, ceiling or floor, or just "indoor", is not a location). persons_involved: only people clearly visible, else null. datetime and actions_taken: null.
description: one short sentence saying what the photos show and what is wrong.<turn|>
<|turn>user
PHOTO: A kitchen with flames and dark smoke rising from the cooker hood. A cooking pot is burning on the stove.<turn|>
<|turn>model
{"incident_type": "Fire", "severity": "High", "location": "kitchen", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "A fire is burning on the cooker in a kitchen with dark smoke."}<turn|>
<|turn>user
PHOTO: A basement ceiling with a cracked pipe. Water is spraying from the crack and the floor below is wet.<turn|>
<|turn>model
{"incident_type": "Water Leak", "severity": "Medium", "location": "basement", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "Water is spraying from a cracked pipe in a basement and wetting the floor."}<turn|>
<|turn>user
PHOTO: A corridor where a person lies on the floor next to a fallen ladder. The person looks hurt.<turn|>
<|turn>model
{"incident_type": "Injury", "severity": "High", "location": "corridor", "datetime": null, "persons_involved": "a person", "actions_taken": null, "description": "A person lies hurt on the floor of a corridor next to a fallen ladder."}<turn|>
<|turn>user
PHOTO: A tidy meeting room with a table and chairs. Nothing looks wrong.<turn|>
<|turn>model
{"incident_type": null, "severity": null, "location": null, "datetime": null, "persons_involved": null, "actions_taken": null, "description": null}<turn|>
<|turn>user
PHOTO: A pipe is spraying water in a trench.
PHOTO: Water covers the ground around the pipe.<turn|>
<|turn>model
{"incident_type":
```
Rules in short: report from the photos alone; type from the visible problem (fire / flames / smoke / burn damage, water leaking or overflowing, a person hurt); severity from the visible danger; location only a kind of room or area (never a wall, "indoor", names or room numbers) or null; people only when clearly visible; time and actions null. The four examples are generic scenes (kitchen fire, basement pipe, hurt person in a corridor, tidy room) and none of them is a scene of the evaluation images (`PhotoPromptsTest` checks that).

#### Iterations on the board (Gemma 4 E2B, 6 images, tag `PHOTOLAB`; the image encoder on the GPU makes single runs noisy, see `TESTING.md` "Photo-only reports")
| Version | Change | Result (types / severity) |
|---|---|---|
| v0 | the old prompts: one-sentence description, narration rules with "base the report on PHOTO alone" | 3 of 6 images without a type (broken pipe, AC leak: "condensation dripping" ignored), burnt lab read as "a person using a machine", toilet foam Spill Low; fire / socket / building fire right |
| v1 | description asks what went wrong (fire, soot, leaks, spills, damage) in two sentences; separate photo-only prompt with severity from visible danger and three examples | 6 of 6 types right; the burnt lab Critical (too high); locations like "interior space", "wall" |
| v2 | description shape lists example places ("office, bathroom, laboratory, kitchen ...") | WORSE: the model copied the examples (broken pipe described as "a kitchen with a fire", socket "no incident"); reverted. Lesson: no example words in the description instruction |
| v3 | back to v1 description; extraction: "a kind of room or area ... a wall, ceiling or floor is not a location", Critical only for large flames / spreading fire / people in danger | 6 of 6 types; location "outdoors", "bathroom", "building exterior" |
| v4 | description adds "soot or burn marks without flames are damage from a past fire" | WORSE: "soot and burn marks" appeared in the broken-pipe and building-fire descriptions; reverted |
| v5-v6 | extraction: "Critical (flames described on a building or a large area ...), High (smoke, soot or burn damage, a smaller or contained fire, damaged electrical parts ...)"; overflow rule ("an overflowing toilet, sink or bath too: Water Leak") | pipe Water Leak Medium, burnt lab Fire High, building Fire Critical, socket Electrical Hazard High/Critical, foam Spill/Water Leak Medium, AC Water Leak Medium |

### G3. Extraction, update, rephrase on Gemma
Same text as sections 1-3, rendered in the Gemma format: the system text becomes `<|turn>system\n...<turn|>\n`, the few-shot examples `<|turn>user` / `<|turn>model` turns, and the forced prefix follows `<|turn>model\n`:
```text
<|turn>system
Extract incident report fields as JSON. Use null for anything not said. Never invent details.
Copy location, datetime and persons_involved using the user's exact words.
incident_type: Water Leak, Fire, ... or null if no incident. If a person got hurt, use Injury.
severity: Critical (...), High (...), Medium (...), Low (minor), or null if no incident.<turn|>
<|turn>user
There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.<turn|>
<|turn>model
{"incident_type": "Water Leak", "severity": "Medium", ...}<turn|>
... (two more examples)
<|turn>user
<the narration><turn|>
<|turn>model
{"incident_type":
```
Rephrase has no forced prefix: Qwen gets the empty think block, Gemma just `<|turn>model\n`. Token budgets of these prompts with Gemma's tokenizer: **not measured** (`tools/count_tokens.py` knows only Qwen's `tokenizer.json`; the Gemma GGUF vocabulary is not a `tokenizer.json`).
When photos were described (Gemma backend or Mock AI, gallery photos) the system text gets one more line and the user turn ends with `PHOTO: <description>` lines (`Prompts.extraction(transcript, hints)`):
```text
PHOTO (optional) describes an attached photo. Use it only for facts the user did not say; the user's words win.
```

### G4. One-call narration: audio (+ photos) -> transcript + report (`Prompts.multimodalExtraction`, Gemma only, developer-menu option)
The guide's `bench_report.py` prompt shape ("Please ... and give an incident report. Respond with ONLY a JSON object, no markdown, in exactly this shape:") with this app's keys. Photos come first in marker order, then the audio:
```text
<|turn>system
You write incident reports from a spoken report and/or a photo. Use null for anything not said or visible. Never invent details.
Copy location, datetime and persons_involved using the speaker's exact words.
incident_type: Water Leak, Fire, Injury, Accident, Equipment Failure, Electrical Hazard, Gas Leak, Spill, Security, Other, or null if no incident. If a person got hurt, use Injury.
severity: Critical (deaths, many injured, emergency needed), High (anyone hurt, fire, gas), Medium (contained or isolated), Low (minor), or null if no incident.<turn|>
<|turn>user
Please take a look at the photo and listen to the audio and give an incident report.
Respond with ONLY a JSON object, no markdown, in exactly this shape:
{
  "transcript": "<what the speaker said, verbatim; empty string if no audio>",
  "image_description": "<one sentence describing what is visible; empty string if no photo>",
  "incident_type": "<one of the incident types above, or null>",
  "severity": "<Critical, High, Medium, Low, or null>",
  "location": "<where, if stated or visible, else null>",
  "datetime": "<when, if stated, else null>",
  "persons_involved": "<who, if stated, else null>",
  "actions_taken": "<actions mentioned or visible, else null>",
  "description": "<one short sentence summarising the incident, else null>"
}<__media__><__media__><turn|>
<|turn>model
{"transcript":
```
Max 512 tokens. The 7 incident keys are mapped by the unchanged `ModelOutputMapper`; `transcript` goes to the dialogue as the STT result. The user's reference outputs of the guide's prompt (`idk-test/incident_report.json`, 118-155 generated tokens) were
valid JSON in all 7 cases with this shape; ours is a variant (7 incident keys instead of `expected.fields`), so the forced prefix and the new keys are **not yet verified on the device**. The parser fixtures `app/src/test/resources/llm_outputs/gemma_*.txt` are hand-written.

## Measured on the device with Gemma
None by this app yet. Numbers of the user's `llama-server` runs (NPU + GPU, same model files): `MODEL_INTEGRATION.md`, last section.

## Hindi / any-language input (ADR-047)
Changes: one rule line in the extraction and one-call (multimodal) system prompts ("The user may speak any language ...: write EVERY value in ENGLISH. Translate descriptions; write place names and acronyms in Latin letters (KRB washroom, third floor) and keep proper nouns and codes"), one Hindi few-shot example in the extraction examples (water leak in the KRB washroom) and one in the update examples (location), "(translate; write place names in Latin letters)" in the update system prompt, and the new `Prompts.translateAnswer` (system: "Translate the user's message into one short plain English sentence. Write names and acronyms in Latin letters. Output only the translation."; used once for an unclassified non-English injury / injured-person / injury-type answer). The text above in this file shows the earlier wording; `ai/Prompts.kt` and `ChatFormatTest` hold the exact current text.
Measured with Qwen's tokenizer (`tools/count_tokens.py`, worst-case 90-word English transcript, ChatML): extraction 666 (target 1200, cap 2000), update 546 (target 800, cap 1500), rephrase 232 (target 300, cap 500). The translation prompt was not measured separately (a one-line system prompt and the answer). No Gemma token counts or device timings were measured by this change.

## ADR-051: language-agnostic prompts (measured)
Measured with Qwen's `tokenizer.json` (downloaded from Hugging Face `Qwen/Qwen3-4B`; `tools/count_tokens.py`, the same 90-word worst-case transcript, ChatML): extraction **541** (was 666; the Hindi few-shot was removed), update **502** (was 546), rephrase **232** (unchanged). Targets 1200 / 800 / 300, caps 2000 / 1500 / 500: all ok. Not measured with Gemma's tokenizer (still unavailable as a `tokenizer.json`). The new STT instruction ([Prompts.TRANSCRIBE_INSTRUCTION], two lines of format) is 103 Qwen tokens (the old one was 14); the one-call system rules are 184 tokens and the JSON shape 207 (Qwen tokenizer, an estimate for Gemma). Wording changes: the system rules say "The speaker may use any Indian language (for example Tamil, Assamese, Odia, Bengali, Telugu, Hindi), possibly mixed with English; understand it and write the English report"; the update system says the user may answer in any Indian language; the translate prompt says "any Indian language, possibly mixed with English". No device timing was measured.

## ADR-054: forced spoken language (verbatim)
Chosen language (any name from `languages.json`; here Tamil), Gemma user turn with the audio marker, plain-text reply:
```text
This audio is spoken in Tamil. Transcribe it verbatim in Tamil using its native script. Do not translate. Output only the transcript.
```
English: `Transcribe this audio exactly. Output only the transcript, nothing else.` One-call narration with a language set: the user text gains the line `The audio is spoken in Tamil; write the transcript verbatim in Tamil using its native script.` (before "Respond with ONLY a JSON object...") and the assistant prefix is `{"language": "Tamil", "transcript":`. Auto-detect (default) uses the two-line `Language:` / `Transcript:` prompt of ADR-051, unchanged. Token counts of these prompts with Gemma's tokenizer: not measured (the forced prompt is about 35 words).

## ADR-056: grounded rephrasing of knowledge-graph recommendations (verbatim; off by default)
Built by `Prompts.groundedRephrase(facts)` as a fresh conversation (no history, no media). System turn:
```text
Rewrite only APPROVED_FACTS into concise user-facing steps.
Do not add actions, causes, numbers, locations, medical guidance or safety claims.
Preserve owner roles, uncertainty and prerequisites.
Return JSON with recommendation_id and rendered_text.
```
User turn (one line per approved fact, then the shape):
```text
APPROVED_FACTS:
- recommendation_id: rec:isolate-asset | text: Ask authorized IT staff to isolate the affected equipment from power and the network.
- recommendation_id: ... | text: ...
Return ONLY a JSON array, no markdown, in exactly this shape with one item per recommendation_id:
[{"recommendation_id": "<id>", "rendered_text": "<one short sentence>"}]
```
No assistant prefix. The reply is validated by `LlmOutputValidator` (ids, numbers, new capitalised names, medical terms, phone numbers, dispatch claims); any failure shows the canonical graph text. Token counts: not measured (the renderer is not used by the app).
