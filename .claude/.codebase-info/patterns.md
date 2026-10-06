# Patterns, Style and Known Oddities

*Last Updated: 2026-10-06*

## Patterns

- **Pure policies behind adapters.** Decisions live in plain Kotlin with no Android imports, so they're JVM-testable:
  - `doze/DozeController.kt`, `SafetyNet.kt`, `WatchdogPolicy.kt`, `SchedulePolicy.kt`;
  - `access/CapabilityResolver.kt`, `ExternalControlPolicy.kt`;
  - `service/SessionLifecycle.kt`, `FeatureSelection.kt`;
  - `ui/DebtRules.java`, `ModeSwitchRules.java`, `SettingsRules.java`, `AccessUi.AccessUpdate`.

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
  - `forcedoze:restore` (30 s cap) covers the teardown follow-up and every restore-only window.
- **minSdk 23 SAMs**: no `java.util.function` in production; use project interfaces (`MyApplication.Factory`/`Callback`,
  `ExternalControlReceiver.Admission.Policy`/`Summary`) or Kotlin function types.
- **Singletons**: `AccessManager`, `MyApplication.getDozeRuntime()`, `MyApplication.getJournal()`, `DozeTunableHandler.getInstance`,
  `NotificationService.getInstance`.

## Style

- Java 17 source in the root package and `ui/`, Kotlin for all new logic packages.
- Legacy screens use `findViewById`. Classes log through `Utils.logToLogcat` with tag `EnforceDoze`; pref `disableLogcat`
  suppresses it.
- 4-space indent. There's no formatter config. Lint uses `app/lint-baseline.xml`, so only new issues fail.

## Testing

`app/src/test/java/com/akylas/enforcedoze/` has 95 JVM sources with 755 `@Test` methods (JUnit 4.13.2 plus Robolectric 4.16,
no mocking library):

| Package | Files | Tests |
|---------|-------|-------|
| root | 5 | 29 |
| access | 17 | 86 |
| doze | 12 | 144 |
| doze/parse | 7 | 36 |
| monitor | 7 | 53 |
| service | 34 | 296 |
| ui | 13 | 111 |

The suites use these styles:
- fake-backed behaviour tests: `FakeRunner` (a `CommandRunner`), `FakeClock` and an in-memory ledger store, in
  `doze/DozeControllerTest.kt`;
- `*JavaApiTest.java` files, which pin the Kotlin API as Java callers see it;
- source-text wiring tests in `service/` that assert `ForceDozeService` wiring. They break when a call moves, so update
  the anchor in the same change;
- characterization goldens. These are written before a behaviour-preserving split and must pass unchanged across it:
  - `access/CommandCatalogReadbackGoldenTest.kt`: every Feature × API 23..36 × null/valid/invalid target readback, as
    literal rows;
  - `monitor/MonitorCharacterizationTest.kt`;
  - `doze/RestoreCharacterizationTest.kt`: restore order, saves, events and settle waits; access lost mid-entry; the
    single-command radio guard that keeps maintenance's mid-entry `lost` path unreachable.

  A deliberate behaviour change updates the golden in the same commit.
- `*RobolectricTest` classes cover Android glue on the JVM: `ui/NoticeSinkRobolectricTest`, `ResetReportRobolectricTest`,
  `SettingsFragmentRobolectricTest`, `ForceDozeServiceRobolectricTest` and `MyApplicationRobolectricTest`. They follow
  three rules:
  - Use `@Config(application = Application.class)`, so `MyApplication` never builds DozeRuntime or the journal.
  - Reset the statics you touch in @After: AccessManager, the MyApplication context/runtime, NoticeSink and
    `ResetReport.TRACKER`. Root-package tests use `TestAppState.reset()`; the ui tests reset their own.
  - Never spawn su, bind Shizuku or start a real service.

  SettingsActivity is hosted only in Shizuku mode with no binder, and its key → enabled/visible/summary goldens pin the
  preference wiring. MainActivity isn't hosted, because its `onCreate` starts AccessManager discovery; its glue is cc1
  over `AccessUi` rules. ForceDozeService is built without `onCreate`, with its narrow adapter methods overridden.

The parser fixture is `app/src/test/resources/doze/deviceidle.txt`, and `ParserFixtures.kt` holds inline samples.

`androidTest/…/ApplicationTest.java` is still a template stub. Real binder, root, OEM, SQLite, provider and notification
behaviour needs a device: `docs/device-test-1.11.0.md`. Robolectric shadows don't replace that.

## Known oddities

- Strings and the README still say "EnforceDoze", and the package stays `com.akylas.enforcedoze`.
- `ShizukuHandler.java` remains as a compatibility listener alongside `AccessManager`.
- `libsuperuser:1.1.0.+` is a dynamic version, and `jcenter()` is still a repository (`build.gradle`).
- The legacy notification block below Android 13 (`service call notification`) is root-only and UNVERIFIED on devices.
- Exported automation receivers have no Android permission. Trust comes from the two in-app gates
  (see entry-points.md).
