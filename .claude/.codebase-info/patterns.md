# Patterns, Style and Known Oddities

*Last Updated: 2026-10-03*

## Patterns

- **Pure policies behind adapters.** Decisions live in plain Kotlin with no Android imports, so they're JVM-testable:
  - `doze/DozeController.kt`, `SafetyNet.kt`, `WatchdogPolicy.kt`, `SchedulePolicy.kt`;
  - `access/CapabilityResolver.kt`, `ExternalControlPolicy.kt`;
  - `service/SessionLifecycle.kt`, `FeatureSelection.kt`;
  - `ui/DebtRules.java`, `ModeSwitchRules.java`.

  Android classes (`ForceDozeService`, Activities, `JournalDb`, `SharedPrefsLedgerStore`) only adapt.
- **Readback is the oracle.** A command's exit code never proves an effect, so every mutation is confirmed by a state read
  (`CommandCatalog` readbacks, `doze/FeatureReadback.kt`). Unknown or OEM output is UNVERIFIED, which is shown and never
  retried in a loop.
- **Ledger first.** The original value is read and committed to `RestoreLedger` before anything changes. Restore uses the
  ledger, never the current prefs.
- **Generations and admission.** Every screen event bumps a generation. Queued work re-checks the generation and
  `admitted(...)` before it runs a command. Deadline work passes an admission lambda down to `CommandLane.runWithDeadline`.
- **Typed outcomes, UI-owned strings.** Logic returns `Reason`, `EventType`, `ApplyResult` and `CommandResult`. Only `ui/`
  (`MonitorFormat`, `AccessUi`, `NoticeSink`) and Activities turn them into `strings.xml` text.
- **Capability gating, not hiding.** Settings the current access level can't perform are disabled with a reason, and the
  saved values stay unchanged (`SettingsActivity` + `CapabilityResolver`).
- **Prefs as IPC.** Activities write SharedPreferences, then send a local broadcast; the service re-reads.
- **Threading.** Engine work runs on `doze-worker` only. Access listeners run on main. Blocking commands throw if called
  on main.
- **Wakelocks.**
  - `forcedoze:tempWakelock` (10 min cap) covers delayed entry.
  - `forcedoze:restore` (30 s cap) covers the teardown follow-up.
- **Singletons**: `AccessManager`, `MyApplication.getDozeRuntime()`, `DozeTunableHandler.getInstance`,
  `NotificationService.getInstance`.

## Style

- Java 17 source in the root package and `ui/`, Kotlin for all new logic packages.
- Legacy screens use `findViewById`. Classes log through `Utils.logToLogcat` with tag `EnforceDoze`; pref `disableLogcat`
  suppresses it.
- 4-space indent. There's no formatter config. Lint uses `app/lint-baseline.xml`, so only new issues fail.

## Testing

`app/src/test/java/com/akylas/enforcedoze/` has 42 JVM sources with 265 `@Test` methods (JUnit 4.13.2, no mocking library):

| Package | Files | Tests |
|---------|-------|-------|
| root | 2 | 14 |
| access | 8 | 32 |
| doze | 7 | 81 |
| doze/parse | 6 | 23 |
| monitor | 4 | 28 |
| service | 12 | 64 |
| ui | 3 | 23 |

The suites use these styles:
- fake-backed behaviour tests: `FakeRunner` (a `CommandRunner`), `FakeClock` and an in-memory ledger store, in
  `doze/DozeControllerTest.kt`;
- `*JavaApiTest.java` files, which pin the Kotlin API as Java callers see it;
- source-text wiring tests in `service/` that assert `ForceDozeService` wiring.

The parser fixture is `app/src/test/resources/doze/deviceidle.txt`, and `ParserFixtures.kt` holds inline samples.

`androidTest/…/ApplicationTest.java` is still a template stub. Real binder, root, OEM, SQLite, provider and notification
behaviour needs a device: `docs/device-test-1.11.0.md`.

## Known oddities

- Strings and the README still say "EnforceDoze", and the package stays `com.akylas.enforcedoze`.
- `ShizukuHandler.java` remains as a compatibility listener alongside `AccessManager`.
- `libsuperuser:1.1.0.+` is a dynamic version, and `jcenter()` is still a repository (`build.gradle`).
- The legacy notification block below Android 13 (`service call notification`) is root-only and UNVERIFIED on devices.
- Exported automation receivers have no Android permission. Trust comes from the two in-app gates
  (see entry-points.md).
