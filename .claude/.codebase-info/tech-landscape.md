# Tech Landscape

*Last Updated: 2026-10-03*

| Item | Value | Source of truth |
|------|-------|-----------------|
| Gradle | 8.13 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 8.13.2 | `build.gradle` (buildscript classpath) |
| Kotlin | 2.0.0 (`ext.kotlin_version`) | `build.gradle` |
| JDK target | 17 (`compileOptions`, `jvmTarget`) | `app/build.gradle` |
| SDK | compile/target 36, min 23 | `app/build.gradle` |
| Version | `versionCode 86`, `versionName "1.10.2"` | `app/build.gradle` |
| Repos | `jcenter()`, `google()`, jitpack | `build.gradle` |
| Gradle props | `-Xmx2048m`, Jetifier on, non-transitive R | `gradle.properties` |

Build scripts are Groovy (`apply plugin:`), no version catalog, no build-logic. Signing: debug key by
default; release signing from env vars when `-PuseExternalSigning` is passed.

## Dependencies (`app/build.gradle`)

| Category | Library |
|----------|---------|
| UI | `androidx.appcompat:appcompat:1.7.0`, `com.google.android.material:material:1.12.0`, `com.afollestad.material-dialogs:core:0.9.3.0`, `androidx.preference:preference-ktx:1.2.1`, `androidx.browser:browser:1.8.0` |
| Privilege | `eu.chainfire:libsuperuser:1.1.0.+` (dynamic version), `dev.rikka.shizuku:api` + `provider` 13.1.5 |
| Misc | `androidx.media2:media2-session:1.3.0` (media controller for playing-app detection), `androidx.localbroadcastmanager:1.1.0`, `com.fabiendevos:nanotasks:1.1.0` (async tasks), `com.jakewharton:process-phoenix:2.1.2` (app restart) |
| Test | none declared (stub test cannot compile) |

No DI, database, networking, coroutines, Compose, lint or formatter config.

## Tooling outside Gradle

- `fastlane/Fastfile`: lanes `setup`, `build_and_publish`, `build_flavor`, `get_changelog`, `write_changelog`, `get_version`; Ruby via `Gemfile`.
- `fastlane/metadata/android/`: store listing + changelogs.
- `.weblate`: translations from hosted Weblate (`enforcedoze/application-strings`).
- `docs/index.html`: static landing page.
