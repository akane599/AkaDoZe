# Codebase Map — AkaDoZe (EnforceDoze fork)

*Last Updated: 2026-10-04*

AkaDoZe is an Android app that forces Doze right after screen-off and restricts motion sensors. It can also apply
optional radio, location, biometrics, app-suspend and notification-block toggles while the device dozes. It works through
root (libsuperuser) or Shizuku, and every change is verified by readback and undone from a durable restore ledger. The
package is `com.akylas.enforcedoze`, and the current version is 1.11.0 (AkaDoZe 2.0).

**Stack:** Java (54 files) + Kotlin (51) · XML Views + AndroidX Preference · platform SQLite · Groovy Gradle 8.13 / AGP 8.13.2 · minSdk 23, target/compile 36
**Shape:** a single `:app` module in seven packages. The root package is the Android shell; pure Kotlin logic lives in
`access/`, `doze/` (+ `parse/`), `monitor/` and `service/`; the newer screens are in `ui/`.

## Documents

| Document | What's inside |
|----------|---------------|
| [architecture.md](./architecture.md) | Layers, access levels, the screen-off → Doze → restore flow, teardown, debt, restore-only windows, reset |
| [entry-points.md](./entry-points.md) | Manifest components, gated automation API, tiles, alarms, boot/update triggers |
| [communication.md](./communication.md) | Transports and lanes, capability matrix, command catalogue, engine events, local broadcasts |
| [database.md](./database.md) | Preferences, restore ledger format, notice store, SQLite journal schema, backup rules |
| [modules.md](./modules.md) | Every source file grouped by package and role, with directory layout |
| [tech-landscape.md](./tech-landscape.md) | Build setup, dependencies, lint baseline, R8, tooling (fastlane, Weblate) |
| [patterns.md](./patterns.md) | Design patterns, style, test suites and counts, known oddities |
| [onboarding.md](./onboarding.md) | Gate commands, device testing, automation examples, where to change things |

## How to use this map

- New here? Read `onboarding.md`, then `architecture.md`.
- Before touching code, skim the doc(s) for the area you're changing. The main code is in
  `app/src/main/java/com/akylas/enforcedoze/`:
  - Engine behaviour: `doze/DozeController.kt`.
  - Process wiring: `service/DozeRuntime.kt` and `ForceDozeService.java`.
  - Privileged commands: `access/`.
- These docs hold concrete file paths; use them to go straight to the relevant code.

## Keeping this map current

Some changes call for a map refresh: architecture, directory structure, dependencies, the data model, entry points,
APIs/events or conventions. After one of those, refresh the affected docs with the `update-codebase-map` skill
(`/codebase-mapper:update-codebase-map`). Small internal-only changes don't need an update.
