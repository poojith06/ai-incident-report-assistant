# Spec v2: Follow-Up Dialogue System for the Voice Incident Reporter

> **Audience:** developers working on this Android (Kotlin) app.
> **Goal:** greet the user, let them describe the incident in their own words, then ask **only** for fields that are still empty, in the right tone. **Each field is asked at most once, and the app never insists if the user doesn't answer.**
> **Read this whole file before writing code.** Build in the order given in [Build order](#11-build-order). Ask the team before changing any rule marked **MUST**.

### Changes from v1

- The conversation always starts with one fixed prompt: **"Hi, describe your incident."**
- Follow-up questions are asked **only** for fields the first description (and later answers) did not fill.
- **Ask-once rule:** every field is asked **at most once** per report. All re-ask / `clarify` variants and the ASR retry prompt are removed.
- If the user doesn't answer, is unsure, or the answer is unclear, the app records that and **moves on**. Nothing is spoken twice. The user can still fix any field by tapping it on the review screen.
- Location is now **one** question (with a "say 'here'" option) instead of a GPS yes/no question followed by a second location question.

---

## 1. Context

The app is an on-device incident reporting assistant running on a Qualcomm QIDK (Snapdragon 8 Gen 2), Android, Kotlin.

Pipeline:

```
Greeting (TTS): "Hi, describe your incident."
  → Mic → Whisper ASR (NPU) → transcript
  → LLM extraction via tool calls (LiteRT-LM, Gemma 4 E2B on GPU; fallback Gemma 3 1B on NPU)
  → form state (ReportViewModel)
  → [THIS SPEC] follow-up dialogue: decide mode, ask each unfilled field once, read back, submit
  → Android TextToSpeech
```

Hard constraints:

- **MUST** run fully offline. No network calls, no cloud APIs.
- **MUST** keep all dialogue decisions (which field to ask, tone mode, when to stop) in deterministic Kotlin. The LLM may only *rephrase* a question, never choose what to ask.
- **MUST** work with the LLM switched off (template-only mode), because the LLM may be slow or fail.
- **MUST** never speak the same question, or ask about the same field, twice in one report.

---

## 2. What to build (summary)

1. `ModeClassifier`: decides the tone mode: `PLAIN`, `CARE` or `URGENT`.
2. `QuestionBank`: loads one question template per field × mode × language from JSON assets.
3. `FollowUpPlanner`: given form state, mode and the set of fields already asked, returns the next **unfilled, not-yet-asked** field, or "done".
4. `Rephraser` + `PhraseValidator`: optionally asks the LLM to make the template sound natural, then validates it; falls back to the template on any failure.
5. `DialogueManager`: the state machine that speaks the greeting, runs the follow-ups, merges answers and produces the read-back.
6. Integration with the existing form tools (tool results return the next question hint).
7. Unit tests + scripted scenario tests (no audio needed).

---

## 3. Tone modes

| Mode | When | Style | Max follow-up questions |
|---|---|---|---|
| `PLAIN` | Property, equipment or facility issues; near misses where nobody was hurt | Short, neutral | **2** |
| `CARE` | Someone injured; distress cues; personal incidents (harassment, threats, assault, personal theft) | Acknowledge first, warm, every question skippable | **3** |
| `URGENT` | Ongoing danger: fire, smoke, gas, unconscious or not breathing, trapped, heavy bleeding, electrocution, explosion, collapse, ongoing violence or weapon | Safety check first, trigger emergency flow, defer the form | **2**, then defer |

The greeting is the same in every mode; the mode is decided **after** the first description.

### 3.1 Classification rules

Inputs: the original transcript, the English transcript (if Whisper translated), the extracted form fields, the optional LLM severity estimate, and optional CLIP photo labels.

Algorithm (**MUST** follow this order):

1. Compute `ruleMode` from keyword rules (3.2) applied to both transcripts.
2. Compute `modelMode` from extracted fields:
   - `injury == true` → at least `CARE`
   - `severity == CRITICAL` → `URGENT`
   - CLIP top label in {fire, smoke} with score ≥ 0.5 → `URGENT`
3. `finalMode = max(ruleMode, modelMode)` with ordering `PLAIN < CARE < URGENT`.
4. **Escalate-only:** the mode may go up during the conversation (e.g. a later answer reveals an injury) but **MUST NEVER go down** within one report.

### 3.2 Keyword rules

Match on lowercase, word-boundary regex. Keep the lists in `assets/dialogue/mode_keywords.json` so they can be edited without code changes.

- **URGENT:** fire, flames, smoke, gas leak, smell gas, explosion, exploded, unconscious, not breathing, passed out, collapsed, trapped, stuck inside, bleeding a lot, heavy bleeding, electrocuted, electric shock, weapon, knife, gun, attacking, still happening.
  - Hinglish (romanized): aag, dhuan, behosh, khoon bahut.
  - Telugu (romanized): nippu, poga, spruha ledu.
- **CARE:** hurt, injured, injury, fell, slipped and fell, cut, burn, burned, bleeding, sprain, broke, fracture, pain, bruise, scared, afraid, shaking, panicking, crying, upset, harass, harassment, harassed, threatened, threat, assault, assaulted, groped, stalking, bullied, my phone was stolen, my bag was stolen.
  - Hinglish: chot, gir gaya, gir gayi, dard, dar lag.
  - Telugu: debba, noppi, bhayam.

> **Note:** the Hindi and Telugu entries are a starting point. Mark them `"needs_native_review": true` in the JSON and add a TODO. Do not invent more without review.

**Negation guard (MUST):** a CARE keyword related to injury does not trigger CARE if it appears in a negated phrase such as "no one was hurt", "nobody got hurt", "not injured", "no injuries", "koi chot nahi". Implement a small window check: a negation word within 4 tokens before the keyword. Add unit tests for this.

---

## 4. Fields, priority and the ask-once rule

### 4.1 When a field counts as "filled"

A field is **filled** if extraction produced a non-empty value for it from **any** user speech so far: the first description or any later answer (answers often fill several fields at once). Filled fields are never asked.

### 4.2 Priority and conditions

The planner walks this list in order and returns the first field that is **not filled**, **not already asked**, and whose condition is true.

| Priority | Field key | Condition to ask | Notes |
|---|---|---|---|
| 1 | `injury` | always, if not filled | Yes/no. Drives mode. |
| 2 | `injured_person` | `injury == true` and not filled | One question covers "me / someone else / name or role" |
| 3 | `injury_type` | `injury == true`, not filled, mode == `CARE` | Skippable |
| 4 | `location` | always, if not filled | One question; the user may say "here" to use GPS |
| 5 | `description` | only if the first description is "thin" (see 4.3) | |
| 6 | `time` | only if the description implies the past (see 4.4) and no time was extracted | Otherwise default to "now" and mention it in the read-back |
| 7 | `immediate_actions` | severity ≥ HIGH or hazard ongoing, and not filled | |
| 8 | `people_involved` | personal-incident subtype (harassment, threat, assault) and not filled | Always optional |

Fields **never asked** (filled by the app or edited on the review screen): `incident_type`, `severity`, `equipment`, `witnesses`, `recommended_follow_up`, `reporter`, `report_timestamp`, `gps`.

### 4.3 "Thin" description

The description counts as thin if any of these is true:
- `incident_type` could not be extracted (null).
- The first transcript has fewer than 6 content words.
- The LLM returned an empty description.

### 4.4 Past-time cue

Ask for `time` only if the transcript contains a past cue (earlier, this morning, yesterday, last night, an hour ago, before lunch, kal, subah, ninna, podduna) **and** no time was extracted. Otherwise set `time = now` with `time_source = "default_now"` and mention it in the read-back.

### 4.5 Ask-once rule (MUST)

- `DialogueManager` keeps `askedFields: MutableSet<String>`. A field is added the moment its question is spoken. `FollowUpPlanner` **MUST** skip every field in `askedFields`.
- There are **no** re-ask, clarify or "sorry, I didn't catch that" prompts.
- After a field's question, the answer has one of five outcomes. All of them end that field's turn:

| Outcome | How it's detected | Field status | Next step |
|---|---|---|---|
| Answered | extraction fills the field | `filled` | next field |
| Unsure / skip | "I don't know", "not sure", "skip", "no idea", "pata nahi", "nahi pata", "teliyadu", "skip chey" | `unknown_reporter_unsure` | next field |
| No response | no speech within **8 s** of the question ending | `no_response` | next field |
| Unclear | ASR confidence < 0.5 **or** extraction can't map the answer to the field | `unverified`; store the raw text in `raw_answer` | next field |
| Answered something else | extraction fills **other** fields but not this one | other fields `filled`; this field `unverified` | next field |

- Fields with status `no_response`, `unverified` or `unknown_reporter_unsure` are highlighted on the review screen so the user (or the safety team) can fix them by tapping. **No voice prompt is repeated.**
- When the mode's cap is reached and fields are still empty, mark them `missing_flagged` and go to the read-back.

### 4.6 Greeting is also spoken once (MUST)

- The greeting "Hi, describe your incident." is spoken exactly **once** per report.
- If the user says nothing within **10 s** after the greeting, do **not** repeat it. Show an on-screen hint ("Tap the mic when you're ready") and wait for a tap. The user can also type.

---

## 5. Conversation state machine

```
START
  → GREETING                   speak "Hi, describe your incident." (once)
  → LISTEN_NARRATION           silence > 10 s → show hint, wait for tap (no repeat)
  → EXTRACT                    existing tool-calling step
  → CLASSIFY_MODE
      ├─ URGENT → SAFETY_CHECK (once) → EMERGENCY_FLOW (existing countdown)
      │           → ASK_LOOP (max 2) → DEFER_OR_READBACK
      └─ PLAIN/CARE → [OPENING only if ≥1 field will be asked] → ASK_LOOP
  → READBACK
  → REVIEW                     user can say a correction once, or tap fields / Submit
  → SUBMIT
  → CLOSING
```

`ASK_LOOP`, per field:
1. Planner returns the next field (or done).
2. Add the field to `askedFields`, then speak its question (rephrased or template).
3. Listen once (8 s silence timeout).
4. Run extraction on the answer and merge **all** filled fields into the form.
5. Set this field's status per the table in 4.5.
6. Re-run `ModeClassifier` (escalate-only).
7. Repeat until the planner returns done or the cap is reached.

If the first description already fills every required field (e.g. the water-leak example), skip `OPENING` and `ASK_LOOP` and go straight to `READBACK`.

### 5.1 Fixed lines (templates, never LLM-generated)

| Slot | PLAIN | CARE | URGENT |
|---|---|---|---|
| `greeting` (all modes, before classification) | "Hi, describe your incident." | same | same |
| `opening` | "Got it. Just one quick detail." (use "a couple of quick details" if 2+ fields will be asked) | "I'm sorry this happened. Thank you for reporting it. I just need a couple of details." | — |
| `safety_check` | — | — | "Are you somewhere safe right now?" |
| `defer` | — | — | "Your safety comes first. We can finish the details later." |
| `readback` | "Logged: {incident_type}, {location}, {time_phrase}. Anything to change?" | "Thank you. Reporting this helps keep everyone safe. I've logged {summary}. {next_step} Anything you'd like to change?" | same as CARE |
| `closing` | "Report saved." | "Your report is saved. You can add more details any time." | same as CARE |
| `offer_typing` (personal incidents only, spoken with the first CARE question, not as a separate question) | — | "You can also type if you'd prefer." | — |

Rules for these lines:
- `safety_check` is asked once. On no response, assume **not safe** and continue the emergency flow.
- `readback` ends with one question. On no response within 8 s, **do not repeat it**: show the review screen with a Submit button and stop speaking.
- `{next_step}` **MUST** be built only from actions that actually happened (e.g. "The safety team has been notified." only if a notification was really sent). If nothing happened, omit it. Never say "help is on the way" unless the emergency flow actually dialled or alerted someone.

---

## 6. Question bank

Store as `assets/dialogue/question_bank.en.json` (plus `.hi.json`, `.te.json` with the same keys). Each field has exactly **one** question per mode. There are no clarify or retry variants. `URGENT` uses the `CARE` text unless an `URGENT` entry exists.

```json
{
  "version": 2,
  "language": "en",
  "greeting": "Hi, describe your incident.",
  "fields": {
    "injury": {
      "PLAIN": "Was anyone hurt?",
      "CARE": "Is everyone okay, or was someone hurt?",
      "URGENT": "Is anyone hurt right now?"
    },
    "injured_person": {
      "CARE": "Was it you who got hurt, or someone else? A name or role is fine."
    },
    "injury_type": {
      "CARE": "What kind of injury is it, like a cut, burn or fall? You can skip this."
    },
    "location": {
      "PLAIN": "Where did this happen? You can say 'here' if it's where you are.",
      "CARE": "Where did this happen? A rough location is fine, or just say 'here'.",
      "URGENT": "Where are you right now?"
    },
    "description": {
      "PLAIN": "What did you see?",
      "CARE": "In your own words, what happened? Only share what you're comfortable with."
    },
    "time": {
      "PLAIN": "Roughly what time was this?",
      "CARE": "Do you remember roughly when? An estimate is fine."
    },
    "immediate_actions": {
      "PLAIN": "Has anything been done to make the area safe?",
      "CARE": "Has anyone been able to help yet, like first aid or security?"
    },
    "people_involved": {
      "CARE": "If you want, you can tell me who was involved. You can also skip this."
    }
  }
}
```

Rules:
- `location`: if the answer is "here", "right here", "yahin" or "ikkada" and a GPS fix exists, store the GPS position and set `location_source = "gps"`. Otherwise store the spoken location.
- Hindi and Telugu files **MUST** be human-translated (leave English text with a `TODO_TRANSLATE` marker if no translation is available yet). Do not machine-translate sensitive CARE lines with the on-device LLM.

---

## 7. Blocked phrases and wording rules

Store in `assets/dialogue/blocked_phrases.json`. `PhraseValidator` rejects any generated text containing these (case-insensitive):

```
why did you, why didn't you, why were you, you should have, you shouldn't have,
calm down, relax, it's not a big deal, not a big deal, you're lucky, you should feel lucky,
you shouldn't feel, help is on the way, don't worry, it's your fault,
you need to see a doctor, diagnos,
please answer, i need you to answer, you didn't answer, again, let me ask again, i'll ask again
```

The last line enforces the ask-once rule in LLM output: the model must never imply it is repeating or pressing.

`"help is on the way"` is allowed **only** in the fixed `readback`/`next_step` lines when an alert was actually sent, never in LLM output.

Wording rules for every template and every LLM rephrase:
- One question per turn. Exactly one `?`.
- 20 words or fewer (TTS time matters).
- "What/where/when/who/how" questions only. Never "why".
- Never ask for an exact time; accept estimates.
- No medical diagnosis or advice.
- Plain, everyday words. No jargon like "incident classification".

---

## 8. LLM rephrasing (optional layer)

Purpose: make the single question for a field refer to what the user already said, e.g. "You mentioned the pantry. Which floor is that on?" Rephrasing happens **once per field**, before the question is spoken. It is never used to generate a second attempt.

### 8.1 Interface

```kotlin
interface Rephraser {
    /** Returns a rephrased question, or null to use the template. Must never throw. */
    suspend fun rephrase(req: RephraseRequest): String?
}

data class RephraseRequest(
    val field: String,             // e.g. "location"
    val mode: DialogueMode,        // PLAIN, CARE, URGENT
    val template: String,          // the safe default question
    val transcriptSoFar: String,   // user's words only
    val language: String           // "en", "hi", "te"
)
```

### 8.2 Prompt (system instruction for the rephrase call)

```
You rewrite ONE follow-up question for a voice incident-report assistant.
Rules:
- Ask exactly one question, about the field: {field}. Nothing else.
- Tone: {mode_description}.
- 20 words or fewer. End with a question mark.
- You may refer to something the user already said.
- This question is asked only once. Never say you are asking again or that an answer is needed.
- Never ask "why". Never blame. Never give medical advice. Never promise help.
- Output only the question text. No quotes, no explanation.
Template to adapt: "{template}"
User's words so far: "{transcriptSoFar}"
```

`mode_description`:
- `PLAIN`: "short and neutral"
- `CARE`: "warm and gentle; the user may be hurt or upset; make clear they can skip"
- `URGENT`: "calm, very short, focused on safety"

Sampler: low temperature (e.g. 0.3), thinking disabled, max ~40 output tokens. Use a **separate** short conversation for rephrasing, so it doesn't pollute the extraction conversation.

### 8.3 PhraseValidator (MUST pass all, else use the template)

1. Not null or blank; contains exactly one `?`.
2. Word count ≤ 20.
3. Contains no blocked phrase (Section 7).
4. Does not start with "why" (in any supported language: "kyun", "enduku").
5. Mentions the target field (keyword check, e.g. `location` → any of where, room, floor, building, place, here; keep the map in `field_keywords.json`).
6. Returned within **1500 ms** (enforce with `withTimeoutOrNull`).

Log every rejection with its reason, so the team can report "validator pass rate" in the final report.

### 8.4 Feature flag

`DialogueConfig.useLlmRephrase: Boolean` (default `true`). With `false`, the system **MUST** behave identically except it uses the templates verbatim. Add a debug toggle in the settings screen.

---

## 9. Integration with existing form tools

The form tools (`recordWhat`, `recordWhereWhen`, `recordPeople`, `recordResponse`) return `ok` + `missing`. Extend each tool's return value to include the planner's hint. The planner receives `askedFields`, so `next_field` is never a field that was already asked:

```json
{
  "ok": true,
  "missing": ["location"],
  "next_field": "location",
  "mode": "CARE",
  "template": "Where did this happen? A rough location is fine, or just say 'here'."
}
```

When there is nothing left to ask, return `"next_field": null`.

`DialogueManager` is the single owner of what gets spoken. If the extraction LLM also produces a free-text reply after a tool call, **ignore it for TTS**. Speak only the planner-chosen question (rephrased or template).

Speech I/O must go through interfaces so tests can run without audio:

```kotlin
interface SpeechOut { suspend fun speak(text: String, language: String) }
interface SpeechIn  { suspend fun listen(silenceTimeoutSec: Int = 8, maxSeconds: Int = 30): AsrResult? } // null = no speech
data class AsrResult(val text: String, val confidence: Float, val language: String)
```

`listen()` returning `null` means **no response**: record it and move on (Section 4.5).

---

## 10. Logging (for evaluation)

Append one JSON line per turn to `files/dialogue_logs/{reportId}.jsonl`:

```json
{"t": 1728600000, "state": "ASK_LOOP", "mode": "CARE", "field": "location",
 "template": "...", "rephrased": "...", "validator": "pass|fail:<reason>|timeout",
 "spoken": "...", "asr_text": "...", "asr_conf": 0.82,
 "outcome": "filled|unknown_reporter_unsure|no_response|unverified",
 "latency_ms": {"rephrase": 640, "asr": 900}}
```

This feeds the metrics for M2 and the final report: questions per report, outcome mix per field, validator pass rate, rephrase latency and total dialogue time.

---

## 11. Build order

1. **Data and assets:** `question_bank.en.json`, `mode_keywords.json`, `blocked_phrases.json`, `field_keywords.json`. Loader + schema validation at app start (fail loudly in debug builds; reject any bank that contains a `clarify` key).
2. **`ModeClassifier`** + negation guard + unit tests.
3. **`FollowUpPlanner`** (pure Kotlin, no Android dependencies) + unit tests for every rule in Section 4, especially the ask-once rule.
4. **`DialogueManager`** state machine with template-only output, using fake `SpeechIn`/`SpeechOut`. Get the scenario tests (Section 12) passing here.
5. **Wire to real ASR/TTS** and the existing form tools.
6. **`Rephraser` + `PhraseValidator`** behind the feature flag, with unit tests for the validator using hand-written good and bad outputs.
7. **Logging.**
8. **Hindi/Telugu asset files** (structure now, translations when available).

Do not start step 6 until step 4's scenario tests pass in template-only mode.

Suggested package layout:

```
app/src/main/java/<pkg>/dialogue/
  DialogueMode.kt
  DialogueConfig.kt
  ModeClassifier.kt
  QuestionBank.kt
  FollowUpPlanner.kt
  DialogueManager.kt
  Rephraser.kt            // interface + LiteRtRephraser implementation
  PhraseValidator.kt
  DialogueLogger.kt
app/src/main/assets/dialogue/
  question_bank.en.json
  question_bank.hi.json
  question_bank.te.json
  mode_keywords.json
  blocked_phrases.json
  field_keywords.json
app/src/test/java/<pkg>/dialogue/
  ModeClassifierTest.kt
  FollowUpPlannerTest.kt
  PhraseValidatorTest.kt
  DialogueScenarioTest.kt
```

---

## 12. Scenario tests (MUST pass, template-only mode)

Each scenario starts with the greeting, feeds a scripted description and scripted answers through fake speech I/O (`null` = silence), and checks the spoken lines and the final form.

| # | Description (after greeting) | Scripted answers | Expected |
|---|---|---|---|
| 1 | "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area." | — | PLAIN. Zero follow-ups, no opening line. Read-back includes water leak, Conference Room 2, 10 AM. |
| 2 | "There's water all over the floor." | "no", "here" | PLAIN. Asks `injury`, then `location` (one question each). Location stored from GPS. Exactly 2 questions. |
| 3 | "I slipped in the pantry and I'm hurt." | "I twisted my ankle" | CARE. Opening acknowledgement. Asks only `injury_type`. Does **not** ask `injury`, `injured_person` or `location` (all filled by the description). |
| 4 | "Nobody was hurt but the printer gave off a spark in room 4." | — | Negation guard: injury not triggered. PLAIN. `injury` filled as false, so it is not asked. Zero follow-ups. |
| 5 | "There's smoke coming from the server room!" | `null` (to safety check) | URGENT. Safety check spoken once; on silence, treated as not safe; emergency flow triggered. Safety check **not** repeated. |
| 6 | "Someone has been harassing me near the parking lot." | "no" (to injury), "skip" (to people involved) | CARE. Typing offer included with the first question. `people_involved` asked once → `unknown_reporter_unsure`. Not asked again. |
| 7 | "Something happened earlier in lab 3, nobody was hurt." | "a chemical bottle fell and broke", "pata nahi" | Thin description → asks `description` once. Past cue → asks `time` once; "pata nahi" → `unknown_reporter_unsure`. Cap of 2 reached, go to read-back. |
| 8 | "The AC in block C is leaking." | `null` (to injury) | `injury` → `no_response`. Moves to the next field. `injury` never asked again. |
| 9 | "Something is broken." | "uh" (ASR conf 0.3), `null` | PLAIN cap 2. First field `unverified` with `raw_answer = "uh"`; second `no_response`. No retry prompt is ever spoken. Remaining fields `missing_flagged`. Still reaches read-back. |
| 10 | "The door handle broke." | "yes, I cut my hand on it" (to injury), "block A, first floor" | Escalates to CARE mid-dialogue. The answer also fills `injured_person` (me) and `injury_type` (cut), so neither is asked. Next question is `location` in CARE wording. Never returns to PLAIN. |
| 11 | `null` (silence after greeting) | — | Greeting **not** repeated. On-screen hint shown. Dialogue waits for a mic tap. |
| 12 | Any scenario where the read-back gets no answer | `null` | Read-back **not** repeated. Review screen shown with Submit. |
| 13 | Any CARE scenario with no notification sent | — | Read-back contains no "notified" or "help is on the way" text. |
| 14 | Every scenario | — | Each field key appears in `askedFields` at most once. No spoken line is spoken twice. Every question has exactly one "?" and ≤ 20 words. |

---

## 13. Acceptance criteria

- [ ] All scenario tests in Section 12 pass with `useLlmRephrase = false`.
- [ ] With `useLlmRephrase = true`, the same tests pass when the `Rephraser` is a fake returning (a) good text, (b) text with "why", (c) two questions, (d) "Let me ask again…", (e) null, (f) a timeout. Cases (b)–(f) must fall back to the template.
- [ ] **No field is asked more than once** in any test (enforced by an assertion inside `DialogueManager` in debug builds).
- [ ] The greeting, safety check and read-back are each spoken at most once per report.
- [ ] Fields that are already filled are never asked.
- [ ] Mode never decreases within a report (test 10).
- [ ] No network permission is used by the dialogue package.
- [ ] Dialogue logs are written for every report.
- [ ] Average added latency per question with rephrasing ≤ 1.5 s on the QIDK (measure and record in the M2 report).

---

## 14. Things NOT to do

- Do not repeat any question, prompt, greeting or read-back, even if the user is silent or unclear.
- Do not add "Sorry, I didn't catch that", "Could you repeat?" or any retry prompt.
- Do not ask about a field that is already filled.
- Do not let the LLM pick the next field, the tone mode or when to stop.
- Do not ask for fields listed as "never asked" in Section 4.2.
- Do not ask combined questions ("Where and when did it happen?").
- Do not speak the extraction LLM's free-text replies.
- Do not claim any action (notification, call, help coming) that didn't actually happen.
- Do not machine-translate CARE templates with the on-device LLM.
- Do not add cloud TTS, cloud ASR or any network dependency.

---

## 15. Design rationale (short)

These rules come from guidance on people in distress: WHO psychological first aid (safety first, don't pressure people to talk, no false promises), trauma-informed interviewing (avoid "why" questions and demands for exact timelines), emergency call-handling guidance (honest reassurance, prepare the caller for questions), and voice-UI design guidance (one question per turn). The ask-once rule extends the "don't pressure" principle: an unanswered question is recorded and left for the review screen rather than pressed again. Research on LLM-driven follow-up questions shows they can skip topics, loop, or merge several questions into one, which is why the planner is deterministic and the LLM only rephrases.
