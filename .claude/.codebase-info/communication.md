# Privileged Commands, Broadcasts and System APIs

*Last Updated: 2026-10-03*

## Execution modes

Pref `executionMode` (`root` default | `shizuku`), read by `Utils.isShizukuMode`. `isSuAvailable` is set
by `MainActivity` after an su probe.

| Path | Where | Mechanism |
|------|-------|-----------|
| Shizuku | `ShizukuHandler.executeCommand` | Reflection on `Shizuku.newProcess` → `sh -c <cmd>` on a new `Thread`; callback `(code, exit, stdout, stderr)` |
| Root | `ForceDozeService.executeCommandWithRoot` | Persistent libsuperuser `Shell.Interactive` (`useSU`, 5 s watchdog) on `rootShellExecutor` |
| Non-root shell | `ForceDozeService.executeCommand` | Same but `useSH`; works for `dumpsys` only after `pm grant … DUMP` via adb |
| Activities | `MainActivity`, `SettingsActivity`, `DozeTunablesActivity` | Their own `executeCommand*` copies (duplicated logic) |
| Receivers | `AddWhiteListReceiver`, `RemoveWhiteListReceiver`, `LogActivity` | Blocking `Shell.SH.run` in `AsyncTask` |

Both service paths dispatch to Shizuku when `executionMode=shizuku` and Shizuku is available.

## Command catalogue (ForceDozeService unless noted)

| Purpose | Command |
|---------|---------|
| Force / leave Doze | `dumpsys deviceidle force-idle deep` / `unforce`; pre-N `force-idle` / `step` |
| Non-root Doze (API 34+) | `device_config put device_idle …` (`DozeTunableHandler.getCommandsList`), reset with `device_config reset trusted_defaults device_idle` |
| Non-root Doze (< 34) | `Settings.Global.putString("device_idle_constants", …)` |
| Read state | `dumpsys deviceidle` (parsed in `getDeviceIdleState`) |
| Self whitelist | `dumpsys deviceidle whitelist +com.akylas.enforcedoze` |
| Sensors | `dumpsys sensorservice restrict [pkg]` / `enable`; all-sensors via sensor privacy (`setAllSensorsState`) |
| Self grants | `pm grant com.akylas.enforcedoze android.permission.{DUMP,WRITE_SECURE_SETTINGS,READ_PHONE_STATE,MANAGE_SENSOR_PRIVACY}` |
| App blocklist | `pm suspend/unsuspend <pkg>` or `pm disable/enable <pkg>` (`setPackageState`) |
| Notification blocklist | `service call notification <txn> …` using a transaction code looked up by reflection (`setNotificationEnabledForPackage`) |
| Radios | `svc wifi|data|bluetooth enable|disable`; `settings put global airplane_mode_on` + `am broadcast -a android.intent.action.AIRPLANE_MODE`; `settings put secure location_mode`; `settings put global low_power`; `settings put secure biometric_keyguard_enabled` |
| Focused app | `dumpsys activity activities | grep -E 'CurrentFocus|ResumedActivity|FocusedApp'` (root) or `UsageStatsManager` (non-root) |

## Local broadcasts (LocalBroadcastManager)

| Action | Sent by | Received by |
|--------|---------|-------------|
| `reload-settings` | `SettingsActivity`, `SettingsChangeReceiver`, `AirplaneTileService`, stats activities | `ForceDozeService.reloadSettings`, `Utils` (logcat flag) |
| `reload-notification-blocklist` | `BlockNotificationsActivity` | `ForceDozeService` |
| `reload-app-blocklist` | `BlockAppsActivity` | `ForceDozeService` |
| `update-state-from-tile` | `ForceDozeTileService` | `MainActivity` |
| `com.akylas.enforcedoze.ACTION_IGNORE_BATTERY_OPTIMIZATION_RESULT` | `RequestIgnoreBatteryActivity` | `ForceDozeService` |

## External

- No network API. `CustomTabs` / `ChromePackageHelper` open web links (donate, about).
- Notification channels: stats, tips, silent (foreground requirement on S+).
