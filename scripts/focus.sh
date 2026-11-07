#!/bin/sh
# Prints the element that currently has focus on the emulator (text of the focused node or its children + bounds).
# Usage: scripts/focus.sh [KEY ...]   e.g. scripts/focus.sh DPAD_DOWN DPAD_LEFT
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
for k in "$@"; do adb shell input keyevent "KEYCODE_$k"; sleep 0.7; done
adb shell uiautomator dump /sdcard/f.xml >/dev/null 2>&1
adb shell cat /sdcard/f.xml | python3 -c '
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
def texts(n):
    out = [n.get("text") or n.get("content-desc") or ""]
    for c in n: out += texts(c)
    return [t for t in out if t]
found = False
for n in root.iter("node"):
    if n.get("focused") == "true":
        found = True
        print("FOCUS:", " | ".join(texts(n))[:120] or "(no text)", n.get("bounds"))
if not found: print("FOCUS: none reported")
'
