#!/bin/sh
# Scroll benchmark for a long poster grid (Categories → first category). Run after bench-home.sh installed the APK.
set -e
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
PKG=com.seenama.tv
key() { adb shell input keyevent "$@"; }
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 10
key KEYCODE_BACK                                   # focus the rail (on Home)
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER   # Categories
sleep 4
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER           # first category card
sleep 6
for pass in warm measure; do
  [ $pass = measure ] && adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in $(seq 1 12); do key KEYCODE_DPAD_DOWN; done
  key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
  for i in $(seq 1 12); do key KEYCODE_DPAD_UP; done
  sleep 2
done
adb shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|Number Slow UI thread|Number Slow bitmap"
