#!/usr/bin/env bash
# Usage: seed-stats.sh
# The stats screens are empty on a fresh emulator. Appends 8 synthetic Doze sessions over the last
# 16 hours to the app's default prefs (dozeUsageDataAdvanced) so DozeStatsActivity and
# DozeBatteryStatsActivity render rows. Needs adb root and the app run once (prefs file present).
# Synthetic data: battery levels and durations are made up, so judge layout, never the numbers.
set -eu
PKG=com.akylas.enforcedoze
F=/data/data/$PKG/shared_prefs/${PKG}_preferences.xml
TMP=$(mktemp -d)
adb shell am force-stop $PKG
adb shell cat "$F" > "$TMP/prefs.xml"
grep -q dozeUsageDataAdvanced "$TMP/prefs.xml" && { echo "already seeded"; exit 0; }
python3 - "$TMP/prefs.xml" <<'PY'
import sys, time
p = sys.argv[1]; s = open(p).read()
now = int(time.time() * 1000); H = 3600000
rows = []; lvl = 88
for i in range(8):
    t = now - (16 - 2 * i) * H
    rows.append(f"{t},{lvl},ENTER")
    rows.append(f"{t+50*60000},{lvl-1},EXIT_MAINTENANCE" if i % 2 else f"{t+90*60000},{lvl-1},EXIT")
    lvl -= 2
xml = '<set name="dozeUsageDataAdvanced">' + ''.join(f'<string>{r}</string>' for r in rows) + '</set>'
open(p, 'w').write(s.replace('</map>', xml + '\n</map>'))
PY
adb push "$TMP/prefs.xml" /data/local/tmp/p.xml >/dev/null
adb shell "cp /data/local/tmp/p.xml $F && chown \$(stat -c %u:%g /data/data/$PKG) $F && chmod 660 $F"
rm -rf "$TMP"; echo seeded
