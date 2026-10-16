# Theming

Everything lives in ONE file: `ui/theme/Theme.kt`. Dark mode only. To restyle, edit the default values of the
token classes there. Access in code: `AppTheme.colors.primary`, `AppTheme.spacing.md`, `AppTheme.type.body`, ...
Rule: components never use `Color(0x..)` literals or raw `dp`/`sp` for styling (only token values; `0.dp`/alpha math
is fine). `res/values/themes.xml` only sets the dark window theme (no colors).

## Colors (`AppColors`)

| Token | Value | Purpose |
|---|---|---|
| `background` / `onBackground` | `#0E1116` / `#E8EDF4` | Screen background / main text |
| `surface` / `onSurface` | `#171C24` / `#E8EDF4` | Cards, sheets |
| `surfaceElevated` | `#202733` | Banners, secondary circle button, disabled buttons |
| `primary` / `onPrimary` | `#5AA9FF` / `#06121F` | Mic button, primary buttons, links |
| `primaryContainer` / `onPrimaryContainer` | `#16314F` / `#D3E6FF` | Selected chips |
| `mutedText` | `#9AA6B8` | Secondary text, hints |
| `border` | `#2C3544` | Card and button outlines |
| `error` / `onError` / `errorContainer` | `#FF7B7B` / `#2A0707` / `#3B1517` | Missing required fields, destructive actions |
| `warning` / `onWarning` | `#FFB84D` / `#2B1A00` | Low-confidence fields, "details left" |
| `success` / `onSuccess` | `#4CD08A` / `#04210F` | Done steps, success screen |
| `recordingActive` / `onRecordingActive` | `#FF4D4D` / `#FFFFFF` | Recording button, waveform, REC dot |
| `fieldAi` | `#B39DFF` | "AI-filled" chip |
| `fieldUserEdited` | `#5ED0C0` | "Edited" chip |
| `fieldUserVoice` | `#7FD4FF` | "Your answer" chip |
| `scrim` | `#B3000000` | Overlays (REC badge, sheets) |

Contrast (approx.): `onBackground` on `background` 15:1, `mutedText` on `background` 7:1, `onPrimary` on `primary` 8:1.
These are mapped into a Material3 `darkColorScheme` in `IncidentAssistantTheme` so stock M3 widgets follow the tokens.

## Typography (`AppTypography`)
`display` 34sp bold, `headline` 24sp semibold, `title` 18sp semibold, `body` 16sp, `bodyLarge` 20sp, `label` 14sp medium,
`caption` 12sp, `timer` 40sp monospace. All in `sp` so large system fonts scale them.

## Spacing (`AppSpacing`)
`xxs` 2, `xs` 4, `sm` 8, `md` 16, `lg` 24, `xl` 32, `xxl` 48 dp.

## Radii (`AppRadii`) and elevation (`AppElevation`)
Radii: `sm` 8, `md` 12, `lg` 20, `pill` 100 dp. Elevation: `none` 0, `low` 2, `medium` 6, `high` 12 dp (currently unused: flat design).

## Sizes (`AppSizes`)
`minTouch` 48, `micButton` 176, `homeMicTopGap` 72, `secondaryButton` 64, `shutterButton` 72, `icon` 24,
`iconLarge` 56, `micIcon` 72, `thumbnail` 88, `waveformHeight` 96, `waveformBar` 6, `borderWidth` 1,
`borderWidthStrong` 2, `statusDot` 10, `contentMaxWidth` 640 dp.

## Motion (`AppMotion`, ms)
`short` 150, `medium` 300 (screen cross-fade), `long` 600, `pulse` 1200 (recording pulse).
`AppTheme.reduceMotion` is true when the system animator duration scale is 0; the pulse and cross-fade are then disabled.

## How to change the palette
Edit the defaults in `AppColors` in `Theme.kt`. Nothing else needs to change. Keep `onX` colors readable on `X`.
