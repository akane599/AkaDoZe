# Modules and Files

*Last Updated: 2026-10-07*

Single Gradle module `:app`, base package `com.akylas.enforcedoze` at
`app/src/main/java/com/akylas/enforcedoze/`: 128 sources (73 Java, 55 Kotlin) in eight packages.

## Root package (43 Java, 2 Kotlin): Android shell

| Role | Files |
|------|-------|
| Service | `ForceDozeService.java` (foreground lifecycle, receivers, admission, engine wiring; teardown in `runTeardown` / `finishTeardown` / `awaitTeardown` / `reportTeardownWait`; enter, reapply and idle-change paths split into named helpers behind five overridable adapters: `enterCore`, `enterGroupsSafely`, `maintenance`, `musicListener`, `requestPlayingPackage`) |
| App | `MyApplication.java` (context, lazy `getDozeRuntime()` that also attaches `NoticeSink`, app-owned lazy `getJournal()`; project SAMs `Factory`/`Callback` because `java.util.function` is API 24+) |
| Automation | `ExternalControlReceiver.java` (abstract trust/execution boundary; an unexpected failure is journaled as `INTERNAL_ERROR`; ENABLE starts the service first and persists intent only after the start is accepted, stopping that start if the write fails) and its aliases `EnableForceDozeService`, `DisableForceDozeService`, `ReenterDoze`, `AddWhiteListReceiver`, `RemoveWhiteListReceiver`, `SettingsChangeReceiver` |
| System receivers | `BootCompleteReceiver`, `AutoRestartOnUpdate` (both run a restore-only window when the service is off and the ledger has work), `CustomDozePeriodReceiver`, `ExactAlarmPermissionReceiver` (re-arms the next boundary after an exact-alarm grant change), `InternalEnableReceiver` (non-exported target of the app's own "tap to enable" notification) |
| Screens | `MainActivity`, `SettingsActivity`, `DozeTunablesActivity`, `WhitelistAppsActivity`, `BlockAppsActivity`, `BlockNotificationsActivity`, `DozeBatteryStatsActivity`, `DozeStatsActivity`, `LogActivity`, `TaskerBroadcastsActivity`, `PackageChooserActivity`, `AboutAppActivity`, `RequestIgnoreBatteryActivity` |
| Adapters / items | `AppsAdapter`+`AppsItem`, `BatteryConsumptionAdapter`+`BatteryConsumptionItem`, `DozeStatsAdapter`+`DozeStatsCard`, `TaskerBroadcastsAdapter`+`TaskerBroadcastsItem` |
| Tunables | `DozeTunableHandler.java` (typed `ApplyResult` with readback), `DozeTunableConstants.java` |
| Tiles / listener | `ForceDozeTileService`, `AirplaneTileService`, `NotificationService.kt` (media-playing app) |
| Helpers | `Utils.java` (service start, schedules, permissions, pref repair), `NumberPickerPreference.java`, `MaterialListPreference.kt`, `CustomTabs.java`, `ChromePackageHelper.java` |

## `access/` (14 Kotlin): transports and capabilities

`AccessManager.kt` (singleton: access level, grants, transports, lanes) · `AccessResolution.kt` (bounded cold-start
discovery: 10 s Shizuku window, root 1+3 probes, binder-seen facts, fresh root budget on service attach) · `AccessModels.kt` (AccessLevel, Feature, Reason,
Grants, CommandResult, FeatureStatus) · `CapabilityResolver.kt` (capability matrix, `PackageNames` validator) ·
`CommandCatalog.kt` (per-API mutation / original / readback commands) · `CommandLane.kt` (FIFO lanes with timeouts and
deadlines) · `RootCommandRunner.kt` (persistent libsuperuser shell behind the `RootSession` seam) · `ShellCommandRunner.kt` (app `sh` or Shizuku
process) · `GrantCommands.kt` (permission / appops / listener / whitelist helpers; no READ_LOGS) · `HelperGrantPolicy.kt` (AUTOMATIC vs EXPLICIT
selection, persist-before-grant attempts, forget-before-revoke, typed `NotRunReason` when a run is skipped) · `ExternalControlPolicy.kt` (gates,
scalar allowlist; protected keys such as the sensor allow-token pref are refused as `PROTECTED_SETTING`) · `ExternalCallRateLimiter.kt` (journal flood limit) · `WhitelistParser.kt` (whitelist dump → packages + typed parse
reason) · `Prefs.kt` (shared keys/defaults).

## `doze/` (9 Kotlin) and `doze/parse/` (5 Kotlin): engine

`DozeController.kt` (enter / verify / exit / reconcile / maintenance, generations) · `DozeModels.kt` (Clock, DozeEvent,
EventType, DozeConfig, Enter/ExitResult) · `DozeStates.kt` · `FeatureReadback.kt` · `RestoreLedger.kt` (ledger + codec) ·
`SafetyNet.kt` · `SchedulePolicy.kt` · `ExactAlarmAccessPolicy.kt` · `WatchdogPolicy.kt`. Parsers: `DozeStateParser.kt`, `ForceIdleResultParser.kt`,
`IdlingHistoryParser.kt`, `SensorModeParser.kt`, `FocusedAppParser.kt` (Known packages vs Unknown; unknown skips
suspend/notification block for that session).

## `monitor/` (7 Kotlin): journal

`JournalDb.kt` (serial SQLite adapter) · `JournalEvent.kt` (event row, `JournalIdentity` for session vs self-test ids) ·
`HistoryMerger.kt` (merge OS idling history; "truncated" only when the 100-entry buffer is full) · `SessionAggregator.kt` (coverage, maintenance, problems) ·
`ReportFormatter.kt` · `ReportExporter.kt` (FileProvider share) · `EventCodes.kt` (persisted journal detail codes).

## `service/` (18 Kotlin): process runtime

`DozeRuntime.kt` (controller, `doze-worker`, recovery, self-tests, deadlines, reset, restore-only windows, runtime debt notice; also `ServiceResetQueue`) · `SessionLifecycle.kt` (epochs, teardown
budgets) · `SessionAccess.kt` (`SessionMode` FORCE / SENSOR_ONLY / RESTORE_ONLY) · `SelfTest.kt` · `JournalSink.kt` · `EventSinks.kt` · `AccessRecovery.kt` ·
`LedgerRecovery.kt` · `SharedPrefsLedgerStore.kt` · `FeatureSelection.kt` · `DeferredFeatureSelection.kt` ·
`LegacyDozeStats.kt` · `AndroidClock.kt` · `AccessReadiness.kt` (enter/recovery barrier, `RootProbeRetry`,
`sameCapability`) · `BootRestorePolicy.kt` (pure boot/update preflight) · `BootRestore.kt` (reads the ledger snapshot; builds the
runtime only inside the admitted callback) ·
`RestoreOnlyRequest.kt` (9 s receiver window + the single process-level `RestoreContinuation`) · `SystemReset.kt`
(readback-verified reset plan and result types; deferred revokes report per-command status markers).

## `ui/` (20 Java) and `ui/amber/` (11 Java): new screens, presentation and the Amber Night kit

`AccessCard.java` (Main access/debt card) · `AccessUi.java` (capability/session presentation, Main status text and the
pure `AccessUpdate` access-change decision) · `DozeMonitorActivity.java`
· `MonitorAdapter.java` · `MonitorData.java` (off-main loading) · `MonitorFormat.java` (localized text) ·
`NoticeSink.java` (app-owned notifications, attached to the journal by `MyApplication.java`) · `DebtRules.java` (debt rules, `LedgerState` DEBT / CLEAN / EMPTY and the notified-key `NoticeGate`), `ModeSwitchRules.java`, `SettingsRules.java` and `MainRules.java` (pure rules,
unit-tested; `MainRules` holds the Main screen's permission order, Shizuku prompt, lockscreen-timeout notice and Doze-item visibility) ·
`DamagedRecords.java` (dismissible damaged ledger lines) · `ResetReport.java` (Settings reset result text and pref
clearing) · `WhitelistUi.java` (whitelist outcomes as text).

Amber Night (dark-only design, US-1) adds pure, JVM-tested rules and the theme wiring to `ui/`:
- `AccentClock.java`: hour → `Phase` (morning 05–10, day 10–17, evening 17–22, night 22–05), no resource ids.
- `AccentThemer.java`: `ActivityLifecycleCallbacks` registered first thing in `MyApplication.onCreate` (before the
  direct-boot return). It applies `ThemeOverlay.Amber.Accent.*` with `getTheme().applyStyle(overlay, true)` in
  `onActivityPreCreated` (API 29+) or `onActivityCreated` (below). The hour source is injectable for tests.
- `LaunchGlowRules.java` (Main's once-per-process launch glow: when it plays, its 500 ms curve, and `startNow`; the
  animator starts in `MainActivity.onEnterAnimationComplete`), `MonitorMotionRules.java` (spring only on a real
  live-state change), `StatsColorRules.java` (sage figures on the stats screens), `AccessUi.GrantHelperDecision`.

`ui/amber/` is the view kit the layouts name directly (no global inflater hook):
- `AmberCardView` (extends `MaterialCardView`) and `AmberButton` (extends `MaterialButton`): squircle shape via
  `SquircleShapes` / `SquircleCornerTreatment` (G2-like cubic corners, footprint clamped to min(w,h)/2, nested radius
  = outer − padding). `AmberCardView` adds `GlassOverlay` (seeded 3 % noise tile + gradient hairline, drawn through
  the `ViewOverlay`). Cards keep `clipToOutline=false`, so children need ≥16dp padding; setting
  `android:background` on an `AmberButton` throws.
- `Amber.treat(Chip|MaterialCardView)` for views built in code (`MonitorAdapter`); a treated chip's `chipMinHeight`
  grows to its line height + 2×`space_1`, so large font scales don't overflow the pill.
- Motion: `Springs` (one `SpringAnimation` per view+property, kept in a view tag from `res/values/ids.xml`),
  `MotionPolicy` (reduced motion = `ANIMATOR_DURATION_SCALE` 0 on every API; springs then snap), `Haptics`
  (CONFIRM on API 30+, CONTEXT_CLICK below), `AmberGlow` (outline shadow colour + `glow_elevation` on API 28+,
  elevation 0 when inactive, no-op below 28).
- `AmberDialogs.builder(ctx)`: the material-dialogs 0.9 builder with the bundled typefaces; every
  `new MaterialDialog.Builder(` in the app goes through it. M3 dialogs are themed by `materialAlertDialogTheme`; AppCompat
  `AlertDialog`s (AndroidX preference dialogs such as the Tunables "Modify value") by `alertDialogTheme` →
  `ThemeOverlay.Amber.AlertDialog` with `drawable/amber_dialog_background.xml`.

## Directory layout

```
app/
├── build.gradle               # Groovy; deps inline; lint baseline
├── lint-baseline.xml          # pre-existing lint issues (only new ones fail)
├── proguard-rules.pro         # keeps Shizuku newProcess for reflection
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── java/com/akylas/enforcedoze/   # root + access/ doze/ doze/parse/ monitor/ service/ ui/ ui/amber/
    │   └── res/  layout/ (28), xml/ (prefs, tunables, shortcuts, backup + data-extraction rules, file_paths), values*/
    │       font/ (Inter + Source Serif 4 variable TTFs, amber_sans/amber_serif families), anim/amber_activity_*
    │   assets/licenses/OFL-*.txt   # font licences, bundled with the APK (not yet shown in the About licenses dialog)
    ├── test/java/com/akylas/enforcedoze/  # 122 JVM test sources, mirrors main packages (see patterns.md)
    ├── test/snapshots/                    # Roborazzi goldens (AmberScreenshotTest)
    ├── test/resources/doze/deviceidle.txt # parser fixture
    └── androidTest/…/ApplicationTest.java # template stub
docs/doze-feature-ledger.md    # command / readback matrix per feature and API band
docs/device-test-1.11.0.md     # on-device release checklist
fastlane/                      # release lanes + store metadata
CHANGELOG.md, PROGRESS.md      # release notes; decision log
```
