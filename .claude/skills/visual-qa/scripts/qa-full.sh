#!/usr/bin/env bash
# Usage: qa-full.sh <apk> <outdir>   (emulator already booted by emu-boot.sh)
# Full "after" set on the single running emulator: every activity at font scale 1.0/1.3/2.0,
# RTL samples, the four accent phases on Main, and launch-glow frames with animations on and off.
set -u
APK=$1; OUT=$2
HERE=$(cd "$(dirname "$0")" && pwd)
PKG=com.akylas.enforcedoze
mkdir -p "$OUT"/{rtl,accent,glow,glow-off}

bash "$HERE/qa-shots.sh" "$APK" "$OUT" 1.0 1.3 2.0

shot() { adb shell am force-stop $PKG; adb shell am start -W -n "$PKG/.$1" >/dev/null 2>&1; sleep 3; adb exec-out screencap -p > "$2"; }

# RTL: an RTL app locale (API 33+); debug.force_rtl only applies after a configuration change
adb shell cmd locale set-app-locales $PKG --locales ar
for a in MainActivity ui.DozeMonitorActivity SettingsActivity WhitelistAppsActivity DozeBatteryStatsActivity AboutAppActivity; do shot "$a" "$OUT/rtl/${a##*.}.png"; done
adb shell cmd locale set-app-locales $PKG --locales en-US

# Accent phases (needs root for date)
adb shell settings put global auto_time 0
for p in "morning 0601" "day 1200" "evening 1900" "night 2330"; do
  set -- $p
  adb shell date "$(date +%m%d)$2$(date +%Y).00" >/dev/null
  shot MainActivity "$OUT/accent/$1_MainActivity.png"
  shot ui.DozeMonitorActivity "$OUT/accent/$1_DozeMonitorActivity.png"
done
adb shell settings put global auto_time 1

# Launch glow, animations on: stills at 10x animator scale (screenrecord drops unchanged frames)
bash "$HERE/glow-slow.sh" "$OUT/glow"

# Animations off: glow must not play
for k in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $k 0; done
adb shell am force-stop $PKG; adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 0.3
adb exec-out screencap -p > "$OUT/glow-off/t0300ms.png"; sleep 1
adb exec-out screencap -p > "$OUT/glow-off/t1300ms.png"
for k in animator_duration_scale transition_animation_scale window_animation_scale; do adb shell settings put global $k 1; done
find "$OUT" -name '*.png' | wc -l
