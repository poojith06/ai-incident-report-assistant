# Admin dashboard (in-app)

An offline admin area inside the app (ADR-058): counters and charts, all reports with their analysis, every log, an Obsidian-like
interactive graph of the knowledge graph, a knowledge editor, the dashboard outbox and admin settings. It reads the app's own files; no
network and no INTERNET permission. The future web dashboard consumes the same files (`doc/ADMIN_DASHBOARD_CONTRACT.md`).

## Opening it
Home (idle only) has an **Admin** icon next to the Settings gear. It asks for a 4-digit **PIN** (default `0000`, change it in Admin >
Settings). **The PIN is a demo gate, not real security**: it only keeps casual users out; it is stored unencrypted in the app's preferences
and all data is plain files. After 5 wrong entries the dialog pauses 30 s. Leaving the dashboard (back, lock icon) locks it and forgets what was loaded.

## Tabs
| Tab | What it shows |
|---|---|
| Overview | Counters (reports total, last 7 and 30 days, by severity and type, analyses run, abstained, suppressed recommendations, outbox pending, demo reports, graph version, nodes, edges, sources, logs, eval runs); a bar chart of reports per day (last 14 days) and per type (Canvas, no chart library); graph health: expired sources, sources expiring within 30 days, unverified campus and team nodes, non-approved recommendations. |
| Reports | List with search, type, severity, date range and demo/real filters. A report opens to its full detail: fields with source and confidence, photos, transcript and input language, dialogue Q and A and log, severity reasoning, and the analysis (recommendations with rank, score, source, provenance; consequences; arrangements; teams; the one question; suppressed candidates with reasons). **Show in graph** opens the graph on that incident's path; **Run analysis** (re)runs it. |
| Logs | Sub-tabs: Analysis audit (`files/kg/audit`), Dialogue (`files/dialogue_logs/*.jsonl`, one entry per turn), Eval runs (`files/eval_runs`), Outbox. Search, level (info, warn, error) and type filters, expandable JSON rows, share one entry or a whole bundle (system share sheet, plain text, cut at 200,000 characters), Clear logs with confirmation. Unreadable log lines show as ERROR rows instead of vanishing. |
| Graph | The interactive graph (below). |
| Graph data | Knowledge editor: browse and search nodes, edges, recommendations, sources, teams, contacts; create, edit and disable through forms (field validation, then dangling-reference and cycle validation by the service); approve a recommendation (reviewer required, source must be approved, optional expiry); attach a source with expiry; verify a location or team; **Preview** (pick incident type, location type, severity, status, assets, hazards, injury and see the REPORTER and ADMIN_PREVIEW results side by side, then show either path in the graph); **Import / export** (system document picker, validation, diff summary added/changed/removed, confirm); **Versions** with rollback. Every change creates a new graph version. |
| Outbox | Queued exports (`files/kg/outbox`) with a QUEUED status, per-item share, export all as a bundle, clear. Network sync is future work: nothing is sent. |
| Settings | Change PIN; analysis settings (abstention threshold, output limits, ranking weights) with validation, save and reset; "also delete analysis files when deleting reports" (default on, fixes the old gap); **Generate demo history** (about 30 synthetic reports, ids `DEMO-nnnn`, also analysed) and **Remove demo data**; dev info (graph version, app version, schema). |

## The graph
Canvas with a pure Kotlin Fruchterman-Reingold layout (`knowledgegraph/admin/graphview/ForceLayout`): deterministic, bounded, stepped a few iterations per frame, capped at 300 iterations, positions cached per graph version so a filter change does not scramble the picture. Only the visible subgraph is laid out (cap 400 nodes: the highlighted, matched and focused nodes first, then the busiest).
- Nodes are coloured by kind (legend), sized by degree; SOP sources are drawn as nodes too. Edges are thin relation-coloured lines with an arrowhead near the target. Labels appear when zoomed in, or always for selected, matched, highlighted and neighbour nodes.
- **Gestures**: drag empty space = pan; pinch = zoom; tap = select a node (opens the node sheet); tap empty space = deselect; long-press a node = pin or unpin; drag a node = move it (stays pinned); double-tap a node = zoom to it and focus it.
- **Controls**: search (matches highlighted, the rest dimmed), filters by node kind and relation, "only approved and active", show orphans, show suppressed candidates, **Global / Local** with a depth slider 1 to 3 around the selected node (like Obsidian's local graph), Reset view (fit, clear pins), Re-layout, a legend.
- **Node sheet**: properties, verification and approval status, version, source, incoming and outgoing links (tap to jump), "Local graph around this node", "Focus".

### How highlighting is derived
- **Incident path**: `IncidentPath.fromAudit(pack, auditLog)` (ADR-058). The engine already stores, for every shown item, its traversed path as text (`incident-type:x -TRIGGERS_ACTION-> action:y [edge:id]`). The class parses it, matches every hop to a pack edge by (source, relation, target), and adds the incident, location and asset type nodes, the recommendation record of each action with its SUPPORTED_BY edge and source, and the owning team. Everything else is dimmed; the path glows. No schema change was needed, so old audit logs and exports work.
- **Suppressed candidates** (the audit's suppressed ids) are drawn in the warning colour when they exist in the pack (nodes or edges).
- **Selected neighbourhood**: the node and its direct neighbours and links.
- **Preview**: the same derivation from a live `RecommendationSet` (`IncidentPath.fromPreview`), for the reporter or the admin audience.

## Architecture
Pure and JVM-tested: `admin/` (AdminState and AdminReducer, OverviewStats, ReportFilters, LogParser, ImportDiff, DemoData, AdminPin, AnalysisSettings, EditorForms and EditorRows, PreviewSpec), `knowledgegraph/admin/graphview/` (ForceLayout, GraphSubgraph, IncidentPath). Android: `AdminBackend` (the only file/store access: snapshot, clear, demo data, preview), `AdminViewModel` (state through the reducer), and the Compose tabs in `ui/admin/` (shell `AdminDashboardScreen`, one file per tab) and `ui/admin/graph/` (canvas, controls, node sheet). Theme tokens only (`graphPalette`, `code` style added to `Theme.kt`).

## Demo data
"Generate demo history" writes about 30 reports with ids `DEMO-0001`... (the report model has no spare field, so the id prefix is the flag), spread over the last 55 days; the first four are water leaks in the same washroom so the recurrence rule fires. They are analysed in date order (audit logs and outbox exports are created). "Remove demo data" deletes exactly the `DEMO-` reports with their audit, outbox and dialogue files; real reports are never touched. Demo reports also show in Past reports until removed.
