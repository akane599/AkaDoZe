# Modules and Files

*Last Updated: 2026-10-04*

Single Gradle module `:app`, base package `com.akylas.enforcedoze` at
`app/src/main/java/com/akylas/enforcedoze/`: 105 sources (54 Java, 51 Kotlin) in seven packages.

## Root package (42 Java, 2 Kotlin): Android shell

| Role | Files |
|------|-------|
| Service | `ForceDozeService.java` (foreground lifecycle, receivers, admission, engine wiring, teardown) |
| App | `MyApplication.java` (context, lazy `getDozeRuntime()` that also attaches `NoticeSink`) |
| Automation | `ExternalControlReceiver.java` (abstract trust/execution boundary) and its aliases `EnableForceDozeService`, `DisableForceDozeService`, `ReenterDoze`, `AddWhiteListReceiver`, `RemoveWhiteListReceiver`, `SettingsChangeReceiver` |
| System receivers | `BootCompleteReceiver`, `AutoRestartOnUpdate` (both run a restore-only window when the service is off and the ledger has work), `CustomDozePeriodReceiver` |
| Screens | `MainActivity`, `SettingsActivity`, `DozeTunablesActivity`, `WhitelistAppsActivity`, `BlockAppsActivity`, `BlockNotificationsActivity`, `DozeBatteryStatsActivity`, `DozeStatsActivity`, `LogActivity`, `TaskerBroadcastsActivity`, `PackageChooserActivity`, `AboutAppActivity`, `RequestIgnoreBatteryActivity` |
| Adapters / items | `AppsAdapter`+`AppsItem`, `BatteryConsumptionAdapter`+`BatteryConsumptionItem`, `DozeStatsAdapter`+`DozeStatsCard`, `TaskerBroadcastsAdapter`+`TaskerBroadcastsItem` |
| Tunables | `DozeTunableHandler.java` (typed `ApplyResult` with readback), `DozeTunableConstants.java` |
| Tiles / listener | `ForceDozeTileService`, `AirplaneTileService`, `NotificationService.kt` (media-playing app) |
| Helpers | `Utils.java` (service start, schedules, permissions, pref repair), `ShizukuHandler.java` (legacy compatibility listener only), `NumberPickerPreference.java`, `MaterialListPreference.kt`, `CustomTabs.java`, `ChromePackageHelper.java` |

## `access/` (13 Kotlin): transports and capabilities

`AccessManager.kt` (singleton: access level, grants, transports, lanes) · `AccessResolution.kt` (bounded cold-start
discovery: 10 s Shizuku window, root 1+3 probes, binder-seen facts) · `AccessModels.kt` (AccessLevel, Feature, Reason,
Grants, CommandResult, FeatureStatus) · `CapabilityResolver.kt` (capability matrix, `PackageNames` validator) ·
`CommandCatalog.kt` (per-API mutation / original / readback commands) · `CommandLane.kt` (FIFO lanes with timeouts and
deadlines) · `RootCommandRunner.kt` (persistent libsuperuser shell) · `ShellCommandRunner.kt` (app `sh` or Shizuku
process) · `GrantCommands.kt` (permission / appops / listener / whitelist helpers) · `ExternalControlPolicy.kt` (gates,
scalar allowlist) · `ExternalCallRateLimiter.kt` (journal flood limit) · `WhitelistParser.kt` (whitelist dump → packages + typed parse
reason) · `Prefs.kt` (shared keys/defaults).

## `doze/` (8 Kotlin) and `doze/parse/` (5 Kotlin): engine

`DozeController.kt` (enter / verify / exit / reconcile / maintenance, generations) · `DozeModels.kt` (Clock, DozeEvent,
EventType, DozeConfig, Enter/ExitResult) · `DozeStates.kt` · `FeatureReadback.kt` · `RestoreLedger.kt` (ledger + codec) ·
`SafetyNet.kt` · `SchedulePolicy.kt` · `WatchdogPolicy.kt`. Parsers: `DozeStateParser.kt`, `ForceIdleResultParser.kt`,
`IdlingHistoryParser.kt`, `SensorModeParser.kt`, `FocusedAppParser.kt` (Known packages vs Unknown; unknown skips
suspend/notification block for that session).

## `monitor/` (6 Kotlin): journal

`JournalDb.kt` (serial SQLite adapter) · `JournalEvent.kt` (event row, `JournalIdentity` for session vs self-test ids) ·
`HistoryMerger.kt` (merge OS idling history) · `SessionAggregator.kt` (coverage, maintenance, problems) ·
`ReportFormatter.kt` · `ReportExporter.kt` (FileProvider share).

## `service/` (17 Kotlin): process runtime

`DozeRuntime.kt` (controller, `doze-worker`, recovery, self-tests, deadlines, reset, restore-only windows) · `SessionLifecycle.kt` (epochs, teardown
budgets) · `SessionAccess.kt` · `SelfTest.kt` · `JournalSink.kt` · `EventSinks.kt` · `AccessRecovery.kt` ·
`LedgerRecovery.kt` · `SharedPrefsLedgerStore.kt` · `FeatureSelection.kt` · `DeferredFeatureSelection.kt` ·
`LegacyDozeStats.kt` · `AndroidClock.kt` · `AccessReadiness.kt` (enter/recovery barrier, `RootProbeRetry`,
`BootRestorePolicy`) · `BootRestore.kt` (receiver preflight that never builds the runtime for an empty ledger) ·
`RestoreOnlyRequest.kt` (9 s receiver window + the single process-level `RestoreContinuation`) · `SystemReset.kt`
(readback-verified reset plan and result types).

## `ui/` (12 Java): new screens and presentation

`AccessCard.java` (Main access/debt card) · `AccessUi.java` (capability/session presentation) · `DozeMonitorActivity.java`
· `MonitorAdapter.java` · `MonitorData.java` (off-main loading) · `MonitorFormat.java` (localized text) ·
`NoticeSink.java` (notifications) · `DebtRules.java` and `ModeSwitchRules.java` (pure rules, unit-tested) ·
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
