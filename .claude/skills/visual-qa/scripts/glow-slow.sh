#!/usr/bin/env bash
# Usage: glow-slow.sh <outdir> [frames]
# Captures the launch glow as stills with animations slowed 10x. screenrecord only writes frames
# when the screen changes and a cold start spends ~2 s on the splash, so a recording tends to miss
# a 500 ms alpha ramp; slowed screenshots don't. Restores animator_duration_scale to 1 afterwards.
set -u
OUT=$1; N=${2:-24}; PKG=com.akylas.enforcedoze
mkdir -p "$OUT"
adb shell settings put global animator_duration_scale 10
adb shell am force-stop $PKG; sleep 1
adb shell am start -n "$PKG/.MainActivity" >/dev/null
for i in $(seq -w 1 "$N"); do adb exec-out screencap -p > "$OUT/s_$i.png"; done
adb shell settings put global animator_duration_scale 1
# Warmth of the bottom quarter (mean R-B); the glow peaks there. Avoids the nav-handle rows.
python3 - "$OUT" <<'PY'
import sys, glob
from PIL import Image
for p in sorted(glob.glob(sys.argv[1] + "/s_*.png")):
    im = Image.open(p).convert("RGB"); w, h = im.size
    box = im.crop((0, int(h * .72), w, int(h * .92))).resize((60, 20))
    px = list(getattr(box, "get_flattened_data", box.getdata)()); print(p.rsplit("/", 1)[-1], round(sum(r - b for r, g, b in px) / len(px), 1))
PY
