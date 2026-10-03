# Architecture

*Last Updated: 2026-10-03*

## Overview

No layers, DI or ViewModels: a classic pre-Jetpack Android app. All Doze logic sits in one sticky
foreground service, `ForceDozeService` (~1600 lines). UI Activities write SharedPreferences and nudge
the service through `LocalBroadcastManager`. Privileged actions are shell commands run as root
(libsuperuser `Shell.Builder().useSU()`), through Shizuku (`ShizukuHandler`), or as a plain `sh`
when the app has been granted `DUMP` / `WRITE_SECURE_SETTINGS` via adb.

```
 MainActivity (toggle) ─┐        QS tiles ─┐     public broadcasts (Tasker) ─┐   BootCompleteReceiver
                        ▼                  ▼                                ▼            │
                  Utils.startForceDozeService / stopForceDozeService  ◄─────────────────┘
                                          │  (startForegroundService or exact alarm on S+)
                                          ▼
                                ForceDozeService (foreground, START_STICKY)
           registers DozeReceiver: SCREEN_ON/OFF, USER_PRESENT, POWER_CONNECTED, (LIGHT_)DEVICE_IDLE_MODE_CHANGED
                                          │
       ┌──────────────────────────────────┼─────────────────────────────────────┐
       ▼                                  ▼                                     ▼
 executeCommandWithRoot()          executeCommand()                    Android APIs
 su session / Shizuku              sh session / Shizuku                Settings.Global, AlarmManager,
 (pm suspend, svc wifi/data,       (dumpsys deviceidle, dumpsys        UsageStatsManager, notifications
  settings put, am broadcast)       sensorservice, device_config)
```

## Core flow (ForceDozeService.java)

| Step | Method | What happens |
|------|--------|--------------|
| Service start | `onCreate` | Creates notification channels, registers `DozeReceiver` + local receivers, loads ~20 prefs, picks Shizuku/root, self-grants `DUMP`, `WRITE_SECURE_SETTINGS`, `READ_PHONE_STATE` via `pm grant`, re-enables any blocklisted apps left disabled |
| | `onStartCommand` | Foreground notification (stats or silent), `addSelfToDozeWhitelist()`, `enterDoze()` |
| Screen off | `DozeReceiver.onReceive` | Skips when charging (`disableWhenCharging`), in a call/VoIP; else enters immediately or after `dozeEnterDelay` / lockscreen timeout with a temp wakelock + `Timer` |
| Enter Doze | `enterDoze` | Checks custom Doze periods and screen state; suspends/disables app blocklist, disables notification blocklist, records stats, `applyDoze()`, restricts sensors after 2 s, `enterDozeHandleNetwork()` |
| Force idle | `applyDoze` | root/Shizuku: `dumpsys deviceidle force-idle deep`; non-root: writes tunables (`DozeTunableHandler`) to `device_idle_constants` or `device_config` (API 34+) |
| Radios | `actualEnterDozeHandleNetwork` | Snapshots Wi-Fi/data/airplane/BT/GPS/hotspot/saver state, then turns things off per prefs; skipped when media is playing and `whitelistMusicAppNetwork` (via `NotificationService`) |
| Maintenance | `ACTION_DEVICE_IDLE_MODE_CHANGED` | `IDLE_MAINTENANCE` → restore radios; back to `IDLE` → turn them off again |
| Screen on / unlock | `handleScreenOn` → `exitDoze` | Restores radios from the snapshot, `dumpsys deviceidle unforce`, re-enables apps/notifications, re-enables sensors after 2 s, updates stats notification |
| Charger plugged | `ACTION_POWER_CONNECTED` | Exits Doze when `disableWhenCharging` |
| Service stop | `onDestroy` | Re-enables sensors, `exitDoze`, closes shells, shows "disabled" notification if user-disabled |

Device idle state is read by parsing `dumpsys deviceidle` (`getDeviceIdleState`).

## State

- **Default SharedPreferences** is the only store. Keys mirror `app/src/main/res/xml/prefs.xml`
  (`executionMode` root|shizuku, `serviceEnabled`, `isSuAvailable`, `turnOff*InDoze`, `dozeEnterDelay`,
  `customDozePeriods` "HH:mm-HH:mm" set, `notificationBlockList`, `dozeAppBlockList`,
  `dozeUsageDataAdvanced` stats set "timestamp,battery,ENTER|EXIT|…").
- Doze tunables: `app/src/main/res/xml/prefs_doze_tunables.xml`, applied by `DozeTunableHandler`.
- Service fields (`was*On`, `lastKnownState`, `maintenance`) are in-memory only; lost if the service is killed.

## Scheduling

Custom Doze periods (`Utils.applyForceDozeSchedule`, `scheduleNextCustomDozePeriodBoundary`) set an exact
alarm at the next period boundary → `CustomDozePeriodReceiver` → start/stop the service and flip
`serviceEnabled`. Boot re-arms it (`BootCompleteReceiver`).

See [communication.md](./communication.md) for the command catalogue and [entry-points.md](./entry-points.md) for triggers.
