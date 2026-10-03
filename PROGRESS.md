# PROGRESS.md — AkaDoZe

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-03 @ db47ccd: `:app:assembleDebug` ok; `:app:testDebugUnitTest` fails to compile (pre-existing, not ours):
  - ExampleUnitTest (template stub): `org.junit` missing; app/build.gradle declares no `testImplementation` deps. 1 androidTest stub (ApplicationTest), not run.

## Decision log (never delete; one line each)
- 2026-10-03: Adopted android-kit at db47ccd (fork mode, integration branch Base, kit files committed).
- 2026-10-03: added `testImplementation 'junit:junit:4.13.2'` so JVM tests compile; JUnit 4 over 5 because the existing stub and AGP default runner use it. (SQ-1)
- 2026-10-03: AkaDoZe 2.0 plan audited (SQ-3 Astra REWORK, SQ-4 Opus APPROVE WITH FIXES; stricter taken): restoration became a durable ledger transaction with session generations, readback-as-oracle, logic emits Reason enums while UI owns strings, hotfix wave first, service rewrite split 6a/6b/6c, external privileged control default off.

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Plan audit | 2026-10-03 | REWORK → plan reworked (AkaDoZe 2.0; SQ-3, SQ-4) | `/plan-audit` |
| Bug hunt | — | — | `/bug-hunt` |
| Security | — | — | `/claude-security` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Lint | 2026-10-03 | baseline captured: 160 pre-existing issues in app/lint-baseline.xml (only new issues fail) | `./gradlew :app:lintDebug -q` |
| Device QA | — | — | android-emulator-qa skill |

## Environment notes
- 3 GB RAM / 2 cores / no KVM: one Gradle job at a time, no emulator; device QA needs a physical device with root or Shizuku.
- Build pulls from jcenter (read-only mirror) and jitpack; resolves today but is a supply-chain/availability risk.
