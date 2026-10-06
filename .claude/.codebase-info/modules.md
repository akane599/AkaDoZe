# Modules and Files

*Last Updated: 2026-10-06*

Single Gradle module `:app`, base package `com.akylas.enforcedoze` at
`app/src/main/java/com/akylas/enforcedoze/`: 110 sources (56 Java, 54 Kotlin) in seven packages.

## Root package (44 Java, 2 Kotlin): Android shell

| Role | Files |
|------|-------|
| Service | `ForceDozeService.java` (foreground lifecycle, receivers, admission, engine wiring, teardown) |
| App | `MyApplication.java` (context, lazy `getDozeRuntime()` that also attaches `NoticeSink`, app-owned lazy `getJournal()`; project SAMs `Factory`/`Callback` because `java.util.function` is API 24+) |
| Automation | `ExternalControlReceiver.java` (abstract trust/execution boundary) and its aliases `EnableForceDozeService`, `DisableForceDozeService`, `ReenterDoze`, `AddWhiteListReceiver`, `RemoveWhiteListReceiver`, `SettingsChangeReceiver` |
| System receivers | `BootCompleteReceiver`, `AutoRestartOnUpdate` (both run a restore-only window when the service is off and the ledger has work), `CustomDozePeriodReceiver`, `ExactAlarmPermissionReceiver` (re-arms the next boundary after an exact-alarm grant change), `InternalEnableReceiver` (non-exported target of the app's own "tap to enable" notification) |
| Screens | `MainActivity`, `SettingsActivity`, `DozeTunablesActivity`, `WhitelistAppsActivity`, `BlockAppsActivity`, `BlockNotificationsActivity`, `DozeBatteryStatsActivity`, `DozeStatsActivity`, `LogActivity`, `TaskerBroadcastsActivity`, `PackageChooserActivity`, `AboutAppActivity`, `RequestIgnoreBatteryActivity` |
| Adapters / items | `AppsAdapter`+`AppsItem`, `BatteryConsumptionAdapter`+`BatteryConsumptionItem`, `DozeStatsAdapter`+`DozeStatsCard`, `TaskerBroadcastsAdapter`+`TaskerBroadcastsItem` |
| Tunables | `DozeTunableHandler.java` (typed `ApplyResult` with readback), `DozeTunableConstants.java` |
| Tiles / listener | `ForceDozeTileService`, `AirplaneTileService`, `NotificationService.kt` (media-playing app) |
| Helpers | `Utils.java` (service start, schedules, permissions, pref repair), `ShizukuHandler.java` (legacy compatibility listener only), `NumberPickerPreference.java`, `MaterialListPreference.kt`, `CustomTabs.java`, `ChromePackageHelper.java` |

## `access/` (14 Kotlin): transports and capabilities

`AccessManager.kt` (singleton: access level, grants, transports, lanes) · `AccessResolution.kt` (bounded cold-start
discovery: 10 s Shizuku window, root 1+3 probes, binder-seen facts, fresh root budget on service attach) · `AccessModels.kt` (AccessLevel, Feature, Reason,
Grants, CommandResult, FeatureStatus) · `CapabilityResolver.kt` (capability matrix, `PackageNames` validator) ·
`CommandCatalog.kt` (per-API mutation / original / readback commands) · `CommandLane.kt` (FIFO lanes with timeouts and
deadlines) · `RootCommandRunner.kt` (persistent libsuperuser shell behind the `RootSession` seam) · `ShellCommandRunner.kt` (app `sh` or Shizuku
process) · `GrantCommands.kt` (permission / appops / listener / whitelist helpers; no READ_LOGS) · `HelperGrantPolicy.kt` (AUTOMATIC vs EXPLICIT
selection, persist-before-grant attempts, forget-before-revoke) · `ExternalControlPolicy.kt` (gates,
scalar allowlist) · `ExternalCallRateLimiter.kt` (journal flood limit) · `WhitelistParser.kt` (whitelist dump → packages + typed parse
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

`DozeRuntime.kt` (controller, `doze-worker`, recovery, self-tests, deadlines, reset, restore-only windows; also `ServiceResetQueue`, `TeardownTimeout`) · `SessionLifecycle.kt` (epochs, teardown
budgets) · `SessionAccess.kt` (`SessionMode` FORCE / SENSOR_ONLY / RESTORE_ONLY) · `SelfTest.kt` · `JournalSink.kt` · `EventSinks.kt` · `AccessRecovery.kt` ·
`LedgerRecovery.kt` · `SharedPrefsLedgerStore.kt` · `FeatureSelection.kt` · `DeferredFeatureSelection.kt` ·
`LegacyDozeStats.kt` · `AndroidClock.kt` · `AccessReadiness.kt` (enter/recovery barrier, `RootProbeRetry`,
`sameCapability`) · `BootRestorePolicy.kt` (pure boot/update preflight) · `BootRestore.kt` (reads the ledger snapshot; builds the
runtime only inside the admitted callback) ·
`RestoreOnlyRequest.kt` (9 s receiver window + the single process-level `RestoreContinuation`) · `SystemReset.kt`
(readback-verified reset plan and result types).

## `ui/` (13 Java): new screens and presentation

`AccessCard.java` (Main access/debt card) · `AccessUi.java` (capability/session presentation, Main status text and the
pure `AccessUpdate` access-change decision) · `DozeMonitorActivity.java`
· `MonitorAdapter.java` · `MonitorData.java` (off-main loading) · `MonitorFormat.java` (localized text) ·
`NoticeSink.java` (notifications) · `DebtRules.java`, `ModeSwitchRules.java` and `SettingsRules.java` (pure rules,
unit-tested) ·
`DamagedRecords.java` (dismissible damaged ledger lines) · `ResetReport.java` (Settings reset result text and pref
clearing) · `WhitelistUi.java` (whitelist outcomes as text).

## Directory layout

```
app/
├── build.gradle               # Groovy; deps inline; lint baseline
├── lint-baseline.xml          # pre-existing lint issues (only new ones fail)
├── proguard-rules.pro         # keeps Shizuku newProcess for reflection
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── java/com/akylas/enforcedoze/   # root + access/ doze/ doze/parse/ monitor/ service/ ui/
    │   └── res/  layout/ (28), xml/ (prefs, tunables, shortcuts, backup + data-extraction rules, file_paths), values*/
    ├── test/java/com/akylas/enforcedoze/  # 58 JVM test sources, mirrors main packages (see patterns.md)
    ├── test/resources/doze/deviceidle.txt # parser fixture
    └── androidTest/…/ApplicationTest.java # template stub
docs/doze-feature-ledger.md    # command / readback matrix per feature and API band
docs/device-test-1.11.0.md     # on-device release checklist
fastlane/                      # release lanes + store metadata
CHANGELOG.md, PROGRESS.md      # release notes; decision log
```
