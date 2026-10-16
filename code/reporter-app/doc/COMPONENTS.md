# Reusable UI components

All under `ui/components/` unless noted. Every color/size/spacing comes from `AppTheme` (`THEMING.md`).

| Component | File | Parameters | Used by |
|---|---|---|---|
| `MicButton` | `MicButton.kt` | `onClick`, `recording`, `level` (0..1), `idleDescription`, `recordingDescription` (same size idle and recording) | Home, Clarification |
| `SecondaryCircleButton` | `MicButton.kt` | `icon`, `label`, `onClick`, `description` | Home (gallery) |
| `ProgressOrb` | `MicButton.kt` | none; same footprint as `MicButton`, shows a spinner | Home (processing mode) |
| `ProcessingSteps` | `ui/processing/ProcessingScreen.kt` | `phase`, `transcript?` | Home (processing mode), ProcessingScreen |
| `Waveform` | `Waveform.kt` | `level`, `active`, `bars` | Home (recording), Clarification |
| `formatElapsed(ms)` | `Waveform.kt` | ms -> `m:ss` | capture and clarification screens |
| `ScreenFrame` | `Common.kt` | `title?`, `leadingIcon`, `leadingDescription`, `onLeading?`, `trailing?`, content | every screen |
| `PrimaryButton` | `Common.kt` | `text`, `onClick`, `enabled` | Review, Success, Error |
| `TonalButton` | `Common.kt` | `text`, `onClick` (filled `primaryContainer`, weighty secondary action) | Clarification (skip) |
| `SecondaryButton` | `Common.kt` | `text`, `onClick`, `icon?` | Home, Review, Error |
| `LinkButton` | `Common.kt` | `text`, `onClick` | Clarification, field editors |
| `SurfaceCard` | `Common.kt` | `borderColor`, `borderWidth`, content | Review, Processing, History, Success |
| `NoticeBanner` | `Common.kt` | `message`, `onDismiss` | Home, Clarification, Review |
| `StepRow` / `StepStatus` | `Common.kt` | `label`, `detail?`, `status` (PENDING/ACTIVE/DONE) | Processing |
| `SourceChip` | `Common.kt` | `source` (`ai` / `user_voice` / `user_edit` / null = Empty) | Review |
| `CenteredMessage` | `Common.kt` | `title`, `body` | Error |
| `Thumbnail` | `Thumbnail.kt` | `item: MediaItem`, `index` | Home (attached gallery photos), Review |
| `FieldEditor` | `ui/review/FieldEditors.kt` | `field: IncidentField`, `entry: FieldEntry`, `onChange(JsonElement)` | Review |

Notes:
- `MicButton` pulses only when `recording` and not "reduce motion".
- `FieldEditor` keeps text in local state and pushes each change; do not re-key it by value.
- To add a new field kind: add to `FieldKind`, handle in `FieldEditor` and `FieldEntry.displayText`.

| `EmergencyContactsCard` | `EmergencyContactsCard.kt` | `contacts: List<EmergencyContact>` | Review, Success (severity High/Critical only) |

`EmergencyContactsCard` opens the dialer (`ACTION_DIAL`, `tel:` URI) so the user presses call; no CALL_PHONE permission.

## Review speaker button
`ReviewScreen(speaking, onReadAloud)`: an `IconButton` (`Icons.Filled.VolumeUp` / `Stop`, min touch size, `primary` / `error` tint tokens, content description "Read aloud" / "Stop reading aloud") in the header row beside the report ID. Driven by `IncidentFlowViewModel.readoutSpeaking` and `toggleReadout()`.

## Settings components
- `ui/settings/SettingsScreen`: full-screen settings (sections as `SurfaceCard`s; `ScreenFrame` with a back arrow). Private `SettingSwitch` (title, one-line detail, `Switch` in token colours, `enabled = false` dims it to 50 % and makes it inert), `SectionTitle`, `AboutLine`; speech-rate `FilterChip`s styled like the Review choice chips; `AboutInfo` (version, schema, AI, models state).
- `HomeScreen(onSettings)`: gear `IconButton` in the title row (null = hidden).
- `EmergencyContactsCard(title, severe)`: `severe = false` gives the neutral border and title used by Settings.
- `SecondaryButton(enabled)`: new optional parameter.

## Gallery (ADR-053)
`HomeScreen` gained `photos`, `importingPhotos`, `canDescribePhotos`, `onGallery`, `onClearPhotos`, `onPhotoOnly` (and lost `onCamera`): the gallery `SecondaryCircleButton` (`Icons.Filled.PhotoLibrary`, label "Gallery", 64 dp), a thumbnail row above the mic, "Preparing photos...", the primary "Use photos only" button directly under the thumbnails (ADR-062; shown when a model can describe photos) and "Remove photos". `media/ImageCompressor` (Android, decode + EXIF + 512 px + JPEG 80) and `media/PhotoPicks` (pure: at most 3 photos) are the non-UI parts; `CameraCaptureScreen` and CameraX were removed.

## Spoken language (ADR-054)
`LanguageChip(spokenLanguage, onClick)` (Home, under the mic text) and `SpokenLanguageSheet(languages, selected, onSelect, onDismiss)` (`ModalBottomSheet`, radio rows, used from the chip and from the Settings row); `HomeScreen(spokenLanguage: String?, onLanguage)` (null hides the chip); `SettingsScreen(spokenLanguage, languageEnabled, languageNote, onSpokenLanguage)`. Data: `domain/config/SpokenLanguages` (pure parser of `languages.json`), `UserSettings.spokenLanguage` / `forcedLanguage`.

## Post-incident analysis (ADR-055)
`AnalysisCard(state: AnalysisUiState)` in `ui/analysis/AnalysisCard.kt` (SurfaceCard, tokens only, collapsible); `SuccessScreen(report, analysis, onAnother)` shows it; `SettingsScreen` has the "Post-incident analysis" switch (`UserSettings.postIncidentAnalysis`). `AnalysisUiState` (`None`, `Running`, `Done(set)`, `Failed(message)`) lives in `IncidentFlowViewModel.kt`.

## Admin dashboard (ADR-058)
`ui/admin/`: `AdminDashboardScreen(state, vm, onExit)` (shell), `AdminPinDialog`, one file per tab (`AdminOverviewTab`, `AdminReportsTab` + `AdminReportDetail`, `AdminLogsTab`, `AdminDataTab` + `AdminDataSections` + `AdminDataForms`, `AdminSyncTab`, `AdminSettingsTab`), `AdminCharts` (Canvas `BarChart`, `HorizontalBars`), `AdminCommon` (`AdminSearchField`, `AdminChipRow`, `AdminPill`, `JsonBlock`, `AdminKeyValue`, `adminStatusColor`), `AdminShare` (system share sheet as text). `ui/admin/graph/`: `AdminGraphTab` (orchestration, layout stepping), `GraphScene` and `GraphCanvas` (drawing, gestures), `GraphControls` (search, filters, legend, highlight banner), `GraphNodeSheet`, `GraphPalette` (kind and relation colours from `AppTheme.colors.graphPalette`). Theme additions: `AppColors.graphPalette`, `AppTypography.code`. `HomeScreen(onAdmin)` adds the Admin icon.
