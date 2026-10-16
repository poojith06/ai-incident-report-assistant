# IIIT-H demo knowledge-graph seed pack

`seed_pack.json` is a `GraphPack` (schema version 1) for the offline post-incident knowledge graph. It is **generated** by
`tools/kg_seed/build_seed_pack.py`; do not hand-edit it. Everything in it is generic demo guidance that an IIIT-H administrator must
verify. Every node is `NEEDS_ADMIN_VERIFICATION`.

## What is NOT seeded
No phone numbers or contacts, no capacities, no room numbers, no coordinates, no exits or assembly points, no asset inventories, no
named people and no teams beyond generic role labels. Texts contain no digits. Temporary arrangements are generic requests
("Ask the academic office to identify an alternate room of suitable size") that name no room; one extra arrangement requires
`knownCapacity` and `alternateLocationVerified` and is therefore suppressed until campus data supplies those facts.
Use the CSV/JSON files in `templates/` to supply verified data.

## Demo source policy
`sop:demo-seed:v1` is `APPROVED` with `approvedBy = "demo-seed (not a real SOP)"` so that ordinary users can see the demo
recommendations (shown with the label "Demo guidance - pending IIIT-H admin approval"). It is **not** a real IIIT-H procedure.
To replace it an admin must: (1) attach real SOPs as sources (`templates/sop_sources.csv`, with approver, approval time, optional
expiry); (2) re-point each recommendation's `SUPPORTED_BY` edge and `sourceId` to the real source and set `approvedBy`/`approvedAt`;
(3) set the demo source to `EXPIRED` or `REJECTED` (its recommendations then disappear for ordinary users). The ten placeholder
sources (`sop:fire-safety:v1`, ...) are `NEEDS_ADMIN_VERIFICATION`; the minority of recommendations that need a specific real
procedure ("follow the approved fire evacuation procedure", "approved LPG isolation procedure", "chemical specific procedure", ...)
cite them with `approvalStatus = DRAFT` and are visible only in the admin preview.

## Conventions
Ids: `incident-type:<slug>`, `location-type:`, `asset-type:`, `hazard:`, `consequence:`, `action:<slug>`, `rec:<slug>` (one per action,
same slug), `arrangement:`, `team:`, `pattern:`, `building:`, `campus:`. Incident, location, asset, hazard and action nodes are found by
their `code` property; edge conditions and normalization rules use **codes**.

| Node type | properties |
|---|---|
| INCIDENT_TYPE | code, category (FIRE/ELECTRICAL/WATER/MEDICAL/CHEMICAL/PROPERTY/IT/FOOD/OUTDOOR_SECURITY), description |
| LOCATION_TYPE | code, category (ACADEMIC/TECHNICAL/RESIDENTIAL/FOOD/HEALTH_SUPPORT/PUBLIC/OUTDOOR) |
| ASSET_TYPE | code |
| HAZARD | code, hazardClass, activeRisk |
| CONSEQUENCE | consequenceType (SAFETY/ASSET/OPERATIONAL/SECONDARY), impactClass, text, observedWhenEffect? |
| ACTION | title, phase, userCanPerform, requiresAuthorization, priority, canonicalText, canonicalKey, orderClass, medicalClass?, forbiddenForReporter?, requiresHumanApproval |
| RECOMMENDATION | actionId, approvalStatus, approvedBy, approvedAt, sourceId (+ title, phase) |
| TEMPORARY_ARRANGEMENT | title, text, requirements {knownCapacity?, alternateLocationVerified?}, ownerTeamId |
| RESPONSE_TEAM | role, label |
| HISTORICAL_PATTERN | scope, incidentTypes, windowDays, threshold, hourWindow?, description |
| BUILDING | aliases, kind |

| Edge (whitelisted path) | Notes |
|---|---|
| INCIDENT_TYPE -TRIGGERS_ACTION-> ACTION | conditions: `statuses` (immediate/operational: all four; recovery: CONTAINED, RESOLVED; prevention: + UNKNOWN), `injury`, ... |
| INCIDENT_TYPE -PRODUCES_HAZARD-> HAZARD -MAY_CAUSE-> CONSEQUENCE -REQUIRES_ACTION-> ACTION | consequence wording is "may"/"could" |
| LOCATION_TYPE -HAS_CONTINUITY_OPTION-> TEMPORARY_ARRANGEMENT | conditions: incidentTypes |
| ASSET_TYPE -REQUIRES_ACTION-> ACTION | conditions: incidentTypes |
| ACTION -OWNED_BY-> RESPONSE_TEAM, ACTION -DEPENDS_ON-> ACTION (target = prerequisite, acyclic) | |
| RECOMMENDATION -SUPPORTED_BY-> source id | target is a `KgSource` id, not a node |
| HISTORICAL_PATTERN -TRIGGERS_ACTION-> ACTION | e.g. 3 water incidents in one zone in 60 days -> inspect the shared supply and drain path |
| campus -CONTAINS-> building | |

Condition semantics: lists are "any of" (`hazardsAll` = all of); a condition on an unknown fact fails, so the engine abstains.

## Safety rules for texts (enforced by the generator self-check)
Reporter-facing actions (`userCanPerform = true`) are only self-protective: move away, stay away, keep others away, report to a role,
wait for responders. Everything operational is `userCanPerform = false`, `requiresAuthorization = true` and phrased "Ask authorized
... to ...". Never tell the reporter to enter a danger area, fight a fire, touch electrical equipment, handle chemicals, operate
utility shutoffs, power on damaged equipment or clean a spill. No medical advice, diagnosis or first-aid steps: the only medical
actions are "contact campus medical services or qualified emergency responders and follow their instructions" style. No digits, phone
numbers, capacities, room numbers or claims that help was dispatched. One sentence per `canonicalText`.
Incident type `SUSPECTED_BONE_INJURY` is the taxonomy's "suspected fracture" (the word is kept out of the pack).

## Add a scenario
1. Add the `INCIDENT_TYPE` in `INCIDENT_TYPES` and its hazards in `TYPE_HAZ` (plus consequences in `HAZ_CONS` if new).
2. Add actions with `act(...)`/`A(...)` (a recommendation, `SUPPORTED_BY` edge and `OWNED_BY` edge are created automatically).
3. Wire them with `wire(...)`/`trig(...)` (and asset/location edges in `ASSET_ACT`/`CONT`).
4. Add normalization rules (`TYPE_RULES`, location/asset/hazard/status rules) so a report maps to the new codes.
5. Add the report to `SCENARIOS` / `EXPECTED_TYPES`.
6. Run the generator; fix every self-check error.

## Regenerate
`python tools/kg_seed/build_seed_pack.py` (rewrites `seed_pack.json` and `app/src/test/resources/kg/scenarios.json`; the committed
files must equal its output). It prints node/edge/recommendation counts and exits non-zero if the self-check fails.
