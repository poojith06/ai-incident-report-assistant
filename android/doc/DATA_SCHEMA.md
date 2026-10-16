# Data schema: Incident JSON (schema_version 1.1)

Kotlin mirror: `domain/model/IncidentReport.kt` (+ `IncidentField.kt`). Codec: `domain/service/IncidentJsonCodec.kt`.
Serialization ignores unknown keys, so older/newer producers do not crash the app.

## Top level

| Key | Type | Notes |
|---|---|---|
| `schema_version` | string | `"1.1"` |
| `report_id` | string | `INC-<year>-<6 digits>`. Produced by the extraction service. |
| `status` | string | `needs_clarification` / `complete` / `submitted`. Recomputed by `ValidationService`. |
| `created_at` | ISO-8601 with offset | |
| `input` | object | `mode` (`voice` / `voice_and_photo` / `photo_only`; older reports say `voice_and_camera`, read as `voice_and_photo`), `transcript` (first utterance, in the language spoken, never altered), `language` (free-form: `en` when the speaker used English, otherwise the language NAME the speech model reported, lowercased, e.g. `tamil`, `assamese`, `odia`; when the STT gave none, the dominant Unicode script name such as `devanagari` / `tamil`, or `en`; ADR-051), `media[]` (`id`, `type` = `image`, `local_uri`, `captured_at`) |
| `fields` | object | key -> `{ value, confidence 0..1, source }` (see below) |
| `device` | object or null | Set by the app, never by the LLM: `reporter`, `reported_at`, `gps {latitude, longitude}` or null, `anonymous` |
| `validation` | object | `mandatory_fields`, `missing_fields`, `low_confidence_fields`, `follow_up_fields`, `is_complete`. Computed by the app; any LLM-provided values are overwritten. |
| `clarification` | object | `rounds_used`, `max_rounds`, `next_question {target_fields[], text}` or null, `history[]` |
| `tool_call` | object or null | `{ name, arguments_ref }` e.g. `fill_incident_form` / `submit_incident_report`, `arguments_ref: "fields"` |
| `location_source` | string or null | `spoken` / `gps`. How `incident_location` was obtained (set by the dialogue; null if there is no location). **New in 1.1** |
| `time_source` | string or null | `spoken` / `default_now`. How `date_time` was obtained. **New in 1.1** |
| `dialogue` | object | `{ mode, asked_fields[], emergency }`: tone mode (`PLAIN`/`CARE`/`URGENT`), dialogue field keys asked (each at most once), emergency flag (mode reached URGENT). Defaults `{PLAIN, [], false}` for reports that never went through the dialogue. **New in 1.1** |

`clarification.history[]` entry: `question`, `answer` (transcript), `asked_fields[]`, `updated_fields[]`.

`source` values: `ai`, `user_voice` (answered in a clarification round), `user_edit` (typed on the review form).

## The 14 fields (`fields.<key>`)

Order = Review-form order. Groups drive how the field is obtained.

| # | Key | Group | Value type | How filled | Asked? |
|---|---|---|---|---|---|
| 1 | `incident_description` | required | string | narration | only if empty: "What happened?" |
| 2 | `injury_outcome` | situational | enum string: `No injury`, `Minor injury`, `Serious injury`, `Fatal` | narration; Review | never (optional since ADR-050; drives severity when known) |
| 3 | `incident_location` | required | string | narration; GPS backup (`GPS lat, lon`, conf 0.5) | "Where exactly did this happen?" |
| 4 | `date_time` | required | ISO-8601 string | narration, else now (conf 0.7) | never; the TTS read-back says "I've logged this as 9:42 PM; correct me if that's wrong." |
| 5 | `immediate_actions_taken` | situational | string | narration | only if severity is High/Critical or hazard still active: "Has anything been done to make it safe?" |
| 6 | `persons_involved` | situational | string[] | narration | only if someone was injured: "Who was hurt?" |
| 7 | `hazard_still_active` | situational | boolean | narration | never (drives question 5 and severity) |
| 7a | `injury` | situational | boolean | narration; follow-up dialogue | **New in 1.1.** Never asked (ADR-050): filled when the narration or an answer says so. Kept consistent with `injury_outcome` by `DialogueForm.reconcileInjury` |
| 7b | `injury_type` | situational | string | narration; follow-up dialogue | **New in 1.1.** Dialogue-asked in CARE mode only when `injury == true` |
| 8 | `equipment_asset_involved` | optional | string[] | narration or photo | never; editable on review |
| 9 | `witness_information` | optional | `[{name, role|null, contact|null}]` | narration | never; editable on review |
| 10 | `incident_type` | app-filled | string | LLM classification (+ photo) | never |
| 11 | `severity_level` | app-filled | `Low` / `Medium` / `High` / `Critical` | `IncidentPolicies` from injury + hazard | never; user can change on review |
| 12 | `recommended_follow_up_action` | app-filled | string | LLM (future: with incident history) | never |

Device-sourced (not in `fields`): reporter, report time, GPS in `device`; the review form has an "Anonymous" toggle
(on submit the reporter name is removed).

## Schema 1.1: dialogue additions

Every field object (`fields.<key>`) is now `{ value, confidence, source, status, raw_answer }`:

| Key | Type | Notes |
|---|---|---|
| `status` | string | `filled`, `unknown_reporter_unsure`, `no_response`, `unverified`, `missing_flagged`, `empty`. Kotlin: `FieldEntry.status` is nullable on the wire in older data; `FieldEntry.effectiveStatus` derives `filled`/`empty` from `value` when it is null. The dialogue stamps every field. A user edit on the review screen creates a fresh entry (status null = `filled`), which clears the highlight. |
| `raw_answer` | string or null | The user's unparsed words; only set when `status == "unverified"` |

Status meanings (Spec v2 section 4.5): `filled` = extraction produced a value; `unknown_reporter_unsure` = the user said "I don't know/skip"; `no_response` = no speech within 8 s of the question; `unverified` = ASR confidence below 0.5 or the answer could not be mapped to the field (raw text kept); `missing_flagged` = the field was still wanted when the mode's question cap was reached; `empty` = nothing there and nothing was asked. The Review screen highlights `unknown_reporter_unsure`, `no_response`, `unverified` and `missing_flagged`.

### Mapping: dialogue field (Spec v2) to report field

| Dialogue field | Report field | Change |
|---|---|---|
| `injury` | `injury` | **New** boolean field |
| `injured_person` | `persons_involved` | Entry carrying the injured marker: the string ends with ` (injured)` (e.g. `"Reporter (injured)"`). See ADR-021 |
| `injury_type` | `injury_type` | **New** string field |
| `location` | `incident_location` | Report-level `location_source`: `spoken` or `gps` |
| `description` | `incident_description` | none |
| `time` | `date_time` | Report-level `time_source`: `spoken` or `default_now` |
| `immediate_actions` | `immediate_actions_taken` | none |
| `people_involved` | `persons_involved` | Entries without the injured marker |
| never asked | `incident_type`, `severity_level`, `equipment_asset_involved`, `witness_information`, `recommended_follow_up_action` | Extraction or the review screen only |

`injury` and `injury_outcome` (Phase 1 enum) both exist. If only one is present the dialogue derives the other: `injury_outcome` set -> `injury = (outcome != "No injury")`; `injury == false` -> outcome "No injury"; `injury == true` -> outcome "Minor injury" at confidence 0.5 (below the 0.6 threshold, so Review highlights it as "not sure").

### Example: complete report (schema 1.1, after the dialogue; water-leak case, zero follow-ups)

```json
{
  "schema_version": "1.1",
  "report_id": "INC-2026-000142",
  "status": "complete",
  "created_at": "2026-10-11T10:24:31+05:30",
  "input": { "mode": "voice", "transcript": "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.", "language": "en", "media": [] },
  "fields": {
    "incident_description": { "value": "Water leaking near Conference Room 2.", "confidence": 0.9, "source": "ai", "status": "filled", "raw_answer": null },
    "injury_outcome": { "value": "No injury", "confidence": 0.95, "source": "ai", "status": "filled", "raw_answer": null },
    "incident_location": { "value": "Conference Room 2", "confidence": 0.93, "source": "ai", "status": "filled", "raw_answer": null },
    "date_time": { "value": "2026-10-11T10:00:00+05:30", "confidence": 0.88, "source": "ai", "status": "filled", "raw_answer": null },
    "immediate_actions_taken": { "value": "Maintenance isolated the area.", "confidence": 0.95, "source": "ai", "status": "filled", "raw_answer": null },
    "injury": { "value": false, "confidence": 0.95, "source": "ai", "status": "filled", "raw_answer": null },
    "injury_type": { "value": null, "confidence": 0.0, "source": "ai", "status": "empty", "raw_answer": null },
    "persons_involved": { "value": [], "confidence": 0.8, "source": "ai", "status": "filled", "raw_answer": null },
    "hazard_still_active": { "value": false, "confidence": 0.8, "source": "ai", "status": "filled", "raw_answer": null },
    "equipment_asset_involved": { "value": [], "confidence": 0.7, "source": "ai", "status": "empty", "raw_answer": null },
    "witness_information": { "value": [], "confidence": 0.5, "source": "ai", "status": "empty", "raw_answer": null },
    "incident_type": { "value": "Water Leak", "confidence": 0.96, "source": "ai", "status": "filled", "raw_answer": null },
    "severity_level": { "value": "Low", "confidence": 0.75, "source": "ai", "status": "filled", "raw_answer": null },
    "recommended_follow_up_action": { "value": "Inspect ceiling plumbing above Conference Room 2.", "confidence": 0.74, "source": "ai", "status": "filled", "raw_answer": null }
  },
  "device": { "reporter": "Pixel 8", "reported_at": "2026-10-11T10:24:31+05:30", "gps": null, "anonymous": false },
  "validation": { "mandatory_fields": ["incident_description", "incident_location", "date_time"], "missing_fields": [], "low_confidence_fields": [], "follow_up_fields": [], "is_complete": true },
  "clarification": { "rounds_used": 0, "max_rounds": 3, "next_question": null, "history": [] },
  "tool_call": { "name": "fill_incident_form", "arguments_ref": "fields" },
  "location_source": "spoken",
  "time_source": "spoken",
  "dialogue": { "mode": "PLAIN", "asked_fields": [], "emergency": false }
}
```

### Example: incomplete report (schema 1.1; "Something is broken." with an unclear answer and silence)

PLAIN mode, cap of 2 reached. The planner asked `location` (no response) and the thin `incident_description` (an unclear "uh": the value is kept, marked `unverified`, and the raw answer is stored). Nobody mentioned an injury, so `injury` stays empty and is not asked (ADR-050). Review highlights both asked fields.

```json
{
  "schema_version": "1.1",
  "report_id": "INC-2026-000143",
  "status": "needs_clarification",
  "created_at": "2026-10-11T11:02:10+05:30",
  "input": { "mode": "voice", "transcript": "Something is broken.", "language": "en", "media": [] },
  "fields": {
    "incident_description": { "value": "Something is broken", "confidence": 0.5, "source": "ai", "status": "unverified", "raw_answer": "uh" },
    "injury": { "value": null, "confidence": 0.0, "source": "ai", "status": "empty", "raw_answer": null },
    "incident_location": { "value": null, "confidence": 0.0, "source": "ai", "status": "no_response", "raw_answer": null },
    "date_time": { "value": "2026-10-11T11:02:10+05:30", "confidence": 0.7, "source": "ai", "status": "filled", "raw_answer": null },
    "incident_type": { "value": null, "confidence": 0.0, "source": "ai", "status": "empty", "raw_answer": null },
    "severity_level": { "value": null, "confidence": 0.0, "source": "ai", "status": "empty", "raw_answer": null }
  },
  "validation": { "mandatory_fields": ["incident_description", "incident_location", "date_time"], "missing_fields": ["incident_location"], "low_confidence_fields": [], "follow_up_fields": [], "is_complete": false },
  "clarification": { "rounds_used": 0, "max_rounds": 3, "next_question": null, "history": [] },
  "tool_call": null,
  "location_source": null,
  "time_source": "default_now",
  "dialogue": { "mode": "PLAIN", "asked_fields": ["location", "description"], "emergency": false }
}
```

(Fields not listed are `empty`; the real file lists all 14. `date_time` has `time_source: default_now`, mentioned in the read-back.)

## Validation rules (`DefaultValidationService`)

- **Mandatory fields** come from `IncidentConfig.DEFAULT_MANDATORY_FIELDS` (`domain/config/IncidentConfig.kt`) and are
  copied into `validation.mandatory_fields`. UI never hardcodes them.
- **Missing**: mandatory and value is `null`, blank string, or empty list; or `injury_outcome` / `severity_level`
  outside the allowed values.
- **Low confidence**: mandatory, present, in `askOrder`, `source == ai`, `confidence < confidenceThreshold` (default 0.6).
  `date_time` is never low-confidence because it is never asked. User-sourced values are trusted.
- **Follow-up** (`follow_up_fields`): `persons_involved` if injured and empty; `immediate_actions_taken` if severity is
  High/Critical or `hazard_still_active` is true, and empty. Each is asked at most once (checked against
  `clarification.history[].asked_fields`). Follow-ups never make a report "incomplete".
- **`is_complete`** = no missing and no low-confidence.
- `Validation.attentionFields` = missing + low confidence + follow-ups; this is what the "N details left" counter shows.
- `IncidentConfig.maxClarificationRounds` (default 3) is only copied into `clarification.max_rounds`; questions are now chosen by the dialogue planner (mode caps 2/3/2 and at most 2 questions per report in total, safety check included; `DIALOGUE.md`).

## App-filled fields (`IncidentPolicies`)

Run after every extraction pass and after every review edit.

1. `date_time` empty -> now, confidence 0.7.
2. `incident_location` empty and `device.gps` known -> `GPS lat, lon`, confidence 0.5 (so it is still asked).
3. `severity_level` (unless `source == user_edit`) -> `SeverityPolicy`: `max(model hint, floors)`, never lowered by a later pass (see "Severity policy" below), confidence 0.75, source `ai` when the policy raised it.

### Severity policy (`SeverityPolicy`, `assets/dialogue/severity_cues.json`)

The LLM's severity is only a hint (the current value of `severity_level`). Floors, highest wins:

| Floor | Severity |
|---|---|
| injury/hazard table (below); an injury answer without outcome counts as Minor, "no" as none | Low .. Critical |
| `dialogue.mode == URGENT` or `dialogue.emergency` | at least High |
| URGENT and (an un-negated escalation cue in the transcript or any answer, or `injury == true`, or an injured outcome) | Critical |
| `incident_type` Fire / Gas Leak / Electrical Hazard | at least High |

| injury | hazard active | table severity |
|---|---|---|
| unknown | any | no floor |
| No injury | no / yes | Low / Medium |
| Minor injury | no / yes | Medium / High |
| Serious injury | no / yes | High / Critical |
| Fatal | any | Critical |

Escalation cues (English): urgent, very urgent, emergency, need help, please help, help me, hurry, come fast, come quickly, trapped, not breathing, unconscious, heavy bleeding, serious bleeding, seriously bleeding, a lot of bleeding, bleeding a lot, bleeding badly; negation-guarded ("it's not urgent"). `injury_outcome` is never defaulted while `injury` is unknown. `IncidentPolicies.apply` needs the cues (`DialogueAssets.severityCues`). A `user_edit` severity is never changed.

## Example: complete report (abridged)

```json
{
  "schema_version": "1.1",
  "report_id": "INC-2026-000142",
  "status": "complete",
  "created_at": "2026-10-10T10:24:31+05:30",
  "input": {
    "mode": "voice_and_photo",
    "transcript": "There was a water leak near Conference Room 2 around 10 AM. Nobody was hurt. Maintenance has already isolated the area. Ravi from facilities saw it first. It's coming from the ceiling above the projector, and the projector might be damaged.",
    "language": "en",
    "media": [{ "id": "media_01", "type": "image", "local_uri": "file:///data/user/0/com.example.test_app/files/media/1_media_01.jpg", "captured_at": "2026-10-10T10:23:58+05:30" }]
  },
  "fields": {
    "incident_description": { "value": "Water leaking from the ceiling near Conference Room 2, directly above the projector. Projector may be damaged.", "confidence": 0.9, "source": "ai" },
    "injury_outcome": { "value": "No injury", "confidence": 0.95, "source": "ai" },
    "incident_location": { "value": "Conference Room 2, ceiling above projector", "confidence": 0.93, "source": "ai" },
    "date_time": { "value": "2026-10-10T10:00:00+05:30", "confidence": 0.88, "source": "ai" },
    "immediate_actions_taken": { "value": "Maintenance isolated the area.", "confidence": 0.95, "source": "ai" },
    "persons_involved": { "value": [], "confidence": 0.8, "source": "ai" },
    "hazard_still_active": { "value": false, "confidence": 0.8, "source": "ai" },
    "equipment_asset_involved": { "value": ["Projector"], "confidence": 0.85, "source": "ai" },
    "witness_information": { "value": [{ "name": "Ravi", "role": "Facilities", "contact": null }], "confidence": 0.82, "source": "ai" },
    "incident_type": { "value": "Water Leak", "confidence": 0.96, "source": "ai" },
    "severity_level": { "value": "Low", "confidence": 0.75, "source": "ai" },
    "recommended_follow_up_action": { "value": "Inspect ceiling plumbing above Conference Room 2 and assess projector for water damage.", "confidence": 0.74, "source": "ai" }
  },
  "device": { "reporter": "Pixel 8", "reported_at": "2026-10-10T10:24:31+05:30", "gps": null, "anonymous": false },
  "validation": {
    "mandatory_fields": ["incident_description", "incident_location", "date_time"],
    "missing_fields": [], "low_confidence_fields": [], "follow_up_fields": [], "is_complete": true
  },
  "clarification": { "rounds_used": 0, "max_rounds": 3, "next_question": null, "history": [] },
  "tool_call": { "name": "fill_incident_form", "arguments_ref": "fields" }
}
```

## Example: incomplete report (LLM output before validation)

Output of the LLM for "Someone slipped near the stairs." (before the app adds `device`, defaults the time, validates):

```json
{
  "schema_version": "1.1",
  "report_id": "INC-2026-000143",
  "status": "needs_clarification",
  "created_at": "2026-10-10T11:02:10+05:30",
  "input": { "mode": "voice", "transcript": "Someone slipped near the stairs.", "language": "en", "media": [] },
  "fields": {
    "incident_description": { "value": "A person slipped near the stairs.", "confidence": 0.8, "source": "ai" },
    "injury_outcome": { "value": null, "confidence": 0.0, "source": "ai" },
    "incident_location": { "value": "Near the stairs", "confidence": 0.45, "source": "ai" },
    "date_time": { "value": null, "confidence": 0.0, "source": "ai" },
    "incident_type": { "value": "Slip / Fall", "confidence": 0.91, "source": "ai" },
    "severity_level": { "value": null, "confidence": 0.0, "source": "ai" }
  },
  "validation": {
    "mandatory_fields": ["incident_description", "incident_location", "date_time"],
    "missing_fields": [], "low_confidence_fields": ["incident_location"],
    "follow_up_fields": [], "is_complete": false
  },
  "clarification": {
    "rounds_used": 0, "max_rounds": 3,
    "next_question": { "target_fields": ["incident_location"], "text": "Where exactly did this happen?" },
    "history": []
  },
  "tool_call": null
}
```

(`date_time` becomes "now" via `IncidentPolicies`, so it is not listed as missing after the app processes it.)
Fields omitted from the LLM output are treated as empty.

## Emergency contacts (severe incidents)

`domain/config/EmergencyContacts.kt`: `EmergencyContact(name, role, phone)`; `EmergencyContacts.DUMMY` holds 5 dummy Indian
numbers (security supervisor, HR manager, facilities and maintenance, cleaning staff lead, first-aid/medical desk).
`EmergencyContacts.isSevere(severity)` is true for `High` and `Critical`. Not part of the Incident JSON. Replace the list
with the organisation's directory (or a repository) for production.

## Report language (always English)
Every extracted VALUE is English whatever language was spoken (the LLM translates; place names and acronyms are written in Latin letters: "KRB washroom, third floor"). `input.transcript` keeps the original words (Devanagari for Hindi) and `input.language` says which language that was (free-form language name as the STT reported it, or `en`; fallback when the STT gave none: `LanguageDetect.detect`, the dominant non-Latin Unicode script name, else `en`; no ISO table, no per-language code; Latin-script Hinglish stays `en`). No schema change. Safety net (`ModelOutputMapper`): if a model value for a structured text field still contains non-Latin script, it is NOT stored: the field is empty with `status = unverified` and `raw_answer` = the original script text, Review highlights it and the planner may ask for it once; a good English value is never replaced by script. Spoken lines (read-back, Form read-out) never contain non-Latin text: "the place you mentioned" replaces it.

## `input.language` and optional injury (ADR-051, ADR-050)
`input.language` is a free-form language name, or `en`: the language the speech model reports for the narration (Gemma STT replies `Language: <name>` / `Transcript: <text>`; the name is stored lowercased, `English` becomes `en`, an unknown or missing name falls back to the dominant Unicode script of the transcript: `devanagari`, `tamil`, `bengali` (Assamese is written in the Bengali script), `oriya`, ... or `en`). Consumers must not assume a fixed set. `injury` and `injury_outcome` are NOT mandatory (ADR-050): `validation.mandatory_fields` is `incident_description`, `incident_location`, `date_time`; the dialogue never asks about injury and never flags it `missing_flagged`; it is filled when the narration says it (or an answer reveals it) and the user can set it on Review (`injury_outcome` moved to the "Situational" group there).

## `input.language` with a chosen spoken language (ADR-054)
When the user picked a language in Settings the speech model is told the language; `input.language` is then that name lowercased (`en` for English, e.g. `tamil`), not a model guess. With Auto-detect it stays the model-reported name or the script fallback (ADR-051). Free-form either way.

## Knowledge-graph documents (ADR-055, ADR-057)
The report schema is unchanged. The analysis writes its own documents under `files/kg/`: graph packs (`packs/<version>.json`, `GraphPack`), the audit log (`audit/<incidentId>.json`) and the dashboard export queue (`outbox/<incidentId>.json`, `IncidentAnalysisExport`). Their schemas, with examples, are in `doc/ADMIN_DASHBOARD_CONTRACT.md`; the node/edge conventions are in `doc/KNOWLEDGE_GRAPH.md`. Mapping from the report: `incident_type`, description and place text -> graph incident type, location type, assets, hazards, effects by the pack's keyword rules; `hazard_still_active` or status words -> `IncidentStatus`; `severity_level` -> severity; `injury` / `injury_outcome` -> injury; the number of persons marked injured -> `peopleAffected`; a SUBMITTED report -> `userConfirmed`. No reporter or person name is copied.

## Admin dashboard data (ADR-058)
The dashboard adds no stored schema. It reads reports, `files/kg/audit`, `files/kg/outbox`, `files/dialogue_logs/<id>.jsonl`, `files/eval_runs/<run>/results.json` and the active pack, and stores three small preferences (`admin_settings`: `pin`, `also_delete_kg`, `analysis_settings` as JSON of `AnalysisSettings`). Demo reports are normal reports whose `report_id` starts with `DEMO-`. `ReportRepository` gained `delete(reportId)`.
