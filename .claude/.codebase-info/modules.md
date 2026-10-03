# Modules and Files

*Last Updated: 2026-10-03*

Single Gradle module `:app`; single package `com.akylas.enforcedoze` at
`app/src/main/java/com/akylas/enforcedoze/` (43 `.java`, 2 `.kt`).

## By role

| Role | Files |
|------|-------|
| Engine | `ForceDozeService.java` (Doze, radios, sensors, stats, notifications), `DozeTunableHandler.java` + `DozeTunableConstants.java` (device_idle constants) |
| Privilege | `ShizukuHandler.java` (singleton), libsuperuser calls spread across service/activities |
| Shared helpers | `Utils.java` (start/stop service, custom period scheduling, permission checks, device-state queries, tile/notification helpers, `logToLogcat`) |
| Screens | `MainActivity`, `SettingsActivity`, `DozeTunablesActivity`, `WhitelistAppsActivity`, `BlockAppsActivity`, `BlockNotificationsActivity`, `DozeBatteryStatsActivity`, `DozeStatsActivity`, `LogActivity`, `TaskerBroadcastsActivity`, `PackageChooserActivity`, `AboutAppActivity`, `RequestIgnoreBatteryActivity` |
| List adapters / items | `AppsAdapter`+`AppsItem`, `BatteryConsumptionAdapter`+`BatteryConsumptionItem`, `DozeStatsAdapter`+`DozeStatsCard`, `TaskerBroadcastsAdapter`+`TaskerBroadcastsItem` |
| Preferences UI | `NumberPickerPreference.java`, `MaterialListPreference.kt` |
| Receivers | `BootCompleteReceiver`, `AutoRestartOnUpdate`, `EnableForceDozeService`, `DisableForceDozeService`, `AddWhiteListReceiver`, `RemoveWhiteListReceiver`, `SettingsChangeReceiver`, `ReenterDoze`, `CustomDozePeriodReceiver` |
| Tiles / listener | `ForceDozeTileService`, `AirplaneTileService`, `NotificationService.kt` |
| Web | `CustomTabs.java`, `ChromePackageHelper.java` |
| App | `MyApplication.java` |

## Directory layout

```
app/
├── build.gradle               # Groovy; deps declared inline, no version catalog
├── proguard-rules.pro         # release is minified + resource-shrunk
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   ├── java/com/akylas/enforcedoze/   # all code (flat)
    │   └── res/
    │       ├── layout/        # 20 XML layouts (activity_*, list_row_*)
    │       ├── xml/           # prefs.xml, prefs_doze_tunables.xml, shortcuts.xml, changelog_master.xml
    │       ├── values*/       # strings in 15+ locales (Weblate), styles, colors, dimens
    │       └── drawable*/, mipmap*/
    ├── test/…/ExampleUnitTest.java       # template stub only
    └── androidTest/…/ApplicationTest.java # template stub only
build.gradle, settings.gradle, gradle.properties, gradle/wrapper/
fastlane/        # release lanes + Play/F-Droid metadata (fastlane/metadata/android)
docs/index.html  # static landing page
.weblate         # Weblate project config
Gemfile*, .bundle/  # Ruby deps for fastlane
```
