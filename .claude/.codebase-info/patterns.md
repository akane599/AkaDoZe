# Patterns, Style and Known Oddities

*Last Updated: 2026-10-03*

## Patterns

- **God service.** `ForceDozeService` owns all behaviour; feature flags are fields reloaded from prefs in both
  `onCreate` and `reloadSettings` (two parallel lists to keep in sync when adding a setting).
- **Adding a setting** touches: `res/xml/prefs.xml`, `strings.xml` (+ translations via Weblate),
  `ForceDozeService` (field, `onCreate`, `reloadSettings`, use site), possibly `SettingsActivity.toggleRootFeatures`
  (root-only enablement) and `Utils.doesSettingExist`/`isSettingBool` (Tasker `CHANGE_SETTING`).
- **Prefs as IPC.** Activities write SharedPreferences, then send a local broadcast; the service re-reads.
- **Shell as API.** Feature work is mostly a new `dumpsys`/`svc`/`settings`/`pm` command; each needs a root,
  Shizuku and (where possible) non-root path.
- **Timers.** `java.util.Timer` with fixed 2 s delays for sensor restrict/enable; wakelock `forcedoze:tempWakelock` (10 min cap) during delayed entry.
- **Snapshot/restore.** Radio state captured on Doze entry (`was*` fields), restored on screen-on, reset after.
- **Singletons**: `ShizukuHandler.getInstance`, `DozeTunableHandler.getInstance`, `NotificationService.getInstance`, `MyApplication.getAppContext`.

## Style

- Java 17 source with some `var` and lambdas; legacy anonymous classes common. Kotlin only in `NotificationService.kt`, `MaterialListPreference.kt`.
- `findViewById` + casts (no ViewBinding). Each class has `TAG` + private `log()` → `Utils.logToLogcat` (suppressed by pref `disableLogcat`).
- Error handling: `try { … } catch (Exception e) { e.printStackTrace(); }`; shell failures only logged.
- 4-space indent, no formatter/lint config in repo. Hard-coded English labels exist in the manifest (`android:label="Settings"` etc.).

## Testing

None real: `app/src/test/…/ExampleUnitTest.java` and `app/src/androidTest/…/ApplicationTest.java` are template
stubs, and the only test dependency is `testImplementation 'junit:junit:4.13.2'` (added 2026-10-03, SQ-1).
Most logic needs a rooted/Shizuku device; pure logic worth unit-testing: `Utils` custom-period parsing
(`isInsideCustomDozePeriod`, `getMillisUntilNextCustomDozePeriodBoundary`), `DozeTunableHandler` string/command
building, `ForceDozeService.getDeviceIdleState` parsing.

## Known oddities (observed, not fixed)

- `ReenterDoze` sends local `reenter-doze`; nothing registers for it.
- `ForceDozeService.onDestroy` unregisters `reloadSettingsReceiver` and `ignoreBatteryResultReceiver` but not the two blocklist receivers.
- `LogActivity` filters logcat by tags `ForceDozeService`/`ForceDoze`, while most classes log as `EnforceDoze`.
- Exported receivers (`ENABLE_FORCEDOZE`, `CHANGE_SETTING`, `ADD_WHITELIST`, …) accept broadcasts from any app without a permission.
- `libsuperuser:1.1.0.+` is a dynamic version; `jcenter()` is still a repository.
