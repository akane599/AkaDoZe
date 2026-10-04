# Privileged Commands, Transports and Events

*Last Updated: 2026-10-04*

## Transports (`access/`)

`AccessManager.kt` is the singleton that owns every privileged command. It observes these sources:
- the pref `executionMode` (`root` | `shizuku`);
- the Shizuku binder, its permission and its death;
- su discovery, run off-main on `access-probe`.

It publishes `AccessState(level, …, grants, …, resolved)`. `resolved` stays false only during bounded cold-start
discovery (`AccessResolution.kt`). Listeners get updates on main; blocking commands refuse to run on main.

| Backend | File | Mechanism |
|---------|------|-----------|
| Root | `RootCommandRunner.kt` | Persistent libsuperuser shell, owned by one lane |
| Shizuku | `AccessManager` + `ShellCommandRunner.kt` | Reflective `Shizuku.newProcess(["sh","-c",cmd], …)` (kept by `app/proguard-rules.pro`) |
| App shell | `ShellCommandRunner.kt` | Plain `sh`; useful only with adb-granted `DUMP` / `WRITE_SECURE_SETTINGS` |

`CommandLane.kt` runs each lane as a FIFO with a supervisor and a serial backend. Control and read lanes are independent.

- `run`: the default timeout is 8 s and excludes queue wait.
- `runWithDeadline`: the deadline counts queue wait too, and admission is re-checked just before the backend runs.
- A timeout kills/resets the backend; stdout and stderr are drained concurrently.

`ShizukuHandler.java` is a legacy compatibility listener, not the authority.

## Capabilities

`CapabilityResolver.kt` maps (Feature, AccessLevel, API, Grants) to `FeatureStatus` (Available / Unavailable(Reason)).
The README "Shizuku vs root" section describes it by hand. In short:
- APP+DUMP: state readback and sensor recovery.
- APP+WSS: tunables and biometrics.
- SHELL/ROOT: almost everything.
- Root only: all-sensor privacy, `setprop` Doze, `pm disable`, and the legacy notification block below Android 13.

Doze **sessions** additionally need SHELL or ROOT (`service/SessionAccess.kt`).

## Command catalogue (`access/CommandCatalog.kt`)

Every feature has an apply command, an original-value read and a readback, chosen per API level. The full matrix, with the
physical-device gaps, is in `docs/doze-feature-ledger.md`.

| Feature | Apply / restore | Readback |
|---------|-----------------|----------|
| Force Doze | `dumpsys deviceidle force-idle deep` / `unforce` | `dumpsys deviceidle` (`mForceIdle`, `get deep`), parsed by `doze/parse/DozeStateParser.kt` |
| Motion sensors | `dumpsys sensorservice restrict <package>` / `enable` | `dumpsys sensorservice` mode + allow token (`SensorModeParser.kt`) |
| Battery saver | `settings put global low_power` / `cmd power set-mode` | `settings get global low_power` |
| Wi-Fi / data / Bluetooth | `svc wifi|data|bluetooth`, `cmd wifi set-wifi-enabled`, `cmd bluetooth_manager` | `settings get global wifi_on|mobile_data|bluetooth_on` |
| Airplane (API 30+) | `cmd connectivity airplane-mode enable|disable` | same command without argument |
| Location | `cmd location set-location-enabled` / `settings put secure location_mode` | `cmd location is-location-enabled` / `location_mode` |
| Biometrics | `settings put secure biometric_keyguard_enabled` | `settings get …` |
| App suspend | `pm suspend|unsuspend` (root on API 23: `pm disable|enable`) | `dumpsys package <pkg>` |
| Notification block | API 33+: `pm revoke|grant POST_NOTIFICATIONS` + user-fixed flags; below: `service call notification <txn>` (root, UNVERIFIED) | `dumpsys package` / `dumpsys notification` |
| All-sensor privacy (root) | `service call sensor_privacy <txn>` | `dumpsys sensor_privacy` |
| Tunables | `cmd device_config put device_idle` (API 31+) or `settings put global device_idle_constants` | matching `get` |
| Whitelist | `dumpsys deviceidle whitelist +pkg|-pkg` | structured `whitelist` dump (`WhitelistParser.kt`: COMMAND_FAILED / TIMED_OUT / PARTIALLY_PARSED / EMPTY) |
| Focused app (read only) | `dumpsys window` | `mCurrentFocus` / `mFocusedApp` (`doze/parse/FocusedAppParser.kt`); a failed or unfamiliar dump is Unknown |

Package names are validated by `CapabilityResolver.PackageNames` before they reach a command. External input is never
interpolated into a shell string.

## Engine events

`doze/DozeModels.kt` `EventType`:
- SCREEN_OFF, SCREEN_ON, ENTER_STEP, VERIFY, REFORCE, IDLE_CHANGED, MAINT_START, MAINT_END;
- SENSORS_RESTRICTED, SENSORS_RESTORED, SKIPPED, RESTORE_FAILED, RECOVERY_DEBT;
- ACCESS_CHANGED, EXTERNAL_CALL, ERROR.

Logic emits typed `DozeEvent(type, detail, …, reason)`. The UI turns them into strings (`ui/MonitorFormat`).

Fan-out: `service/EventSinks.kt` (exception-isolated) → `service/JournalSink.kt`, which records them in `monitor/JournalDb`
and also feeds `ui/NoticeSink`. Notices go out on the existing tips channel:
- access lost or resumed;
- recovery debt;
- the first rejected external call per gate;
- foreground start denied;
- the opt-in summary after screen-on.

## Local broadcasts (LocalBroadcastManager, in-process)

`reload-settings`, `reload-notification-blocklist`, `reload-app-blocklist`, `update-state-from-tile`, and the
battery-optimization result. They are sent by Activities and tiles and received by `ForceDozeService` / `MainActivity`.

## External

No network API. `CustomTabs` / `ChromePackageHelper` open web links.
