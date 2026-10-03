# PROGRESS.md — AkaDoZe

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-03 @ db47ccd: `:app:assembleDebug` ok; `:app:testDebugUnitTest` fails to compile (pre-existing, not ours):
  - ExampleUnitTest (template stub): `org.junit` missing; app/build.gradle declares no `testImplementation` deps. 1 androidTest stub (ApplicationTest), not run.

## Decision log (never delete; one line each)
- 2026-10-03: Adopted android-kit at db47ccd (fork mode, integration branch Base, kit files committed).
- 2026-10-03: added `testImplementation 'junit:junit:4.13.2'` so JVM tests compile; JUnit 4 over 5 because the existing stub and AGP default runner use it. (SQ-1)
- 2026-10-03: AkaDoZe 2.0 plan audited (SQ-3 Astra REWORK, SQ-4 Opus APPROVE WITH FIXES; stricter taken): restoration became a durable ledger transaction with session generations, readback-as-oracle, logic emits Reason enums while UI owns strings, hotfix wave first, service rewrite split 6a/6b/6c, external privileged control default off.
- 2026-10-03: AkaDoZe 2.0 (US-1) lives on branch `akadoze-2.0`; Base stays at 42f8e73 so the user can compare both (user direction). Released as 1.11.0 / versionCode 87, not 2.0: same package, upgrades in place, every settings key kept.
- 2026-10-03: Restore engine: the durable ledger is written before any mutation; session generation and admission are checked before mutating; readback is the only success oracle; restore order is sensors, then unforce, then the rest in reverse; all engine work runs on the single doze-worker thread. (US-1 #1, #7)
- 2026-10-03: Motion sensors are restricted only when the original readback is NORMAL; pre-existing or OEM modes stay UNVERIFIED and untouched. Already-on features are never adopted for restore. (US-1 #4, #5)
- 2026-10-03: Doze sessions require SHELL or ROOT (`SessionAccess.canRunSessions`); APP+DUMP keeps readback and the sensor restore after Shizuku death (CapabilityResolver unchanged). APP-level sensor-only sessions were rejected for this release: they change admission in the high-stakes engine with no device to verify (follow-up SQ-41). (US-1 #17)
- 2026-10-03: External control: basic start/stop gate on by default, privileged gate off; an external REAPPLY never cancels a pending enter or shortens the user's `dozeEnterDelay`. (US-1 #13, #15)
- 2026-10-03: Self-tests run only while the service is attached; no reconcile on every process start, so a cold start never builds the runtime or asks for su. (US-1 #16)
- 2026-10-03: Teardown: the command budget starts on the worker; an incomplete exit queues one deadline-free, restore-only follow-up under a 30 s wakelock; whatever remains becomes per-entry RECOVERY_DEBT. Entries the time-boxed pass never reached stay untouched, and RECOVERY_DEBT is emitted only when an entry first fails or its debt flag changes, so the notice isn't re-posted every screen cycle. (US-1 #17, #19; SQ-39, SQ-43)
- 2026-10-03: The auto-rotate/brightness workaround is retired (key kept): it made setting changes the ledger couldn't restore. (US-1 #9)
- 2026-10-03: User-visible strings say "EnforceDoze" (matches `app_name` and the translations); the repo and branch keep the AkaDoZe name. A full rebrand is the user's call.
- 2026-10-03: Parallel dispatch on the 22 GB machine: up to 4 Gradle tickets and at most 3 concurrent Opus agents (user direction). (US-1 #10, #11)

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Plan audit | 2026-10-03 | REWORK → plan reworked (AkaDoZe 2.0; SQ-3, SQ-4) | `/plan-audit` |
| Story review | 2026-10-03 | US-1 whole-story review SQ-19: 1 BLOCKER, 1 FIX, 6 NIT → fixed in SQ-39/SQ-40, rest deferred to SQ-41; per-ticket reviews SQ-25, SQ-30, SQ-31, SQ-36, SQ-42 | `review-audit` (Opus) |
| Bug hunt | — | — | `/bug-hunt` |
| Security | — | — | `/claude-security` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Lint | 2026-10-03 | baseline captured: 160 pre-existing issues in app/lint-baseline.xml (only new issues fail) | `./gradlew :app:lintDebug -q` |
| Device QA | — | pending: no device here; run `docs/device-test-1.11.0.md` on a rooted or Shizuku phone | physical device (no KVM) |

## Environment notes
- 3 GB RAM / 2 cores / no KVM: one Gradle job at a time, no emulator; device QA needs a physical device with root or Shizuku.
- Build pulls from jcenter (read-only mirror) and jitpack; resolves today but is a supply-chain/availability risk.
