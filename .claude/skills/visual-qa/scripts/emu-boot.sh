#!/usr/bin/env bash
# Usage: emu-boot.sh [avd] [--force]   (default avd: pixel)
# Boots one headless emulator only when the machine can hold it, then readies it for screenshots.
# qemu grows to ~4.5 GB RSS even with -memory 2048 and was OOM-killed beside running Gradle builds,
# so this refuses while a Gradle or Kotlin daemon is alive or less than 6 GB is available.
set -u
AVD=${1:-pixel}; FORCE=${2:-}
if adb devices | grep -q 'emulator-.*device$'; then echo "emulator already running; reuse it"; exit 0; fi
if [ "$FORCE" != "--force" ]; then
  daemons=0   # java processes only: a shell whose command line mentions the names must not count
  for pid in $(pgrep -x java); do grep -qaE 'GradleDaemon|KotlinCompileDaemon' /proc/$pid/cmdline 2>/dev/null && daemons=$((daemons+1)); done
  avail=$(awk '/MemAvailable/ {print int($2/1048576)}' /proc/meminfo)
  if [ "$daemons" -gt 0 ]; then echo "REFUSED: $daemons Gradle/Kotlin daemon(s) alive. Wait for builds to drain, then ./gradlew --stop; pkill -f KotlinCompileDaemon"; exit 3; fi
  if [ "$avail" -lt 6 ]; then echo "REFUSED: only ${avail} GB available (need 6)"; exit 3; fi
fi
LOG=${TMPDIR:-/tmp}/emu-$AVD.log
(emulator -avd "$AVD" -no-window -no-audio -memory 2048 -no-snapshot-save > "$LOG" 2>&1 &)
timeout 300 adb wait-for-device shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done' || { echo "boot timed out; see $LOG"; exit 1; }
adb root >/dev/null 2>&1; adb wait-for-device
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
# a killed run can leave its font scale or animation scales behind; start every shoot from defaults
adb shell settings put system font_scale 1.0; for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $k 1; done
echo "booted $AVD (log $LOG)"
