# Changelog

## 1.11.0 (versionCode 87)

### Highlights
- **Motion sensors are really restricted now.** The old command had no package argument, so Android
  rejected it every time. EnforceDoze now sends `dumpsys sensorservice restrict <package>` and reads the
  sensor mode back to confirm.
- **Doze is verified, not assumed.** The Doze state is read back (`get deep`), and maintenance windows
  are told apart from deep idle. If motion knocks the phone out of forced idle, EnforceDoze re-forces it
  within limits.
- **Tracked session changes are restored.** Before a ledger-backed change, the original value is written to a
  durable restore ledger. Screen-on starts restoration unless "Wait for unlock" is enabled, in which case
  restoration waits for unlock (biometrics alone restore at screen-on). Entries come from the ledger, never
  your current settings, and are restored in a fixed order: sensors, then unforce, then the rest in reverse.
  Anything that can't be restored (for example, access was lost) is shown as recovery debt with a "Restore
  now" action. Doze tunables, whitelist edits and `setprop` changes are not ledger-backed.
- **Honest access levels.** A new access card shows the current mode and what is missing. Settings
  that need root (or a missing permission or Android version) are disabled with the reason; their saved
  values are never touched. With DUMP and motion-sensor restriction enabled, a sensor-only session works
  without Shizuku or root; other Doze sessions need Shizuku or root.
- **Sensor-only sessions with DUMP.** With ADB-granted `DUMP` and "Disable motion sensors" enabled,
  EnforceDoze can restrict motion sensors without Shizuku or root. Android decides when Doze begins;
  this mode does not force Doze and external `REAPPLY_DOZE` cannot reapply it.
- **Doze Monitor.** A new screen (menu, or long-press the quick tile) lists screen-off sessions with
  verified deep-idle time, re-forces, maintenance windows, the sensor state and problems. It can
  run self-tests and restore system state, and it shares a report. An optional summary notification
  appears after screen-on.

### Android 16 / platform
- Foreground service declares its special-use type; start denials are caught and journaled.
- Edge-to-edge insets on every screen; AppCompat toolbars; back handling via the dispatcher.
- Exact-alarm permission checked on Android 12+, with an inexact fallback.
- Background service starts fixed on API 26–30; boot and own-package update handled precisely.
- Log sharing uses a FileProvider (fixes the `Uri.fromFile` crash); preferences are no longer chmod'ed.
- The Doze engine (and its root check) starts only when something needs it, so tiles, alarms, the
  notification listener and app updates no longer trigger a root request.

### Shizuku
- Shizuku access is tracked continuously: binder, permission and uid (shell or root). Commands run
  on ordered lanes with deadlines instead of a thread per command, and an R8 keep rule protects the
  reflective process call.
- Switching to Shizuku waits for its permission prompt, and switching to root waits for the root check.
  A denial reverts to the previous mode with the reason, also across a screen rotation.
- Losing Shizuku while dozing journals recovery debt and posts a notice. When it returns, the ledger
  is reconciled automatically.

### Automation (Tasker / other apps)
- Exported receivers validate extras and allow only basic actions when basic control is enabled, or
  whitelist/setting changes when privileged control is enabled (off by default). Setting names and
  values are allow-listed and typed; package names are validated before being used in whitelist
  commands. The journal limits recorded events to 10 per action per minute; this is not a limit on
  external calls. `REAPPLY_DOZE` additionally requires basic-control consent and is subject to the
  watchdog's 60-second spacing and five-reforce session budget. It does not cut maintenance windows,
  and rejects unknown light state on API 24+. A bounded worker and deadline report busy or timed-out
  work rather than claiming it succeeded.

### Fixes
- Pending restore-ledger work is checked after boot, app update and cold start, even when enforcement
  is disabled; restoration waits for access to resolve. A process-wide continuation handles access
  that arrives after the restore-only window. Empty ledgers and non-recoverable-only damage do not
  trigger access discovery.
- An unreadable focused-app state is treated as unknown, so app suspension and notification blocking
  are skipped rather than based on a guess.
- Damaged ledger entries for force-Doze and motion-sensor restrictions can be cleared automatically;
  other damaged entries remain recovery debt until explicitly dismissed or restored.
- System reset reports per-command outcomes and reports success only when readback confirms the
  requested effect. Whitelist reads preserve parseable entries and report partial or failed parsing.
- DUMP-only access can read Doze state and monitor evidence but cannot enforce a Doze session.
- If restoring at service shutdown runs out of time, a follow-up restore finishes the job in the
  background, and anything still not restored is reported as recovery debt instead of being dropped.
- Recovery notices appear once per problem, not on every app open, and not while the main screen or the
  Monitor already shows it.
- Battery stats keep real readings while charging, cap at the newest 1000 rows, and require paired
  enter/exit.
- Custom schedule periods handle overnight ranges and end boundaries.
- Fixed the READ_PHONE_STATE permission check, and the whitelist check no longer counts
  except-idle entries as deep-whitelisted.
- The auto-rotate/brightness workaround is retired (it made unrecorded changes to your settings).

### Known limits / caveats
- **While motion sensors are restricted, other apps get no motion data:** step counters and pocket
  detection pause until the screen comes back on.
- Self-tests in the Doze Monitor run only while the EnforceDoze service is running.
- On Android 14+ the system may freeze the app before a background follow-up restore finishes. Anything
  unfinished stays in the restore ledger and is restored when the service next starts, or with "Restore now".
- Several behaviours can only be confirmed on a real device: OEM `dumpsys` formats, radio/biometric
  restores, legacy notification blocking on Android 6–12, and sensor-privacy transactions. EnforceDoze
  reports these as UNVERIFIED rather than claiming success.
- Rolling back to an older version doesn't undo system state: use "Restore system state" first.
- Physical-device verification is still pending for the cases listed in `docs/device-test-1.11.0.md`.
  New strings are English-only; translations may still show the previous ADB-dialog wording.