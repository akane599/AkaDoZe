# Tech Landscape

*Last Updated: 2026-10-06*

| Item | Value | Source of truth |
|------|-------|-----------------|
| Gradle | 8.13 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 8.13.2 | `build.gradle` (buildscript classpath) |
| Kotlin | 2.0.0 (`ext.kotlin_version`) | `build.gradle` |
| JDK target | 17 (`compileOptions`, `jvmTarget`) | `app/build.gradle` |
| SDK | compile/target 36, min 23 | `app/build.gradle` |
| Version | `versionCode 87`, `versionName "1.11.0"` (AkaDoZe 2.0) | `app/build.gradle`, `CHANGELOG.md` |
| Repos | `jcenter()`, `google()`, jitpack | `build.gradle` |
| Gradle props | `-Xmx2048m`, Jetifier on, non-transitive R | `gradle.properties` |
| Lint | `lint { baseline = file("lint-baseline.xml") }` (159 pre-existing issues; only new ones fail) | `app/build.gradle`, `app/lint-baseline.xml` |
| Coverage | Opt-in only: `-PcrapCoverage` turns on `debug.enableUnitTestCoverage` (JaCoCo pinned to 0.8.13 via `testCoverage`). Normal builds are byte-identical | `app/build.gradle` |
| R8 | release is minified; keeps `rikka.shizuku.Shizuku` / `ShizukuRemoteProcess` for the reflective `newProcess` call | `app/proguard-rules.pro` |

Build scripts are Groovy (`apply plugin:`), no version catalog, no build-logic. Signing: debug key by
default; release signing from env vars when `-PuseExternalSigning` is passed.

## Dependencies (`app/build.gradle`)

| Category | Library |
|----------|---------|
| UI | `androidx.appcompat:appcompat:1.7.0`, `com.google.android.material:material:1.12.0`, `com.afollestad.material-dialogs:core:0.9.3.0`, `androidx.preference:preference-ktx:1.2.1`, `androidx.browser:browser:1.8.0` |
| Privilege | `eu.chainfire:libsuperuser:1.1.0.+` (dynamic version), `dev.rikka.shizuku:api` + `provider` 13.1.5 |
| Misc | `androidx.media2:media2-session:1.3.0` (media controller for playing-app detection), `androidx.localbroadcastmanager:1.1.0`, `com.fabiendevos:nanotasks:1.1.0` (async tasks), `com.jakewharton:process-phoenix:2.1.2` (app restart) |
| Test | `junit:junit:4.13.2` (testImplementation) |

AkaDoZe 2.0 added no dependencies. Storage is platform SQLite (`android.database.sqlite`, see database.md) and
SharedPreferences. There is no DI, Room, networking, coroutines, Compose or formatter config.

## Tooling outside Gradle

- `fastlane/Fastfile`: lanes `setup`, `build_and_publish`, `build_flavor`, `get_changelog`, `write_changelog`, `get_version`; Ruby via `Gemfile`.
- `fastlane/metadata/android/`: store listing + changelogs.
- `.weblate`: translations from hosted Weblate (`enforcedoze/application-strings`).
- `docs/index.html`: static landing page.

## Coverage and the CRAP gate

The Quartermaster plugin (0.11.10) gates CRAP: cyclomatic complexity combined with test coverage, at a fixed ceiling of 6
against `Base`.
- **Config.** `.claude/quartermaster/crap.json` sets sources `app/src/main/java` and base `Base`.
- **Coverage command.** `.claude/kit/crap-coverage.sh` is fail-closed. It runs
  `:app:createDebugUnitTestCoverageReport -PcrapCoverage` with no daemon, no cache and every task rerun, which takes
  about 35 s. It checks that the native exec and class identities match.
- **Conversion.** `.claude/kit/jacoco-to-lcov.py` is a stdlib-only converter from JaCoCo XML to LCOV, with tests in
  `test_jacoco_to_lcov.py`. LCOV is published atomically, so stale or partial output gives exit 2.
- **Prerequisites.** lizard 1.24.0 lives in a user venv at `~/.local/bin/lizard`; the wrapper refuses any other version.
- **Run it** as described in onboarding.md. Exit 1 on akadoze-2.0 is expected: everything written for 2.0 counts as new
  against Base. The live rule `.claude/live-rules/rules/crap-gate.md` judges only the functions a change adds or
  modifies.
- **Limitations.**
  - JaCoCo filters out empty private constructors, which leaves them unmeasured (exit 2). Utility classes use
    `private X() { throw new AssertionError(); }` instead.
  - lizard 1.24.0's Kotlin parsing is unreliable, and Quartermaster scores only the rows lizard emits. It still
    reports `unmeasured=0` when:
    - it omits expression-body functions (`fun f() = ...`);
    - it merges local or nested functions into one row (a local `fun admitted()` took its enclosing body);
    - it truncates a function at an inline lambda;
    - it gives an unchanged expression-body function a bogus span (`DozeController.enterGroupsSafely` 59–284, cc6).

    US-7 practice: helpers a change adds use block bodies with explicit return types so they get real rows. Anything
    still omitted or truncated is hand-scored in the ticket: cc by lizard's rules, line coverage from JaCoCo, then
    `cc²(1−cov)³ + cc`.
  - Line coverage is not branch coverage.
