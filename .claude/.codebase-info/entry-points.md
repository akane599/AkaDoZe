# Entry Points

*Last Updated: 2026-10-03*

All components are declared in `app/src/main/AndroidManifest.xml`; classes live in
`app/src/main/java/com/akylas/enforcedoze/`.

## Activities

| Class | Purpose |
|-------|---------|
| `MainActivity` (launcher, also QS tile preferences) | Master toggle, root/Shizuku detection (`doAfterSuCheckSetup`), permission prompts, menu to other screens |
| `SettingsActivity` | `PreferenceFragmentCompat` over `res/xml/prefs.xml`; execution mode, custom Doze periods, reset |
| `DozeTunablesActivity` | Edit `device_idle_constants` (`res/xml/prefs_doze_tunables.xml`) |
| `WhitelistAppsActivity` | Add/remove apps from the system Doze whitelist |
| `BlockAppsActivity` / `BlockNotificationsActivity` | App blocklist (disabled while dozing) / notification blocklist |
| `DozeBatteryStatsActivity`, `DozeStatsActivity` (old) | Stats from `dozeUsageDataAdvanced` |
| `LogActivity` | Shows `logcat -d -s ForceDozeService,ForceDoze` |
| `TaskerBroadcastsActivity` | Documents the broadcast API below |
| `PackageChooserActivity`, `AboutAppActivity`, `RequestIgnoreBatteryActivity` (transparent, not exported) | Helpers |

## Services

| Class | Type |
|-------|------|
| `ForceDozeService` | Foreground (`specialUse`), not exported — the engine |
| `ForceDozeTileService` | QS tile: start/stop the service, sends local `update-state-from-tile` |
| `AirplaneTileService` | QS tile for the airplane-in-Doze setting, sends local `reload-settings` |
| `NotificationService.kt` | `NotificationListenerService`; finds the package playing media (`getPlayingPackageName`) |

## Public broadcast API (exported receivers, no permission required)

| Action | Receiver | Extras | Effect |
|--------|----------|--------|--------|
| `com.akylas.enforcedoze.ENABLE_FORCEDOZE` | `EnableForceDozeService` | — | `serviceEnabled=true`, start service |
| `com.akylas.enforcedoze.DISABLE_FORCEDOZE` | `DisableForceDozeService` | — | `serviceEnabled=false`, stop service |
| `com.akylas.enforcedoze.ADD_WHITELIST` | `AddWhiteListReceiver` | `packageName` | `dumpsys deviceidle whitelist +pkg` (via `Shell.SH`) |
| `com.akylas.enforcedoze.REMOVE_WHITELIST` | `RemoveWhiteListReceiver` | `packageName` | `… whitelist -pkg` |
| `com.akylas.enforcedoze.CHANGE_SETTING` | `SettingsChangeReceiver` | `settingName`, `settingValue` | Writes a whitelisted pref (`Utils.doesSettingExist`), sends `reload-settings` |
| (explicit) | `ReenterDoze` | — | Sends local `reenter-doze` (nothing listens; see patterns.md) |

## System triggers

| Trigger | Receiver |
|---------|----------|
| `BOOT_COMPLETED` | `BootCompleteReceiver` → start/stop service, re-arm custom period alarm |
| `PACKAGE_REPLACED` (own package) | `AutoRestartOnUpdate` → restart service if enabled |
| Exact alarm (custom periods) | `CustomDozePeriodReceiver` (not exported) → `Utils.applyForceDozeSchedule` |
| Launcher shortcuts | `res/xml/shortcuts.xml` |

`rikka.shizuku.ShizukuProvider` is declared with authority `${applicationId}.shizuku`.
`MyApplication` only stores a static app context.
