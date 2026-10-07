---
name: visual-qa
description: Screenshot QA of AkaDoZe's look on the emulator — every activity at several font scales, RTL, the four time-of-day accent phases, the launch glow with animations on and off, seeded stats screens, dialogs, and contact sheets for review. Use this whenever a change touches what the app looks like (theme, colours, fonts, layouts, drawables, the ui/amber kit, dialogs, motion) and needs before/after evidence, when a visual-evaluation or UI repair ticket needs shots, or when someone asks to "check how it looks", re-shoot a screen, or verify a UI fix on the emulator. Not for driving a single functional flow (use android-emulator-qa) and not for root/Shizuku/OEM Doze behaviour, which needs a physical phone.
---

# Visual QA (AkaDoZe, XML Views)

JVM screenshot tests (below) catch theme, layout and font drift in Gradle; the emulator stays the
oracle for everything they can't draw. These scripts make a shoot repeatable: same activities, same
scales, same capture tricks, so two runs can be compared side by side. All scripts live in
`scripts/` next to this file; run them with `bash`.

## JVM screenshots (Roborazzi, no emulator)

```bash
bash .claude/kit/gradle-check.sh :app:verifyRoborazziDebug   # compare against the committed goldens
bash .claude/kit/gradle-check.sh :app:recordRoborazziDebug   # rewrite goldens after an intended change
```

`app/src/test/java/com/akylas/enforcedoze/ui/amber/AmberScreenshotTest.kt` renders with Robolectric
native graphics at `w411dp-h891dp-night-xxhdpi`: the kit components (AmberCardView with serif title,
filled and outlined AmberButton, an `Amber.treat` chip, MaterialSwitch on/off) at font scale 1.0 and
2.0, the same card + button under each `ThemeOverlay.Amber.Accent.*`, and AboutAppActivity and
SettingsActivity in non-root mode. Goldens live in `app/src/test/snapshots/` (committed); a failing
verify writes `_actual`/`_compare` PNGs to `app/build/outputs/roborazzi_compare/`. Read the compare
PNGs before re-recording, and read the new goldens after: a re-record is a visual change to review.

Roborazzi is pinned at 1.60.0: 1.61+ ship Kotlin 2.3 metadata the project's Kotlin 2.0 compiler
rejects. Still emulator-only: the glow and other shadows, the glass hairline (Robolectric draws
shadows poorly), haptics, RTL and accent-by-clock end to end (AccentThemer isn't registered under the
test Application), real dialogs, and every activity other than About and Settings.

## 1. Boot safely

```bash
bash .claude/skills/visual-qa/scripts/emu-boot.sh [avd]      # default AVD: pixel (API 36)
```

The machine has 15 GB. qemu grows to ~4.5 GB RSS even with `-memory 2048`, and it was OOM-killed
twice beside Gradle builds. The script therefore refuses (exit 3) while any Gradle/Kotlin daemon is
alive or under 6 GB is available. When it refuses, wait for your builds and executors' builds to
drain, then `./gradlew --stop; pkill -f KotlinCompileDaemon`, and retry. `--force` exists for the
case where you've checked yourself; don't use it to race a running build.

Only the orchestrator boots the emulator, one at a time. Build the APK *before* booting and copy it
somewhere stable (`/home/akane/AkaDoZe-amber-assets/qa/app-debug-<sha>.apk` is the convention), so
no Gradle run is needed while the emulator is up. Other AVDs: `pixel-api35`, `amber-api23` (minSdk
check: fonts fall back to default instances, accent applies via `onActivityCreated`, no glow).
Stop the pixel AVD before booting another (`adb emu kill`).

## 2. Shoot

```bash
S=.claude/skills/visual-qa/scripts
bash $S/qa-shots.sh <apk> <outdir> 1.0 1.3        # all 13 activities per font scale
bash $S/qa-full.sh  <apk> <outdir>                 # the full matrix below
```

`qa-shots.sh` installs with `-g`, grants `DUMP` (so Main and Monitor have content), runs Main once,
seeds synthetic stats via `seed-stats.sh`, then screenshots every activity as root so non-exported
ones start. First-run dialogs are left up on purpose: they're part of the review.

`qa-full.sh` adds, on top of scales 1.0/1.3/2.0:

| Output | How | Why this way |
|---|---|---|
| `rtl/` | `cmd locale set-app-locales <pkg> --locales ar` | `debug.force_rtl` only applies after a configuration change, so it silently produced LTR shots |
| `accent/` | `auto_time 0` + root `date` at 06:01 / 12:00 / 19:00 / 23:30 | the accent is chosen per activity creation from the hour |
| `glow/` | `glow-slow.sh`: 24 stills at `animator_duration_scale 10`, printing bottom-quarter warmth (mean R−B) | `screenrecord` only writes changed frames and the cold-start splash eats ~2 s, so it misses a 500 ms ramp |
| `glow-off/` | all three animation scales 0 | reduced motion must show no glow |

Everything is reset afterwards (font scale 1.0, en-US, auto_time 1, animation scales 1).

Dialogs: open them through the UI tree, not guessed coordinates. `tap.sh '<regex>'` taps the first
node whose text or content-desc matches; for exact matches and tree summaries use the
android-emulator-qa skill's `dump-ui` / `summarize-ui` / `pick`.

## 3. Review

```bash
python3 $S/sheet.py <sheet.png> 4 <outdir>/*_fs1.0.png    # 360px tiles, labelled
```

Read the sheets, not 40 separate PNGs: they make inconsistent spacing, a stray light surface or an
untinted control jump out. Compare against the previous run's sheet for the same scale. Shots go
under `/home/akane/AkaDoZe-amber-assets/shots/<step>/` (outside git; they're large).

Things a sheet can't tell you, so say so in the report instead of implying they passed: haptics,
OEM shadow rendering of the glow, root-mode screens, and anything Shizuku/Doze related. Those are
listed in `docs/device-test-1.11.0.md`.

## Limits of the evidence

- Stats data is synthetic (made-up levels and durations): judge layout, never the numbers.
- The emulator renders with SwiftShader/host GPU; colours are faithful, shadows/elevation are close
  but not what an OEM skin draws.
- One AVD per run means one density and one screen size unless you boot another.
