# Entry Points

*Last Updated: 2026-10-06*

All components are declared in `app/src/main/AndroidManifest.xml`. Classes live in
`app/src/main/java/com/akylas/enforcedoze/` unless prefixed `ui.`.

## Activities (14)

| Class | Exported | Purpose |
|-------|----------|---------|
| `MainActivity` | yes (launcher, shortcuts) | Master switch, access card (`ui/AccessCard`), navigation, safety check on resume |
| `ui.DozeMonitorActivity` | yes (`QS_TILE_PREFERENCES`: long-press a tile) | Live Doze state, self-tests ("Test Doze now", "Test sensor restriction"), session list/timeline, "Restore system state", "Share report". Long-presses on other tiles are forwarded |
| `SettingsActivity` | no | Capability-aware preferences (`res/xml/prefs.xml`, incl. the exact-alarm status row), mode switch root ↔ Shizuku (`ui/ModeSwitchRules`), "Other apps" gates, readback-verified reset (`DozeRuntime.resetSystemState` → `ui/ResetReport`) |
| `DozeTunablesActivity` | no | `device_idle` tunables with per-key apply result |
| `WhitelistAppsActivity` | no | System Doze whitelist, readback-verified (`access/WhitelistParser`, `ui/WhitelistUi`) |
| `BlockAppsActivity` / `BlockNotificationsActivity` | no | App-suspend / notification-block lists |
| `DozeBatteryStatsActivity`, `DozeStatsActivity` | no | Legacy stats (`dozeUsageDataAdvanced`) |
| `LogActivity` | no | logcat view + share via FileProvider |
| `TaskerBroadcastsActivity` | no | Automation help filtered by `ExternalControlPolicy` and showing gate state |
| `PackageChooserActivity`, `AboutAppActivity`, `RequestIgnoreBatteryActivity` (transparent) | no | Helpers |

## Services and providers

| Component | Notes |
|-----------|-------|
| `ForceDozeService` | Not exported, foreground `specialUse`, START_STICKY. The session host (see architecture.md) |
| `ForceDozeTileService` | QS tile: start/stop the service |
| `AirplaneTileService` | QS tile for the airplane-in-Doze setting |
| `NotificationService.kt` | `NotificationListenerService`; media-playing app for the music whitelist |
| `androidx.core.content.FileProvider` | Not exported, `${applicationId}.reports`, exposes `cache/reports/` only (`res/xml/file_paths.xml`) |
| `rikka.shizuku.ShizukuProvider` | `${applicationId}.shizuku` (Shizuku binder delivery) |

## Automation API (exported receivers, all extend `ExternalControlReceiver`)

| Action (`com.akylas.enforcedoze.`…) | Receiver | Gate | Extras |
|--------|----------|------|--------|
| `ENABLE_FORCEDOZE` | `EnableForceDozeService` | basic | — |
| `DISABLE_FORCEDOZE` | `DisableForceDozeService` | basic | — |
| explicit component, no filter | `ReenterDoze` | basic | — (reapply; never cancels a pending enter or shortens `dozeEnterDelay`; shares the watchdog's 60 s spacing and 5-per-session budget, skipped during maintenance or an unknown light state on API 24+) |
| `ADD_WHITELIST` / `REMOVE_WHITELIST` | `AddWhiteListReceiver` / `RemoveWhiteListReceiver` | privileged | `packageName` |
| `CHANGE_SETTING` | `SettingsChangeReceiver` | privileged | `settingName`, `settingValue` |

- **Gates.** Pref `allowExternalBasicControl` (default **on**) and `allowExternalPrivilegedControl` (default **off**) are
  independent; both live in `access/Prefs.kt`.
- **Checks.** `ExternalControlReceiver.Admission` checks the gate before reading extras, then validates input. Denied
  calls never build `DozeRuntime` (they journal through the app-owned `JournalSink`), and CHANGE_SETTING writes only a
  parsed boolean/integer value (`Admission.setting`; anything else is `UNVERIFIED_SETTING_VALUE`). Work runs in
  `goAsync` on a bounded queue with a deadline, and trust and capability are re-checked at backend admission.
- **CHANGE_SETTING.** Only accepts the scalar keys listed by `access/ExternalControlPolicy.kt` (booleans,
  `dozeEnterDelay` 0..1800). Gates, mode, ledger, access keys and the sensor allow-token pref are rejected as
  `PROTECTED_SETTING`.
- **Results.** Start/stop/reapply report REQUESTED, not verified Doze. ENABLE persists the intent only after the start
  is accepted, and stops that start if the write fails (`PREFERENCE_WRITE_FAILED`). An unexpected receiver failure is
  journaled as DENIED with `INTERNAL_ERROR`. Every call is journaled as `EXTERNAL_CALL`,
  rate-limited for the journal only by `access/ExternalCallRateLimiter.kt` (10 per action per rolling minute).
  `ui/NoticeSink` posts one notice per gate on the first rejection.

## System triggers

| Trigger | Receiver |
|---------|----------|
| `BOOT_COMPLETED` (locked boot is ignored: credential-protected prefs aren't readable yet) | `BootCompleteReceiver`: start the service if enabled, else a `goAsync` restore-only window inside the `BootRestore.restoreIfPending` callback; re-arm schedule |
| `MY_PACKAGE_REPLACED` | `AutoRestartOnUpdate`: restart the service if enabled, else the same restore-only window |
| Schedule alarm | `CustomDozePeriodReceiver` (not exported); runs only while the user intent `serviceUserEnabled` is on |
| `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (API 31+) | `ExactAlarmPermissionReceiver` (not exported): re-query exact access and re-arm the next boundary; never starts the service |
| "Disabled, tap to enable" notification | `InternalEnableReceiver` (not exported): same explicit-ON flow as the master switch, never journaled as an external call |
| Dynamic, not exported | `ForceDozeService.DozeReceiver`: screen/power/unlock/(light) idle changes |
| Launcher shortcuts | `res/xml/shortcuts.xml` |
