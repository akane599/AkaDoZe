# Architecture

*Last Updated: 2026-10-06*

## Overview

Still a single `:app` module with XML Views and AndroidX Preference screens, but since AkaDoZe 2.0 the logic
is no longer inside one god service. Pure Kotlin policies and an engine sit behind thin Android adapters:

| Layer | Package (under `app/src/main/java/com/akylas/enforcedoze/`) | Role |
|-------|---------|------|
| Android shell | root package (`ForceDozeService.java`, Activities, receivers, tiles) | Lifecycle, broadcasts, UI screens, wiring |
| Process runtime | `service/` (`DozeRuntime.kt` and helpers) | One process-owned runtime: controller, `doze-worker` thread, journal, recovery, self-tests |
| Engine | `doze/` (`DozeController.kt`, `RestoreLedger.kt`, `SafetyNet.kt`, `WatchdogPolicy.kt`, …) | Transactional enter / verify / restore with a durable ledger and session generations |
| Parsers | `doze/parse/` | `dumpsys deviceidle` / `sensorservice` / idling-history parsing |
| Access | `access/` (`AccessManager.kt`, `CapabilityResolver.kt`, `CommandCatalog.kt`, `CommandLane.kt`) | Root / Shizuku / app-shell transports, access level, capability matrix, command catalogue |
| Journal | `monitor/` (`JournalDb.kt`, `SessionAggregator.kt`, …) | SQLite event journal, session summaries, reports |
| New UI | `ui/` (`AccessCard`, `DozeMonitorActivity`, `NoticeSink`, …) | Access card, Doze Monitor, notices; owns every user-visible string |

```
 MainActivity / QS tiles / automation receivers / BootCompleteReceiver
                      │  Utils.startForceDozeService (FGS)            ExternalControlReceiver (gates)
                      ▼
            ForceDozeService (foreground specialUse, START_STICKY)
   DozeReceiver: SCREEN_OFF/ON, USER_PRESENT, POWER_CONNECTED, (LIGHT_)DEVICE_IDLE_MODE_CHANGED
                      │  generation bump on receive, work posted to doze-worker
                      ▼
          DozeRuntime (MyApplication.getDozeRuntime, lazy)  ──►  JournalSink (app-owned, MyApplication.getJournal) ──► JournalDb
          SessionLifecycle · SessionAccess · SelfTest                 └──► NoticeSink (notifications)
                      │
                      ▼
          DozeController ── RestoreLedger (SharedPrefsLedgerStore) ── SafetyNet / WatchdogPolicy
                      │
                      ▼
          AccessManager lanes ── RootCommandRunner (su) | Shizuku newProcess | ShellCommandRunner (sh)
```

## Access levels

`AccessManager` publishes an `AccessState` with an `AccessLevel`:
- **ROOT**: verified su (uid 0), or Shizuku running as root.
- **SHELL**: Shizuku running as the shell user.
- **APP**: no privileged transport. Only adb-granted `DUMP` / `WRITE_SECURE_SETTINGS` (`Grants`) apply.
- **NONE**: transport-level only.

Two rules decide what works:
- `CapabilityResolver.status(feature, level, api, grants)` decides per feature what is available.
- `service/SessionAccess.mode(level, grants, sensorsEnabled, resolved)` picks the forward `SessionMode`:
  - **FORCE**: SHELL or ROOT (`canRunSessions`). This is the full session: force-idle, sensors and feature groups.
  - **SENSOR_ONLY**: APP + adb-granted DUMP, with "Disable motion sensors" on. The session only restricts motion sensors
    while Android's natural Doze decides when to idle. `canRunFeature` admits `MOTION_SENSORS` only, and external REAPPLY
    is skipped (`reapplySkip`).
  - **RESTORE_ONLY**: unresolved access or nothing usable. No forward mutation; recovery still runs per feature.

  A session that loses Shizuku mid-session first recovers what it can't keep, then continues as SENSOR_ONLY in the same
  epoch (same session, same delay). It upgrades back to FORCE when access returns. `SafetyNet` exempts only healthy
  intent owned by the admitted session (`keepsSafetyIntent`). The UI shows the same three states honestly
  (`ui/AccessUi`), including a "checking" status while cold-start discovery is unresolved.
  On Main, an enabled service with no usable access reads "on, but not enforcing". Its switch stays usable, so it can
  be turned off (`AccessUi.mainStatus` / `mainSwitchEnabled`).

**Helper grants.** At SHELL/ROOT, `AccessManager` grants the `GrantCommands` helpers:
- DUMP, WRITE_SECURE_SETTINGS and READ_PHONE_STATE;
- SCHEDULE_EXACT_ALARM (API 31+) and usage stats;
- the notification listener and the self-whitelist.

READ_LOGS is never granted, because the grant kills the app.
- **Automatic runs** (service start on doze-worker, Main, Tunables) skip helpers already in the device-local record.
  This keeps a user's revoke in place.
- **Explicit runs** (mode switch, access card, Phone toggle) re-grant.
- At APP or unresolved access, the service only shows the system allowlist dialog or notification.
- See database.md for the record and the reset.

## Core flow

| Step | Where | What happens |
|------|-------|--------------|
| Screen off | `ForceDozeService.DozeReceiver.onReceive` → `receiveOnWorker` | Bumps the generation at once, journals SCREEN_OFF, `SessionLifecycle` activates an epoch, `scheduleEnter` (delay / lock timeout, temp wakelock) |
| Admission | `ForceDozeService.admitted` | Active session, APP + DUMP + motion sensors (SENSOR_ONLY) or SHELL/ROOT (FORCE), healthy ledger, `serviceEnabled`, due time, screen/schedule/charging/call policy |
| Enter | `enterDoze` → `DozeController.enterCore` / `enterGroups` | Reads the original value, **saves the ledger entry before any mutation**, then battery saver → sensor restriction → force-idle. Feature groups (radios, location, biometrics, app suspend, notification block) wait for a verified deep IDLE |
| Verify | `DozeController.verifyEnter` | Readback is the oracle (sensor mode + allow token, `get deep`). Exit code 0 alone is never success; unknown/OEM output is UNVERIFIED and never retried in a loop |
| While dozing | `idleChanged` → `DozeController.maintenance` + `WatchdogPolicy.onIdleChanged` | Radios restored/reapplied around maintenance windows; reforce after motion (`keepDozeEnforced`, ≤5 per session, ≥60 s apart). External REAPPLY uses `WatchdogPolicy.onExternalReapply` (same spacing and budget, never cuts maintenance); a generation bump cancels a deferred reforce |
| Screen on / unlock | `handleScreenOn` → `exitDoze` → `DozeController.exit` | Restores from the ledger, never from current prefs: **sensors → unforce → battery saver → the rest in reverse apply order**. Biometrics are restored at screen-on even when waiting for unlock |
| Safety | `DozeRuntime.checkSafety` (SafetyNet) | Verifies sensors NORMAL and `mForceIdle=false` when the app owns them; otherwise journals RECOVERY_DEBT. Also runs at startup reconcile and on Main resume |
| Service stop | `ForceDozeService.onDestroy` → `runTeardown` / `finishTeardown` / `awaitTeardown` | Worker-side time-boxed exit (3.5 s command budget, 4 s main wait; `SessionLifecycle`) with a deadline admission. `TEARDOWN_TIMEOUT` debt is emitted whenever the 4 s main wait expires, including while the teardown runnable is still queued behind other worker work (`reportTeardownWait`). A throwing exit journals `TEARDOWN_FAILED`. Entries it doesn't reach stay untouched, and any incomplete or failed exit queues a deadline-free restore-only follow-up under the `forcedoze:restore` 30 s wakelock; `finishTeardown` then records the exit and releases the worker wakelock (a racing release on API 23-27 is tolerated) |

Failed restores stay in the ledger (attempts/debt). RECOVERY_DEBT is announced when an entry first fails or its debt flag
changes; RESTORE_FAILED is journaled on every pass. After each safety pass the runtime re-reads the committed ledger
(`DozeRuntime.checkDebtNotice` → `updateRuntimeDebtNotice`) into a `ui/DebtRules.LedgerState`:
- **DEBT**: damaged, unreadable, or any entry attempted or debt-flagged.
- **CLEAN**: readable and undamaged with no attempted or debt-flagged entries. It need not be empty.
- **EMPTY**: readable with no entries.

`NoticeSink.restoresChecked(LedgerState)` drops the notified keys that state settles and cancels the debt notification once
none remain. `RESTORE_WINDOW_STARVED` settles only on EMPTY, because a debt-free ledger can still hold unattempted restore
intent. UI reads (`NoticeSink.ledgerChecked`, from the access card and Monitor) may suppress in-session debt, so they only
re-arm keys and never cancel, and `NoticeSink.cancelDebt` refuses while a starved window is recorded.

Wi-Fi, mobile data and Bluetooth can read stale right after airplane mode is restored. Their restore readback then gets a
bounded settle (`DozeController.settleRadioReadback`):
- at most 3 rereads, 150 ms apart, with a 100 ms read timeout (750 ms per radio);
- it runs only when the command budget still has room for it plus one read;
- a timed-out reread ends the settle with the step UNVERIFIED, so root never reopens `su` just to settle. The access card and Monitor show debt with "Restore now".
Damaged ledger lines: FORCE_DOZE / MOTION_SENSORS lines auto-clear after verified recovery; others stay as dismissible
debt (`ui/DamagedRecords` → `DozeRuntime.clearRetainedCorruption`).

## Restore outside a session

- **Access discovery is bounded.** `access/AccessResolution` reports access "unresolved" only during cold-start
  discovery: a 10 s Shizuku window, root 1 probe + 3 retries. A binder death after the binder was seen means no access
  at once. A service that attaches after a detached window closed root discovery mid-probe *and* that probe timed out
  gets a fresh 1+3 budget (`AccessManager.startServiceRootDiscovery`, from `DozeRuntime.attachService`). Callers pass a
  required ownership flag to `finishRootDiscovery(detached)`: the runtime passes `true` and the two service give-ups pass
  `false`. Any definitive root answer (grant, denial, exception) consumes that handoff. A detached close with no probe
  pending or timed out records nothing, so a later ROOT mode switch keeps its own 1+3. Known root, exhausted budgets and
  service-owned closes never reopen. Unresolved access never touches durable intent. The service re-evaluates readiness only when
  `AccessReadiness.sameCapability` changes (level, resolved, and the `SessionAccess`-derived mode), not on every access
  refresh.
- **Restore-only windows.** `DozeRuntime.requestRestoreOnly` runs without a foreground service: a 9 s
  `RestoreOnlyRequest` window under the 30 s `forcedoze:restore` wakelock that reconciles and runs the safety check once
  SHELL/ROOT is ready. The worker job re-checks its remaining budget when it starts; below `MIN_READY_BUDGET_MS` it runs
  nothing, records no attempts and lets the window finish (arming the continuation). The reconcile runs under
  `DozeRuntime.withDeadline`, so every control command goes through deadline admission
  (`AccessManager.controlWithDeadline` → `CommandLane.runWithDeadline`). The shared deadline covers both lane queues, and
  expired work never reaches the backend. Each command's own execution timeout starts when the lane worker dequeues it,
  still capped by the shared deadline, so a short read doesn't lose its budget waiting behind another command. Entries
  the window doesn't reach keep their ledger fields and journal nothing. Triggers: boot and package update with the service off (gated by `service/BootRestore.restoreIfPending`: the runtime is
  built only inside its callback; `BootRestorePolicy` admits pending entries, recoverable damage and an unreadable ledger, never
  an empty ledger or unrecoverable damage),
  `requestSafetyCheck` (Main / Monitor / access card resume, mode switch) and the teardown follow-up when access is still unresolved.
- **One continuation.** Windows that end without SHELL/ROOT arm the runtime's single process-level
  `RestoreContinuation`: one subscription, one follow-up window when access arrives, then disarmed.
- **Reset.** `DozeRuntime.resetSystemState` (Settings) bumps the generation, restores from the ledger and runs
  `service/SystemReset` on the worker. While a service is attached, `ServiceResetQueue` holds the reset and posts it right
  after that service's teardown runnable, so teardown never waits behind it. The restore outcome is `SystemReset.restoreOutcome`. `OK` means readback-confirmed; "Reset complete" needs restore COMPLETE and every
  step OK and no deferred step pending ("Reset almost done" otherwise). WRITE_SETTINGS is reset through its app-op (`appops set … default`, readback `appops get`). Revoking READ_PHONE_STATE or READ_LOGS kills the app (`PROCESS_KILLING`). Those revokes are
deferred until the user taps OK, then sent as one privileged shell (`SystemReset.runDeferred`). The revokes are
`;`-joined, and each echoes its own `__AKADOZE_RESET_<ID>=$?` status marker, so a later successful revoke can't hide an
earlier failure. A marker above 0 is reported as a failure; a missing marker stays UNVERIFIED. Before any helper
revoke, inline or deferred, the helper's grant-record key is forgotten durably. If that fails, the revoke is skipped. Prefs are never
  cleared there; `ui/ResetReport` clears them, keeping restore-intent keys while debt remains. `SystemReset.runJob` turns a
  throwing job (including an undecodable ledger) into a failed report ("didn't finish… try again") that clears nothing and allows a retry;
  `finishReset` posts under the runtime lock, and the static report tracker survives Activity recreation.

## State

See [database.md](./database.md): default SharedPreferences, the restore ledger (`doze_ledger.xml`), the notice store
(`notices.xml`) and the SQLite journal (`doze_journal.db`).

## Scheduling

Custom Doze periods: `Utils.applyForceDozeSchedule` / `scheduleNextCustomDozePeriodBoundary` (pure logic in
`doze/SchedulePolicy.kt`) set an exact `RTC_WAKEUP` alarm when allowed (`SCHEDULE_EXACT_ALARM` is declared; on 13+ the user grants it), else an inexact
one → `CustomDozePeriodReceiver` → start/stop the service. `ExactAlarmPermissionReceiver` (not exported) re-queries the
grant on `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` and re-arms the next boundary (`doze/ExactAlarmAccessPolicy`);
it never starts the service or applies the current window. Revoking exact-alarm access kills the app without a broadcast, so `MyApplication.onCreate`
re-queries the grant (`Utils.requeryExactAlarmAccess`) and re-arms only the next boundary. Settings shows an "Exact alarms" row (API 31+, only with
periods) that opens the app's own "Alarms & reminders" page and refreshes on resume. Boot re-arms it. Boundaries run only while the user intent
`serviceUserEnabled` is on (`SchedulePolicy.shouldRunService`): explicit OFF cancels the alarm; explicit ON (master switch,
tile, external ENABLE, notification) starts the service at once, then persists the intent and arms the next boundary.

See [communication.md](./communication.md) for transports and commands, [entry-points.md](./entry-points.md) for triggers.
