i want u to implement a graph like system for the llm to do post incident analysis.
this is the design details.
```
# IIIT-H Incident Knowledge Graph — Agent Implementation Specification
## 1. Mission
Implement an offline knowledge-graph subsystem for the existing QIDK incident-reporting Android app.
The graph must convert a validated incident into grounded, auditable post-incident consequences, recovery steps, temporary arrangements, responsible teams, and recurrence-prevention suggestions.
Do not modify the existing ASR, extraction, severity, follow-up, emergency-calling, or report-submission pipeline except through clean interfaces.
Core principle:
> The graph selects approved facts and actions; the LLM may only rephrase them. Kotlin validates every output.
## 2. Existing integration assumptions
- Android 15 app using Kotlin, Jetpack Compose, MVVM, Room/SQLite, JNI, QAIRT 2.45 and Genie.
- Qwen3-4B runs on the Snapdragon Hexagon NPU and extracts structured incident JSON.
- Deterministic Kotlin owns grounding, severity, urgency, report state and safety decisions.
- Knowledge-graph inference must work fully offline.
- Do not require a cloud graph database or cloud LLM.
Expected input after report verification:
```json
{
  "incidentId": "INC-2026-0001",
  "type": "COMPUTER_FIRE",
  "severity": "HIGH",
  "urgency": "U3",
  "status": "CONTAINED",
  "locationId": "room:krb:lab:unknown",
  "locationType": "COMPUTER_LAB",
  "affectedAssets": ["DESKTOP"],
  "activeHazards": ["SMOKE", "ELECTRICAL_DAMAGE"],
  "peopleAffected": 0,
  "operationalEffects": ["WORKSTATION_UNAVAILABLE"],
  "evidenceConfidence": 0.91,
  "timestamp": "ISO-8601",
  "userConfirmed": true
}
```
Expected output:
```json
{
  "immediateActions": [],
  "consequences": [],
  "operationalSteps": [],
  "recoverySteps": [],
  "temporaryArrangements": [],
  "preventiveActions": [],
  "responsibleTeams": [],
  "unresolvedQuestions": [],
  "recommendationIds": [],
  "graphVersion": 1,
  "warnings": []
}
```
## 3. Non-negotiable safety rules
- Never diagnose disease or injury.
- Never recommend medicines, dosages, treatment plans, or home remedies.
- Medical output is limited to approved immediate first aid, contacting qualified responders, and following responder instructions.
- Never claim that help was dispatched unless an external system confirms it.
- Never invent phone numbers, locations, policies, room capacities, exits, equipment, or response teams.
- Campus contacts must be administrator-entered and marked verified before use.
- Never tell an untrained user to enter danger, fight a fire, handle chemicals, touch electrical equipment, or operate utility shutoffs.
- Human safety has priority over evidence, assets, service continuity, and cleanup.
- Recommendations with unverified or expired sources must not be shown to ordinary users.
- If required facts are missing, abstain and return an unresolved question.
Medical footer for any injury case:
> This is immediate safety guidance, not medical advice. Contact campus medical services or qualified emergency personnel.
## 4. Scope
Implement:
1. Campus/location ontology.
2. Incident, hazard, consequence, action and team ontology.
3. Versioned Room/SQLite graph storage.
4. Seed data for realistic IIIT-H location types and incident families.
5. Deterministic traversal, filtering, ranking and explanation.
6. Historical recurrence queries.
7. Grounded LLM-rephrasing interface with validation and template fallback.
8. Admin CRUD/import/export and approval-state primitives.
9. Unit tests and sample end-to-end cases.
Do not implement in this task:
- ASR, image inference, TTS, maps, emergency calling or dynamic questions.
- A medical expert system.
- Live scraping into production data.
- Automatic activation of scraped phone numbers or safety procedures.
## 5. IIIT-H environment model
Create `Campus -> Building -> Floor -> Zone -> Room` hierarchy.
Seed only broad, non-sensitive campus entities; mark uncertain entities `NEEDS_ADMIN_VERIFICATION`.
Top-level location classes:
- Academic/research: Nilgiri, Himalaya, Kohli Research Block, classrooms, seminar rooms, auditoriums.
- Technical: computer labs, research labs, electronics labs, robotics spaces, server/network/electrical rooms.
- Residential: hostels, rooms, corridors, stairs, common rooms, washrooms, lifts and pantries.
- Food: messes, kitchens, dining areas, canteens, stores and cold storage.
- Health/support: campus health facility, security, facilities, maintenance and IT support.
- Public: library, shops, ATMs, laundry and reception areas.
- Outdoor: football ground, courts, roads, footpaths, parking and open grounds.
Do not guess exact coordinates, room numbers, capacities, exits, contacts or asset inventories. Provide import templates so IIIT-H administrators can add them.
## 6. Node types
Implement these enums and schemas:
- `Campus`: id, name, verificationStatus.
- `Building`: id, name, latitude?, longitude?, managerTeamId?, verificationStatus.
- `Floor`: id, buildingId, label, planAssetUri?, verificationStatus.
- `Zone`: id, floorId, name, zoneType, occupancyType.
- `Room`: id, zoneId, name, roomType, capacity?, criticality, active.
- `Asset`: id, roomId, assetType, ownerTeamId?, criticality, active.
- `Utility`: id, utilityType, ownerTeamId?, active.
- `IncidentType`: id, name, category, description.
- `Hazard`: id, name, hazardClass, activeRisk.
- `Consequence`: id, name, consequenceType, impactClass.
- `Action`: id, title, phase, userCanPerform, requiresAuthorization, priority.
- `TemporaryArrangement`: id, title, requirementsJson, ownerTeamId.
- `ResponseTeam`: id, name, role, active.
- `EmergencyContact`: id, teamId, number, verifiedAt, expiresAt, enabled.
- `SopSource`: id, title, version, uri, approvedBy, approvedAt, expiresAt, status.
- `Recommendation`: id, title, phase, actionId, sourceId, approvalStatus.
- `Incident`: existing report reference plus graph-normalized fields.
- `HistoricalPattern`: id, scopeId, incidentTypeId, windowDays, threshold.
- `Evidence`: id, incidentId, sourceType, confidence, verificationStatus.
Enums:
```text
VerificationStatus = VERIFIED | NEEDS_ADMIN_VERIFICATION | REJECTED
ApprovalStatus = DRAFT | REVIEWED | APPROVED | EXPIRED | DISABLED
Phase = IMMEDIATE | OPERATIONAL | RECOVERY | PREVENTION
IncidentStatus = ACTIVE | CONTAINED | RESOLVED | UNKNOWN
```
## 7. Edge types
Support typed, directed edges:
```text
CONTAINS
CONNECTED_TO
HAS_EXIT
SERVED_BY
DEPENDS_ON
OCCURRED_AT
INVOLVES
PRODUCES_HAZARD
MAY_CAUSE
TRIGGERS_ACTION
REQUIRES_ACTION
OWNED_BY
BLOCKED_BY
REPLACES
SUPPORTED_BY
SIMILAR_TO
OBSERVED_AT
ESCALATES_TO
AFFECTS
HAS_CONTINUITY_OPTION
```
Each edge stores conditions, priority, source, approval status and version.
Example:
```json
{
  "id": "edge:computer-fire:electrical-inspection",
  "sourceId": "incident-type:computer-fire",
  "relation": "REQUIRES_ACTION",
  "targetId": "action:electrical-inspection",
  "conditions": {
    "statuses": ["ACTIVE", "CONTAINED", "RESOLVED"],
    "locationTypes": ["COMPUTER_LAB", "SERVER_ROOM", "CLASSROOM"],
    "minSeverity": "MEDIUM"
  },
  "priority": 90,
  "approvalStatus": "APPROVED",
  "sourceIdRef": "sop:electrical-safety:v1"
}
```
## 8. SQLite schema
Create Room entities and migrations for:
```sql
kg_node(id TEXT PRIMARY KEY, type TEXT, name TEXT, properties_json TEXT,
        verification_status TEXT, version INTEGER, active INTEGER);
kg_edge(id TEXT PRIMARY KEY, source_id TEXT, relation TEXT, target_id TEXT,
        conditions_json TEXT, priority INTEGER, source_ref TEXT,
        approval_status TEXT, version INTEGER, active INTEGER);
kg_source(id TEXT PRIMARY KEY, title TEXT, version TEXT, uri TEXT,
          approved_by TEXT, approved_at TEXT, expires_at TEXT, status TEXT);
kg_incident_link(incident_id TEXT, node_id TEXT, relation TEXT,
                 confidence REAL, provenance TEXT, user_confirmed INTEGER);
kg_recommendation_log(id TEXT PRIMARY KEY, incident_id TEXT, graph_version INTEGER,
                      recommendation_ids_json TEXT, suppressed_json TEXT,
                      created_at TEXT, user_confirmed INTEGER);
```
Add indexes on node type, edge source/relation, edge target, incident ID, approval status and source expiry.
## 9. Seed incident taxonomy
Seed these incident families:
- Fire/smoke: classroom, hostel, computer, server rack, kitchen and electrical-panel fire.
- Electrical: sparking socket, exposed wire, overloaded extension, water near power, UPS overheating.
- Water/plumbing: pipe leak, ceiling leak, washroom flooding, blocked drain, drinking-water concern.
- Medical/injury: fall, collision, bleeding, unconscious person, suspected fracture, burn and heat illness.
- Chemical/lab: spill, fumes, gas leak, broken container, battery swelling and robotics malfunction.
- Property: broken glass, damaged furniture, ceiling damage, door/lock failure and falling object.
- IT/infrastructure: server overheating, cooling failure, storage failure, network outage and power outage.
- Food/mess: LPG smell, cooking fire, hot-oil spill, refrigeration failure and suspected contamination.
- Outdoor/security: road accident, fallen branch, poor lighting, theft, altercation, trapped person and blocked exit.
## 10. Core graph paths
Generate candidates only through these paths:
```text
IncidentType -> TRIGGERS_ACTION -> Action
IncidentType -> PRODUCES_HAZARD -> Hazard
Hazard -> MAY_CAUSE -> Consequence
Consequence -> REQUIRES_ACTION -> Action
LocationType -> HAS_CONTINUITY_OPTION -> TemporaryArrangement
AssetType -> REQUIRES_ACTION -> Action
Action -> OWNED_BY -> ResponseTeam
Recommendation -> SUPPORTED_BY -> SopSource
HistoricalPattern -> TRIGGERS_ACTION -> Action
```
## 11. Consequence model
Represent consequences in four groups:
- Safety: exposure, blocked evacuation, continuing hazard.
- Asset: equipment damage, data loss, structural damage.
- Operational: room closure, class disruption, service outage, reduced capacity.
- Secondary: smoke spread, water reaching electricity, contamination, repeated failure.
Never state a consequence as certain unless directly observed or confirmed. Use `POSSIBLE`, `LIKELY`, or `CONFIRMED` and preserve provenance.
## 12. Recommendation engine
Create `KnowledgeGraphEngine.recommend(ValidatedIncident)`.
Algorithm:
1. Reject unverified incident input.
2. Resolve incident type, location type, hazards, assets and status to graph nodes.
3. Traverse only whitelisted path patterns.
4. Load candidate actions, consequences, teams and temporary arrangements.
5. Apply edge conditions.
6. Remove unapproved, expired, disabled or sourceless recommendations.
7. Enforce authorization constraints.
8. Enforce safety and medical filters.
9. Apply prerequisite ordering.
10. Rank results.
11. Deduplicate semantically equivalent actions using canonical IDs.
12. Limit output by phase.
13. Return IDs, source references, provenance and unresolved questions.
14. Persist an audit log.
Ordering:
```text
life safety > hazard isolation > responder escalation > service continuity
> evidence preservation > restoration > recurrence prevention
```
Initial ranking score:
```text
0.30 * graphPriority
+ 0.20 * severityRelevance
+ 0.15 * locationMatch
+ 0.15 * assetMatch
+ 0.10 * recurrenceScore
+ 0.10 * evidenceCompleteness
```
Treat the weights as configurable, not learned truth.
Output limits:
- 3 immediate actions.
- 3 operational steps.
- 3 recovery steps.
- 2 temporary arrangements.
- 2 preventive actions.
- 1 highest-priority unresolved question.
## 13. Hard filters
Suppress a candidate if:
- Required preconditions are absent.
- It conflicts with incident status.
- It asks the user to perform an authorized-only action.
- Its SOP is not approved or is expired.
- It provides diagnosis, medication or treatment.
- It requires entering an unsafe area.
- A prerequisite action is incomplete.
- Location or asset identity is too uncertain.
- It contains an unverified phone number.
Return every suppression reason in the admin audit log.
## 14. Grounded LLM phrasing
The LLM receives only graph-approved statements and IDs. It must not receive permission to add advice.
Prompt contract:
```text
Rewrite only APPROVED_FACTS into concise user-facing steps.
Do not add actions, causes, numbers, locations, medical guidance or safety claims.
Preserve owner roles, uncertainty and prerequisites.
Return JSON with recommendation_id and rendered_text.
```
Validate by requiring exactly one output item per supplied recommendation ID. Reject unknown IDs, missing IDs, extra numbers, new named entities or prohibited medical terms. On failure, display canonical graph text.
## 15. History-aware reasoning
Implement deterministic recurrence queries:
- Same incident type at same room/zone/building within N days.
- Same asset with repeated failures.
- Same utility branch with repeated incidents.
- Repeated incomplete corrective action.
- Same incident cluster during a similar time window.
Example rule:
```text
IF WATER_LEAK count >= 3 in same zone within 60 days
THEN suggest inspection of the shared supply/drain path
INSTEAD OF only repeating fixture-level repair.
```
Every history suggestion must list supporting incident IDs and dates. Do not use personal medical history or protected characteristics.
## 16. Required seed scenarios
Implement graph coverage and tests for:
1. Classroom fire -> room closure, alternate classroom, fire/electrical inspection, reopening clearance.
2. Computer fire -> isolate asset, IT/electrical inspection, preserve logs/backups, replacement workstation.
3. Server cooling failure -> infrastructure escalation, service-impact assessment, continuity plan, cooling validation.
4. Water near power strip -> exclusion zone, authorized power isolation, plumbing/electrical clearance.
5. Repeated washroom leak -> slip controls, repair, inspect adjoining areas, shared-line inspection after recurrence.
6. Hostel smoke -> evacuation/escalation nodes, room closure, inspection and temporary accommodation path.
7. Mess LPG smell -> exclusion/escalation, authorized inspection and service suspension until clearance.
8. Football injury -> stop play, contact qualified help, no diagnosis, inspect field/equipment.
9. Broken classroom furniture -> remove from use, inspect similar items, update available capacity.
10. Chemical spill -> isolate, use chemical-specific approved SOP, specialist response, no generic cleanup advice.
11. Blocked fire exit -> remove obstruction through responsible team, inspect similar routes, compliance follow-up.
12. Network/storage outage -> identify services, invoke continuity, preserve logs, verify restoration.
## 17. Example recommendation record
```json
{
  "id": "rec:computer-fire:preserve-data",
  "title": "Preserve recoverable data and system evidence",
  "phase": "RECOVERY",
  "conditions": {
    "incidentTypes": ["COMPUTER_FIRE"],
    "statuses": ["CONTAINED", "RESOLVED"],
    "assetTypes": ["DESKTOP", "WORKSTATION", "SERVER"]
  },
  "canonicalText": "Ask authorized IT staff to preserve logs and available backups before restoration.",
  "forbiddenForReporter": ["POWER_ON_DAMAGED_DEVICE", "CHANGE_PRODUCTION_RETENTION_DIRECTLY"],
  "ownerTeam": "IT_OPERATIONS",
  "requiresHumanApproval": true,
  "sourceStatus": "NEEDS_ADMIN_VERIFICATION"
}
```
## 18. Admin workflow
Provide repository/service APIs for:
- Import/export versioned JSON graph packs.
- Create/update/disable nodes and edges.
- Attach an SOP source and expiry.
- Approve recommendations.
- Verify campus locations and teams.
- Validate dangling references and cycles in prerequisites.
- Preview recommendations for a sample incident.
- Roll back to a previous graph version.
Production rule: a recommendation cannot become `APPROVED` without a source, reviewer, approval time and optional expiry.
## 19. Source and verification policy
Use public IIIT-H information only to create broad campus categories. Do not treat public webpages as authority for current emergency procedures, private floor plans, phone numbers or equipment locations.
Create CSV/JSON templates for administrators to verify:
- Buildings, floors, rooms and coordinates.
- Room type and capacity.
- Exits, assembly points and accessibility routes.
- Responsible teams.
- Emergency contacts.
- Approved SOPs.
- Temporary classroom/lab alternatives.
- Utility ownership and asset inventory.
## 20. Testing
Create unit tests for:
- Every edge condition operator.
- Approval/expiry filtering.
- Medical prohibition rules.
- Authorization filtering.
- Prerequisite ordering.
- Recommendation limits and deduplication.
- Recurrence detection.
- Unknown location and unknown asset abstention.
- LLM output ID validation.
- Audit-log completeness.
- Graph import validation and rollback.
Required negative tests:
- Do not advise restarting fire-damaged equipment.
- Do not advise touching an electrical device near water.
- Do not diagnose a sports injury.
- Do not provide medicine or dosage.
- Do not expose hostel-room or injured-person identity to normal users.
- Do not show expired SOP guidance.
- Do not activate an unverified contact.
- Do not invent a replacement classroom when capacity data is unknown.
## 21. Deliverables
Produce:
```text
knowledgegraph/
  model/
  db/
  repository/
  engine/
  rules/
  history/
  llmgrounding/
  admin/
  seed/
  tests/
```
Files/artifacts:
- Kotlin node/edge/domain models.
- Room entities, DAOs and migration.
- `KnowledgeGraphEngine` and interfaces.
- `SafetyPolicy` and `MedicalOutputGuard`.
- `HistoryPatternDetector`.
- `GroundedRecommendationRenderer`.
- Seed graph JSON for the 12 scenarios.
- Admin import/export schema.
- At least 40 unit tests.
- README with integration instructions.
- Sample output and audit logs.
## 22. Definition of done
The module is complete when:
- It runs offline on the QIDK Android app.
- A verified incident produces deterministic, source-linked suggestions.
- Every visible suggestion maps to an approved recommendation ID.
- Unknown facts cause abstention, not invention.
- Medical cases never produce diagnosis, medication or treatment plans.
- History recommendations cite supporting incident IDs.
- Admins can update and roll back the graph without rebuilding the app.
- All required positive and negative tests pass.
- Existing incident extraction and submission behaviour remains unchanged.

```
how to implement this figure it out on your own. i want atleast a basic version running. in the future we want to implement an admin dashboard so there should be a way to send data to there. the engineering or like the json output those details u can change/optimise according to what works the best. 