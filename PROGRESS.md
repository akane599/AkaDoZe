# PROGRESS.md — AkaDoZe

_Work items, stories and blockers live on the Sidequest board. This file keeps what outlives tickets._

## Baseline
- 2026-10-03 @ db47ccd: `:app:assembleDebug` ok; `:app:testDebugUnitTest` fails to compile (pre-existing, not ours):
  - ExampleUnitTest (template stub): `org.junit` missing; app/build.gradle declares no `testImplementation` deps. 1 androidTest stub (ApplicationTest), not run.

## Decision log (never delete; one line each)
- 2026-10-03: Adopted android-kit at db47ccd (fork mode, integration branch Base, kit files committed).

## Audit status
| Area | Last run | Result | How |
|---|---|---|---|
| Plan audit | — | — | `/plan-audit` |
| Bug hunt | — | — | `/bug-hunt` |
| Security | — | — | `/claude-security` |
| UI / design | — | — | `/ui-overhaul` phase review |
| Lint | — | — | `./gradlew :app:lintDebug -q` |
| Device QA | — | — | android-emulator-qa skill |

## Environment notes
- 3 GB RAM / 2 cores / no KVM: one Gradle job at a time, no emulator; device QA needs a physical device with root or Shizuku.
- Build pulls from jcenter (read-only mirror) and jitpack; resolves today but is a supply-chain/availability risk.
