#!/usr/bin/env bash
# Usage: tap.sh <text-or-content-desc regex>  — taps the centre of the first matching node on screen.
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml | python3 -c '
import sys,re,xml.etree.ElementTree as E
pat=re.compile(sys.argv[1])
for n in E.parse(sys.stdin).iter("node"):
    if pat.search(n.get("text","")) or pat.search(n.get("content-desc","")):
        x1,y1,x2,y2=map(int,re.findall(r"\d+",n.get("bounds")));print((x1+x2)//2,(y1+y2)//2);break
' "$1" | { read x y && [ -n "$x" ] && adb shell input tap $x $y && echo "tapped $1"; } || echo "MISS $1"
