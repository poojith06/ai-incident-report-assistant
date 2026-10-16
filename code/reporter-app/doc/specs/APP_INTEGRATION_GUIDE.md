# App Integration Guide — Qwen3-4B + Rule Layer

For: the teammate who built the QIDK ASR-LLM-TTS app.
Goal: replace the sample's Llama chat with our incident-report pipeline.

Files you need from the LLM side:
- `IncidentRules.kt` — prompts, JSON parser, safety rules, follow-up logic (tested, 24/24 checks pass)
- The Qwen3-4B model is **already on the board** in `/data/local/tmp/qwen4b`

---

## Step 1 — Confirm the app's package name

```powershell
cd $HOME\platform-tools
.\adb shell pm list packages | Select-String tts
```

Expected: `package:com.example.tts`. If it is different, use your package name everywhere below instead of `com.example.tts`.

Also check which **model folder name** the app's code loads from (search the project for `llama_3p2_3b`). The commands below assume that name.

---

## Step 2 — Copy Qwen into the app's model folder

Open the app once on the board first (so Android creates its storage folder). Then:

```powershell
.\adb shell
```

Inside the board shell:

```sh
D=/storage/emulated/0/Android/data/com.example.tts/files/llama_3p2_3b
[ -d $D ] && mv $D ${D}_llama_backup
mkdir -p $D
cp /data/local/tmp/qwen4b/* $D/
cp /data/local/tmp/qairt/lib/hexagon-v79/unsigned/* $D/
cp /data/local/tmp/qairt/lib/aarch64-android/* $D/
ls $D
cat $D/genie_config.json
exit
```

- The old Llama model is kept as `llama_3p2_3b_llama_backup` (fallback).
- The Qwen config already has deterministic sampling (`temp 0.1`, `top-k 1`). Keep it.

---

## Step 3 — Make the config paths absolute

The app needs **full paths** in `genie_config.json` for:
- `tokenizer.json`
- the 4 model files `part1_of_4.bin` … `part4_of_4.bin`
- `htp_backend_ext_config.json`

If the `cat` output shows plain file names (e.g. `"tokenizer.json"`, `"part1_of_4.bin"`), run inside the board shell:

```sh
D=/storage/emulated/0/Android/data/com.example.tts/files/llama_3p2_3b
sed -i "s#\"tokenizer.json\"#\"$D/tokenizer.json\"#; s#\"part\([0-9]\)_of_4.bin\"#\"$D/part\1_of_4.bin\"#g; s#\"htp_backend_ext_config.json\"#\"$D/htp_backend_ext_config.json\"#" $D/genie_config.json
grep -n "$D" $D/genie_config.json
```

The `grep` should show all 6 paths starting with `/storage/emulated/0/...`. **If the config looks different, send the `cat` output to the LLM side before editing.**

---

## Step 4 — Change the prompt format (Llama → Qwen)

The sample builds a **Llama-format** prompt. Search the project for any of:
`begin_of_text`, `start_header_id`, `end_header_id`, `eot_id`

Replace that prompt construction with our prompt:

```kotlin
val prompt = Prompts.extraction(transcript)
```

Important:
- **Send only our prompt.** Do not add the sample's chat history or system text around it.
- **Reset the conversation between requests.** Genie's dialog can keep previous turns in memory. Every call must start fresh (look for a reset/clear function in the app's Genie wrapper, e.g. `GenieDialog_reset`, or re-create the dialog). Otherwise old reports leak into new ones.
- If the app trims output at `<|eot_id|>` (Llama's end token), change it to `<|im_end|>` (Qwen's). Our parser also strips it.

---

## Step 5 — Add the rule layer

1. Copy `IncidentRules.kt` into the app's source folder, next to the other code files (e.g. `app/src/main/java/com/example/tts/`).
2. Change its first line to the app's package:
   ```kotlin
   package com.example.tts
   ```
3. **If the app is written in Java:** Android Studio will offer to "Configure Kotlin" — accept. Then call the Kotlin objects from Java with `INSTANCE`:
   ```java
   String prompt = Prompts.INSTANCE.extraction(transcript);
   IncidentForm form = RuleEngine.INSTANCE.fromModelOutput(raw, transcript);
   FollowUp next = FormManager.INSTANCE.nextQuestion(form);
   ```

---

## Step 6 — Wire up the flow

```kotlin
// 1. User finishes speaking -> ASR gives `transcript`
FormManager.reset()
val raw  = llmGenerate(Prompts.extraction(transcript))     // the model's text output
val form = RuleEngine.fromModelOutput(raw, transcript)

// 2. Follow-up loop
var next = FormManager.nextQuestion(form)
while (next != null) {
    speak(next.question)                                     // TTS
    val answer = listenAndTranscribe()                       // mic -> ASR
    val raw2 = llmGenerate(Prompts.update(form, next.field, answer))
    FormManager.mergeAnswer(form, next.field, raw2, answer)
    next = FormManager.nextQuestion(form)
}

// 3. Review screen: show form.incidentType, severity, location, datetime,
//    personsInvolved, actionsTaken, form.description; all fields editable.
//    If form.emergency == true, show a red emergency banner.

// 4. Submit: save the report, then speak("Report submitted.")
```

`llmGenerate()` = whatever function the app already uses to send text to Genie and get text back. Pass the **raw generated text** to our functions; the parser handles the rest (including the model repeating `{`).

---

## Step 7 — Performance

- **Load the model once at app start-up**, not per request. This is the difference between ~5 s and ~2 s per answer.
- Run LLM and ASR calls **off the main thread** (coroutines on `Dispatchers.Default`), or the UI freezes.
- Log the time for ASR and LLM separately — we need these numbers for the final report.

---

## Step 8 — Test checklist

| Say / type | Expected |
|---|---|
| "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area." | Water Leak, Medium, Conference Room 2, around 10 AM, no follow-up |
| "Something is broken in the lab." | Equipment Failure, location lab, **asks "When did it happen?"** |
| "Someone slipped near the cafeteria stairs and hurt his ankle" | Injury, High, **asks when** |
| "Hello, can you hear me?" | No incident; asks "What kind of incident was it?" |
| "There's a fire in the canteen kitchen, please come fast" | Fire, emergency banner |
| Answer "I don't know" twice to a question | Stops asking, field shows "Not provided" |
| Whole flow with **airplane mode on** | Works the same (proves on-device) |

---

## Step 9 — If something fails, send back

1. Package name, and whether the app is **Java or Kotlin**
2. The `genie_config.json` contents (`cat` output from Step 2)
3. The first red error from Android Studio, or the log:
   ```powershell
   .\adb logcat -d | Select-String -Pattern "genie|qnn|tts|fatal" | Select-Object -Last 40
   ```

---

## For the speech (ASR) side

- Use **multilingual Whisper-Small** (not a `.en` model — those cannot translate).
- **Telugu → translate mode** (transcribe mode produces garbage for Telugu).
- **English → transcribe mode.**
- **Hindi → translate mode** for now (transcribe is also accurate; we are testing which works better with Qwen).
- Optional: give Whisper an initial prompt listing expected place names (e.g. "canteen, server room, Block B, chemistry lab") to reduce misheard locations.