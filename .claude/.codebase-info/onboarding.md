# Onboarding

*Last Updated: 2026-10-03*

## Build and run

```bash
bash .claude/kit/gradle-check.sh :app:assembleDebug          # debug APK → app/build/outputs/apk/debug/
bash .claude/kit/gradle-check.sh :app:testDebugUnitTest      # JUnit 4, only a stub test so far
./gradlew :app:installDebug -q && adb shell am start -n com.akylas.enforcedoze/.MainActivity
adb logcat -d --pid=$(adb shell pidof -s com.akylas.enforcedoze) | tail -80
```

Meaningful device testing needs root or Shizuku. Without either, grant manually:
`adb shell pm grant com.akylas.enforcedoze android.permission.DUMP` and `… WRITE_SECURE_SETTINGS`.
Simulate Doze transitions with `adb shell dumpsys deviceidle get deep` / `force-idle` / `unforce`.

## Exercising the broadcast API

```bash
adb shell am broadcast -a com.akylas.enforcedoze.ENABLE_FORCEDOZE
adb shell am broadcast -a com.akylas.enforcedoze.CHANGE_SETTING --es settingName turnOffWiFiInDoze --es settingValue true
```

## Where to change things

| Task | Start in |
|------|----------|
| Doze entry/exit behaviour | `ForceDozeService.enterDoze`, `exitDoze`, `DozeReceiver` |
| Radio/sensor toggles | `ForceDozeService.actualEnterDozeHandleNetwork`, `leaveDozeHandleNetwork`, `set*State` |
| New setting | see "Adding a setting" in [patterns.md](./patterns.md) |
| Shizuku / root plumbing | `ShizukuHandler.java`, `ForceDozeService.executeCommand*`, `MainActivity.doAfterSuCheckSetup` |
| Schedules | `Utils.applyForceDozeSchedule` and helpers, `CustomDozePeriodReceiver`, `SettingsActivity.showCustomDozePeriodsDialog` |
| Tunables | `DozeTunableHandler`, `DozeTunablesActivity`, `res/xml/prefs_doze_tunables.xml` |
| Strings | `app/src/main/res/values/strings.xml` (translations arrive via Weblate) |
| Release | `fastlane/Fastfile`, `versionCode`/`versionName` in `app/build.gradle` |
