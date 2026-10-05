# Architecture

*Last Updated: 2026-10-05*

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
          DozeRuntime (MyApplication.getDozeRuntime, lazy)  ──►  JournalSink ──► JournalDb (SQLite)
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
- `service/SessionAccess.canRunSessions(level)` decides whether Doze sessions run at all, and **requires SHELL or ROOT**. APP+DUMP is
  read-only plus recovery: it can read Doze state and undo the app's own sensor restriction after Shizuku dies, but it never
  starts a session. The UI applies the same rule through `ui/AccessUi.sessionsAvailable`.

## Core flow

| Step | Where | What happens |
|------|-------|--------------|
| Screen off | `ForceDozeService.DozeReceiver.onReceive` → `receiveOnWorker` | Bumps the generation at once, journals SCREEN_OFF, `SessionLifecycle` activates an epoch, `scheduleEnter` (delay / lock timeout, temp wakelock) |
| Admission | `ForceDozeService.admitted` | Active session, SHELL/ROOT, healthy ledger, `serviceEnabled`, due time, screen/schedule/charging/call policy |
| Enter | `enterDoze` → `DozeController.enterCore` / `enterGroups` | Reads the original value, **saves the ledger entry before any mutation**, then battery saver → sensor restriction → force-idle. Feature groups (radios, location, biometrics, app suspend, notification block) wait for a verified deep IDLE |
| Verify | `DozeController.verifyEnter` | Readback is the oracle (sensor mode + allow token, `get deep`). Exit code 0 alone is never success; unknown/OEM output is UNVERIFIED and never retried in a loop |
| While dozing | `idleChanged` → `DozeController.maintenance` + `WatchdogPolicy.onIdleChanged` | Radios restored/reapplied around maintenance windows; reforce after motion (`keepDozeEnforced`, ≤5 per session, ≥60 s apart). External REAPPLY uses `WatchdogPolicy.onExternalReapply` (same spacing and budget, never cuts maintenance); a generation bump cancels a deferred reforce |
| Screen on / unlock | `handleScreenOn` → `exitDoze` → `DozeController.exit` | Restores from the ledger, never from current prefs: **sensors → unforce → battery saver → the rest in reverse apply order**. Biometrics are restored at screen-on even when waiting for unlock |
| Safety | `DozeRuntime.checkSafety` (SafetyNet) | Verifies sensors NORMAL and `mForceIdle=false` when the app owns them; otherwise journals RECOVERY_DEBT. Also runs at startup reconcile and on Main resume |
| Service stop | `ForceDozeService.onDestroy` | Worker-side time-boxed exit (3.5 s command budget, 4 s main wait; `SessionLifecycle`) with a deadline admission. `TEARDOWN_TIMEOUT` debt is emitted only when the teardown runnable started and didn't finish (`TeardownTimeout`). Entries it doesn't reach stay untouched, and an incomplete exit queues a deadline-free restore-only follow-up under the `forcedoze:restore` 30 s wakelock |

Failed restores stay in the ledger (attempts/debt). RECOVERY_DEBT is announced when an entry first fails or its debt flag
changes; RESTORE_FAILED is journaled on every pass. The access card and Monitor show debt with "Restore now".
Damaged ledger lines: FORCE_DOZE / MOTION_SENSORS lines auto-clear after verified recovery; others stay as dismissible
debt (`ui/DamagedRecords` → `DozeRuntime.clearRetainedCorruption`).

## Restore outside a session

- **Access discovery is bounded.** `access/AccessResolution` reports access "unresolved" only during cold-start
  discovery: a 10 s Shizuku window, root 1 probe + 3 retries. A binder death after the binder was seen means no access
  at once. A service that attaches after a detached window closed root discovery gets a fresh 1+3 budget
  (`AccessManager.startServiceRootDiscovery`, from `DozeRuntime.attachService`). Unresolved access never touches durable intent.
- **Restore-only windows.** `DozeRuntime.requestRestoreOnly` runs without a foreground service: a 9 s
  `RestoreOnlyRequest` window under the 30 s `forcedoze:restore` wakelock that reconciles and runs the safety check once
  SHELL/ROOT is ready. The worker job re-checks its remaining budget when it starts; below `MIN_READY_BUDGET_MS` it runs
  nothing, records no attempts and lets the window finish (arming the continuation). Triggers: boot and package update with the service off (gated by `service/BootRestore.hasPending`),
  `requestSafetyCheck` (Main / Monitor / access card resume, mode switch) and the teardown follow-up when access is still unresolved.
- **One continuation.** Windows that end without SHELL/ROOT arm the runtime's single process-level
  `RestoreContinuation`: one subscription, one follow-up window when access arrives, then disarmed.
- **Reset.** `DozeRuntime.resetSystemState` (Settings) bumps the generation, restores from the ledger and runs
  `service/SystemReset` on the worker. While a service is attached, `ServiceResetQueue` holds the reset and posts it right
  after that service's teardown runnable, so teardown never waits behind it. The restore outcome is `SystemReset.restoreOutcome`. `OK` means readback-confirmed; "Reset complete" needs restore COMPLETE and every
  step OK and no deferred step pending ("Reset almost done" otherwise). WRITE_SETTINGS is reset through its app-op (`appops set … default`, readback `appops get`). Prefs are never
  cleared there; `ui/ResetReport` clears them, keeping restore-intent keys while debt remains. `SystemReset.runJob` turns a
  throwing job (including an undecodable ledger) into a failed report ("didn't finish… try again") that clears nothing and allows a retry;
  `finishReset` posts under the runtime lock, and the static report tracker survives Activity recreation.

## State

See [database.md](./database.md): default SharedPreferences, the restore ledger (`doze_ledger.xml`), the notice store
(`notices.xml`) and the SQLite journal (`doze_journal.db`).

## Scheduling

Custom Doze periods: `Utils.applyForceDozeSchedule` / `scheduleNextCustomDozePeriodBoundary` (pure logic in
`doze/SchedulePolicy.kt`) set an exact `RTC_WAKEUP` alarm when allowed (`SCHEDULE_EXACT_ALARM` is declared; on 13+ the user grants it), else an inexact
one → `CustomDozePeriodReceiver` → start/stop the service. Boot re-arms it. Boundaries run only while the user intent
`serviceUserEnabled` is on (`SchedulePolicy.shouldRunService`): explicit OFF cancels the alarm; explicit ON (master switch,
tile, external ENABLE, notification) starts the service at once, then persists the intent and arms the next boundary.

See [communication.md](./communication.md) for transports and commands, [entry-points.md](./entry-points.md) for triggers.
