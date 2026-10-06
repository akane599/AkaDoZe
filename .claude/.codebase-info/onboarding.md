# Onboarding

*Last Updated: 2026-10-06*

## Build and verify

```bash
bash .claude/kit/gradle-check.sh :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease  # full gate
bash .claude/kit/gradle-check.sh :app:testDebugUnitTest --tests '*DozeControllerTest'                           # narrow run
bash .claude/kit/gradle-check.sh :app:testDebugUnitTest --tests '*RobolectricTest' --max-workers=2               # Android glue on the JVM (SDK 36)
./gradlew :app:installDebug -q && adb shell am start -n com.akylas.enforcedoze/.MainActivity
adb logcat -d --pid=$(adb shell pidof -s com.akylas.enforcedoze) | tail -80
node ~/.claude/plugins/cache/eigenwise-toolshed/quartermaster/0.11.10/bin/quartermaster.js crap --json            # CRAP gate (see tech-landscape.md)
```

Other notes:
- **Release build.** `assembleRelease` minifies, and `app/proguard-rules.pro` must keep `Shizuku.newProcess`. Check that
  with `apkanalyzer dex packages` on the release APK.
- **Device testing.** Doze sessions need Shizuku or root. Without them you only get APP level: grant `DUMP` /
  `WRITE_SECURE_SETTINGS` with `adb shell pm grant com.akylas.enforcedoze android.permission.…` to see readback and
  recovery.
- **Release checklist.** `docs/device-test-1.11.0.md`. The in-app Doze Monitor has "Test Doze now" and "Test sensor
  restriction".

## Exercising the automation API

```bash
adb shell am broadcast -a com.akylas.enforcedoze.ENABLE_FORCEDOZE
adb shell am broadcast -a com.akylas.enforcedoze.CHANGE_SETTING --es settingName turnOffWiFiInDoze --es settingValue true
```

`CHANGE_SETTING` and the whitelist actions are rejected until Settings → Other apps → "Allow other apps to change
whitelist & settings" is on. Every call is journaled; see the Monitor.

## Where to change things

| Task | Start in |
|------|----------|
| Doze enter / verify / restore order | `doze/DozeController.kt` (+ `DozeControllerTest`, `DozeFeatureGroupsTest`) |
| A command, or a new API band | `access/CommandCatalog.kt` + `docs/doze-feature-ledger.md` (+ `CommandCatalogTest`; a readback change or a new API also updates the `CommandCatalogReadbackGoldenTest` matrix, and a multi-command radio restore needs a maintenance `lost`-path test, see `RestoreCharacterizationTest`) |
| Which features a session applies | `service/FeatureSelection.kt`, `DeferredFeatureSelection.kt` |
| What an access level may do | `access/CapabilityResolver.kt` (+ README "Shizuku vs root", `SettingsActivity` gating) |
| Root / Shizuku plumbing | `access/AccessManager.kt`, `CommandLane.kt`, `RootCommandRunner.kt`, `ShellCommandRunner.kt` |
| Screen events, admission, teardown | `ForceDozeService.java` (`receiveOnWorker`, `admitted`, `onDestroy`), `service/SessionLifecycle.kt` |
| Recovery and debt | `service/LedgerRecovery.kt`, `AccessRecovery.kt`, `doze/SafetyNet.kt`, `ui/DebtRules.java`, `ui/DamagedRecords.java` |
| Restore after boot / update / cold start | `service/BootRestore.kt` + `BootRestorePolicy.kt` (`BootRestorePolicyTest`), `RestoreOnlyRequest.kt`, `AccessReadiness.kt`, `access/AccessResolution.kt`, `DozeRuntime.requestRestoreOnly` (+ `AccessDiscoveryRepairTest`, `AccessReadinessTest`) |
| Helper permission grants | `access/AccessManager.kt` (`grantHelpers`, `grantHelper`, `grantHelpersAutomatically`, `forgetHelpers`), `HelperGrantPolicy.kt`, `GrantCommands.kt` (+ `HelperGrantPolicyTest`, `HelperGrantWiringTest`, `ServiceHelperGrantTest`) |
| Settings reset | `service/SystemReset.kt`, `DozeRuntime.resetSystemState`, `ui/ResetReport.java` (+ `SystemResetTest`, `HonestUiTest`) |
| External reapply limits | `doze/WatchdogPolicy.kt` (+ `ExternalReapplyPolicyTest`) |
| Journal, Monitor, reports | `monitor/` + `ui/DozeMonitorActivity.java`, `MonitorFormat.java` |
| Automation receivers | `ExternalControlReceiver.java`, `access/ExternalControlPolicy.kt` |
| New setting | `res/xml/prefs.xml`, `strings.xml`, `access/Prefs.kt` key, `ForceDozeService.reloadSettings` / `FeatureSelection`, the capability in `CapabilityResolver`, and `ExternalControlPolicy` if automation may set it |
| Schedules | `doze/SchedulePolicy.kt`, `Utils.applyForceDozeSchedule`, `CustomDozePeriodReceiver` |
| Tunables | `DozeTunableHandler`, `DozeTunablesActivity`, `res/xml/prefs_doze_tunables.xml` |
| User-visible text | `app/src/main/res/values/strings.xml` (translations come from Weblate). UI work is Claude-only |
| Release | `app/build.gradle` versionCode / versionName, `CHANGELOG.md`, `fastlane/` |
