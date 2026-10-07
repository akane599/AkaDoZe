#!/usr/bin/env bash
# Usage: qa-shots.sh <apk> <outdir> [fontScales...]
# Installs the APK on the single running emulator, grants DUMP so screens have content,
# seeds stats data, then screenshots every activity (as root, so non-exported ones start) at each font scale.
set -u
APK=$1; OUT=$2; shift 2
SCALES=${*:-"1.0 1.3"}
PKG=com.akylas.enforcedoze
ACTS="MainActivity ui.DozeMonitorActivity SettingsActivity DozeTunablesActivity WhitelistAppsActivity BlockAppsActivity BlockNotificationsActivity DozeBatteryStatsActivity DozeStatsActivity LogActivity TaskerBroadcastsActivity PackageChooserActivity AboutAppActivity"
mkdir -p "$OUT"
adb root >/dev/null 2>&1; adb wait-for-device
adb install -r -g "$APK" >/dev/null || { echo "install failed"; exit 1; }
adb shell pm grant $PKG android.permission.DUMP 2>/dev/null
# First run creates the prefs file; then seed synthetic Doze sessions so the stats screens have rows
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 3
bash "$(dirname "$0")/seed-stats.sh" || echo "stats not seeded"
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
for s in $SCALES; do
  adb shell settings put system font_scale "$s"
  for a in $ACTS; do
    adb shell am force-stop $PKG
    adb shell am start -W -n "$PKG/.$a" >/dev/null 2>&1
    sleep 3
    # Dismiss nothing: first-run dialogs are part of what we review.
    adb exec-out screencap -p > "$OUT/${a##*.}_fs${s}.png"
  done
done
adb shell settings put system font_scale 1.0
ls "$OUT" | wc -l
