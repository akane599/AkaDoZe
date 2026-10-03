# Codebase Map — AkaDoZe (EnforceDoze fork)

*Last Updated: 2026-10-03*

Android app that forces Doze immediately after screen-off and restricts motion sensors (plus optional
Wi-Fi/data/BT/GPS/airplane/app/notification toggles while dozing), using root (libsuperuser), Shizuku,
or a limited non-root fallback. Package `com.akylas.enforcedoze`, everything in one Java package.

**Stack:** Java (43 files) + Kotlin (2) · XML Views + AndroidX Preference · Groovy Gradle 8.13 / AGP 8.13.2 · minSdk 23, target/compile 36
**Shape:** single `:app` module, flat package; one large foreground service holds the logic, Activities are settings/list screens; state in default SharedPreferences.

## Documents

| Document | What's inside |
|----------|---------------|
| [architecture.md](./architecture.md) | Components, the screen-off → Doze → screen-on flow, state |
| [entry-points.md](./entry-points.md) | Manifest components, public broadcast API (Tasker), tiles, alarms |
| [communication.md](./communication.md) | Privileged shell commands (root / Shizuku / sh), local broadcasts, system APIs |
| [modules.md](./modules.md) | Every source file grouped by role, with directory layout |
| [tech-landscape.md](./tech-landscape.md) | Build setup, dependencies, tooling (fastlane, Weblate, docs site) |
| [patterns.md](./patterns.md) | Recurring patterns, coding style, testing state, known oddities |
| [onboarding.md](./onboarding.md) | Build/run/debug commands and common change recipes |

## How to use this map

- New here? Read `onboarding.md` then `architecture.md`.
- Before touching code, skim the doc(s) for the area you're changing. Most behaviour lives in
  `app/src/main/java/com/akylas/enforcedoze/ForceDozeService.java`.
- These docs hold concrete file paths — use them to navigate straight to the relevant code.

## Keeping this map current

After a change that affects architecture, directory structure, dependencies, the data model, entry
points, APIs/events, or conventions, refresh the affected docs with the `update-codebase-map` skill
(`/codebase-mapper:update-codebase-map`). Small, internal-only changes don't need an update.
