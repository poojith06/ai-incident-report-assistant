# Feature Context Layers for the Baseline Severity Model

These are add-on context modules that sit on top of the baseline severity classifier described in the severity rulebook and [generalized_severity_framework.md](generalized_severity_framework.md).

The baseline model takes one incident narrative and returns `severity_score` (0–4), evidence, reasoning and confidence. Each layer below adds one capability. Every layer has:

- **Prompt add-on**: text appended to the baseline system prompt.
- **Deterministic logic**: code that runs outside the LLM and can override it.
- **Output extension**: extra fields added to the baseline JSON.
- **UI notes**: how the result is shown to the operator (and to judges).

| Layer | Capability | Runs |
|---|---|---|
| A | Severity × urgency, with hard safety rules | After the baseline classification |
| B | Live entity extraction with confidence colours | While the reporter is speaking |
| C | Duplicate / shared-incident detection | When a new report arrives |

---

## Layer A: Severity × Urgency Hybrid Risk

### Why

Severity and urgency are different questions:

- **Severity** is the potential harm: how bad it is or could get. This is the baseline 0–4 scale.
- **Urgency** is how quickly someone must respond: is it contained, or is it escalating now?

Without the split, a severe but contained event (a fracture already being treated) gets the same treatment as a hazard that is getting worse (smoke spreading through a corridor). Keeping both lets the queue order incidents correctly.

### Urgency scale

| Level | Name | Meaning | Example |
|---|---|---|---|
| U0 | Routine | No time pressure. Log and review. | "Tap was leaking last week, fixed now" |
| U1 | Soon | Respond within hours. Situation is stable. | "Student twisted ankle, sitting in the lab" |
| U2 | Urgent | Respond within minutes. Could get worse. | "Water pooling near the electrical panel" |
| U3 | Immediate | Ongoing or escalating danger to life. | "Fire is spreading", "someone is trapped" |

### Hybrid formula

```text
Final risk =
    LLM severity proposal
    + deterministic safety rules
    + extracted evidence
    + uncertainty handling
```

The LLM proposes. The rules set minimum levels. Evidence justifies the result. Uncertainty pushes the result up and routes it to a human.

### Prompt add-on

```markdown
### URGENCY ASSESSMENT (in addition to severity)

Severity = potential harm (0–4). Urgency = how fast a responder must act (U0–U3).
Assess them independently. A severe event can have low urgency if it is contained;
a moderate event can have high urgency if it is actively escalating.

Urgency cues:
- U3 Immediate: present-tense danger that is spreading or ongoing ("is spreading",
  "still burning", "can't breathe", "trapped", "not responding", "attacking").
- U2 Urgent: an uncontrolled hazard that has not yet caused harm, or harm that may worsen.
- U1 Soon: harm has occurred but the situation is stable or contained.
- U0 Routine: past event, already resolved, or informational.

Tense matters: "the fire was put out" is not the same as "the fire is spreading".
Report what you are unsure of in "uncertainties" instead of guessing.

Add to your JSON output:
"urgency_level": "U0" | "U1" | "U2" | "U3",
"urgency_reasoning": "why this response time is needed",
"is_escalating": true | false,
"uncertainties": ["facts that are missing or ambiguous and would change the rating"]
```

### Hard safety rules (deterministic override)

These run on the raw transcript and the extracted entities. They can only **raise** the severity and urgency floors. They can never lower what the LLM proposed.

| Trigger (pattern family) | Min severity | Min urgency | Category | Notes |
|---|---|---|---|---|
| Person is unconscious / not breathing / unresponsive | 3 | U3 | Medical | |
| Fire is spreading / flames / smoke filling | 3 | U3 | Fire | "Smell of smoke" alone → S2, U2 |
| Gas smell / gas leak | 3 | U3 | Hazmat | Also triggers an "evacuate, no switches" advisory |
| Live wire / exposed cable sparking / electrocution | 3 | U3 | Electrical | |
| Building is collapsing / ceiling falling / structural crack widening | 4 | U3 | Structural | |
| Someone is trapped / stuck / can't get out | 3 | U3 | Rescue | |
| Active violence / weapon / assault in progress | 3 | U3 | Security | |

Rule implementation notes:

1. **Negation and resolution checks.** "No gas smell", "the wire was already insulated" and "the fire was put out" must not fire the rule. Check a short window before the match for negators (`no`, `not`, `never`, `without`) and look for resolution phrases (`was put out`, `has stopped`, `was fixed`). If a rule is suppressed this way, log it so a human can see that it nearly fired.
2. **Synonyms and multiple languages.** Keep the patterns in a config file (e.g. `hard_rules.json`) with synonyms and transliterated Hindi/regional variants, so they can change without a code change.
3. **Always auditable.** Every rule that fires is recorded in `rules_triggered` with the matched span.

### Merge logic

```python
def final_risk(llm, rules_fired, entities):
    sev = llm.severity_score
    urg = llm.urgency_level

    # 1. Deterministic floors: rules can only raise
    for r in rules_fired:
        sev = max(sev, r.min_severity)
        urg = max(urg, r.min_urgency)

    # 2. Uncertainty handling: conservative default from the baseline framework
    needs_review = False
    if llm.confidence_score < 0.6 or entities.mandatory_missing:
        sev = min(sev + 1, 4) if llm.confidence_score < 0.4 else sev
        needs_review = True

    # 3. Human-in-the-loop: high-stakes results never auto-finalise
    if sev >= 3 or urg >= "U2" or rules_fired:
        needs_review = True

    return sev, urg, needs_review
```

### Human authority

Research on human-in-the-loop systems recommends keeping humans in charge of high-stakes incident triage, not letting AI make emergency decisions on its own. The system therefore:

- Shows the AI result as a **proposal**, with evidence and fired rules.
- Requires an operator to **confirm or override** anything at S≥3, U≥U2, or with a fired hard rule.
- Records overrides (`final_decision_by`, `override_reason`). These records become evaluation and fine-tuning data.

### Output extension

```json
{
  "severity_score": 3,
  "severity_source": "rule_floor",
  "llm_severity_proposal": 2,
  "urgency_level": "U3",
  "is_escalating": true,
  "rules_triggered": [
    { "rule": "fire_spreading", "matched_span": "the smoke is getting thicker", "min_severity": 3, "min_urgency": "U3" }
  ],
  "rules_suppressed": [],
  "uncertainties": ["number of people inside unknown"],
  "needs_human_review": true,
  "final_decision_by": null
}
```

### UI notes

- Show a 2-D badge such as **S3 · U3**, not a single colour.
- If a rule raised the level, show it explicitly: *"Raised from S2 → S3 by rule: fire spreading"*.
- Sort the queue by urgency first, then severity.

---

## Layer B: Live Entity Extraction with Confidence Colours

### Why

Instead of a loading spinner, the UI fills in a structured incident card while the reporter speaks. Judges and operators can watch the model's understanding build up and fix it in real time.

### Target behaviour

```text
"Yesterday at 8 PM..."
→ Time: 8:00 PM
→ Date: 10 October 2026      (resolved from report time, 11 Oct 2026)

"...near Lab 204..."
→ Location: Lab 204

"...a student slipped because of water..."
→ Event: Slip/Fall
→ Hazard: Wet floor
→ Person affected: Student
```

### Entity schema

| Field | Mandatory | Example |
|---|---|---|
| `event_type` | Yes | Slip/Fall, Fire, Electrical, Medical, Security, Structural, Hazmat |
| `location` | Yes | Lab 204, Server Room, Block C stairwell |
| `time` | Yes | 20:00 |
| `date` | Yes | 2026-10-10 |
| `hazard` | No | Wet floor, exposed wire |
| `persons_affected` | Yes (count may be "unknown") | Student ×1 |
| `injury_description` | No | Pain in left wrist |
| `current_status` | Yes | Ongoing / Contained / Resolved |
| `asset_id` | No | AC-unit-3, Panel-B2 |

### Confidence states (colours)

| Colour | State | Rule |
|---|---|---|
| 🟢 Green | `explicit` | Stated directly in the transcript; a verbatim span exists |
| 🟡 Yellow | `inferred` | Derived or uncertain (e.g. "yesterday" → a date; "slipped because of water" → hazard = wet floor) |
| 🔴 Red | `missing` | Mandatory field not yet provided |
| 🔵 Blue | `confirmed` | The reporter confirmed or edited the value. Locked; the model cannot overwrite it |

### Prompt add-on

```markdown
### LIVE ENTITY EXTRACTION

You receive a PARTIAL transcript that grows over time, plus the current entity state.
Update the entity state; do not restart from scratch.

Rules:
1. For each field, output value, status, and the exact source span.
   - status = "explicit" if the value appears verbatim or near-verbatim.
   - status = "inferred" if you derived it (relative dates, implied hazards, implied counts).
   - status = "missing" if the field is mandatory and not yet mentioned.
2. Resolve relative time ("yesterday", "an hour ago", "this morning") against
   report_timestamp. Always mark resolved values as "inferred".
3. NEVER change a field whose status is "confirmed".
4. If a new statement contradicts an earlier value, keep the newer one, mark it
   "inferred", and add the conflict to "conflicts".
5. For every "missing" mandatory field, write ONE short, plain-language follow-up
   question the operator can ask.

Input:
report_timestamp: <ISO timestamp>
current_entities: <JSON>
transcript_so_far: <text>

Output JSON:
{
  "entities": {
    "<field>": { "value": ..., "status": "explicit|inferred|missing|confirmed", "source_span": "..." }
  },
  "conflicts": [],
  "follow_up_questions": ["Which room or building is this in?"]
}
```

### Streaming design

- Trigger extraction on every ASR chunk boundary (or every ~10 words / 1.5 s, whichever comes first) to keep on-device compute bounded.
- Merge field by field. A `confirmed` field always wins, then `explicit`, then `inferred`.
- Run a cheap regex/NER pass first for time, room numbers and asset IDs so those appear instantly, then let the LLM refine them.
- The Layer A hard rules also run on each chunk, so an S3/U3 flag can appear mid-sentence.

### UI notes

- Animate each entity into the card as it is extracted, with a short highlight on the matching transcript span.
- Tapping a field lets the reporter confirm it (it turns blue) or edit it.
- Red fields show their follow-up question inline. The report cannot be submitted while mandatory fields are red, unless the operator overrides.

---

## Layer C: Duplicate / Shared-Incident Detection

### Why

During a real event, many people report the same thing. Research on emergency calls names redundant reports, incomplete information and surges in volume as major operational problems. It proposes spatial-semantic clustering with human confirmation. Merging duplicates keeps the queue readable. Combining partial reports also builds a fuller picture than any single report.

### Signals

| Signal | How | Weight |
|---|---|---|
| Location similarity | Same room = 1.0, same floor/adjacent room = 0.6, same building = 0.3 (from a small room-adjacency map) | 0.25 |
| Event type match | Same category = 1.0, related category (smoke ↔ fire ↔ alarm) = 0.7 | 0.15 |
| Time proximity | `exp(-Δt / 10 min)` | 0.15 |
| Semantic similarity | Cosine similarity of on-device sentence embeddings of the descriptions | 0.25 |
| Image features | Cosine similarity of image embeddings (if both reports have photos; otherwise the weight is shared out to the other signals) | 0.10 |
| Shared asset / room ID | Exact match of `asset_id` or room code = 1.0 | 0.10 |

```text
merge_score = Σ weight_i × signal_i      (renormalised over available signals)
```

| Score | Action |
|---|---|
| ≥ 0.80 | Suggest **merge** into one shared incident |
| 0.60 – 0.80 | Show as **possibly related** (linked, not merged) |
| < 0.60 | Keep separate |

Nothing merges automatically. A human always confirms.

### Prompt add-on (pairwise adjudicator, for borderline scores 0.6–0.85)

```markdown
### DUPLICATE ADJUDICATION

Two incident reports are given with their extracted entities and timestamps.
Decide whether they describe:
- "same_incident": the same physical event,
- "related": different reports of a connected situation (e.g. cause and effect,
  or the same hazard seen from different places),
- "different": unrelated events.

Treat different wording for the same thing as consistent ("smoke" vs "burning
smell" vs "alarm going off"). Contradictory facts (different floors, times far apart,
different victims) are evidence against a merge.

Output JSON:
{
  "relation": "same_incident" | "related" | "different",
  "confidence": 0.0-1.0,
  "shared_evidence": ["..."],
  "conflicting_evidence": ["..."]
}
```

### Cluster rules

- **Cluster severity = max** of its members' severities, and the same for urgency. A duplicate never lowers the risk.
- **Surge signal:** 3 or more independent reports within 5 minutes raises cluster urgency by one level (up to U3). Many independent reporters are evidence the event is real and large.
- **Entity fusion:** fill missing fields in the cluster from any member. Where members conflict, keep both values and flag the field yellow.
- Keep every original report **unchanged and traceable**. Merging only links reports; it never deletes them.

### Display

```text
Possible shared incident                     Merge confidence: 87%

Report 104: Smoke in Server Room        — 10:31   ●  anchor
Report 107: Burning smell near Server Room — 10:33   ●  0.91 (same room, related event, Δ2 min)
Report 109: Alarm near Lab 204          — 10:34   ◐  0.62 (adjacent room, possibly related)

Cluster risk: S3 · U3   (surge: 3 reports in 3 min)
[ Confirm merge ]  [ Link only ]  [ Keep separate ]
```

Show the score for each member as well as the cluster score, so the operator can see which link is weak (here, 109 is in a different room).

### Output extension

```json
{
  "cluster_id": "C-0042",
  "anchor_report": 104,
  "members": [
    { "report_id": 107, "merge_score": 0.91, "relation": "same_incident" },
    { "report_id": 109, "merge_score": 0.62, "relation": "related" }
  ],
  "cluster_confidence": 0.87,
  "cluster_severity": 3,
  "cluster_urgency": "U3",
  "surge_detected": true,
  "status": "pending_human_confirmation"
}
```

---

## How the Layers Fit Together

```text
Speech ──► ASR (on-device) ──► Layer B: live entity extraction ──► incident card (colour-coded)
                                     │
                                     ▼
                       Baseline severity model (0–4)
                                     │
                                     ▼
              Layer A: + urgency + hard rules + uncertainty ──► S·U badge, review flag
                                     │
                                     ▼
              Layer C: compare with recent open reports ──► merge / link suggestion
                                     │
                                     ▼
                     Human operator confirms / overrides (logged)
```

### Combined output schema (baseline + all layers)

```json
{
  "reasoning": "...",
  "extracted_evidence": ["..."],
  "severity_level": "HIGH (Level 3: Severe)",
  "severity_score": 3,
  "confidence_score": 0.72,

  "llm_severity_proposal": 2,
  "severity_source": "rule_floor",
  "urgency_level": "U3",
  "is_escalating": true,
  "rules_triggered": [],
  "uncertainties": [],
  "needs_human_review": true,

  "entities": {},
  "follow_up_questions": [],

  "cluster": null,

  "final_decision_by": null,
  "override_reason": null
}
```

### Evaluation hooks

- **Layer A:** measure under-triage (true S≥3 predicted lower) separately from over-triage. Under-triage is the costly error. Report urgency accuracy on its own.
- **Layer B:** field-level precision/recall, and time-to-first-correct-entity during streaming.
- **Layer C:** pairwise precision/recall on a hand-labelled set of duplicate pairs built from `incident_dataset_100.json` paraphrases.
