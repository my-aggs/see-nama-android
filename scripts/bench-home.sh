#!/bin/sh
# Scroll benchmark for the Home screen on the emulator. Prints Compose frame stats from gfxinfo.
# Usage: scripts/bench-home.sh [apk]   (defaults to the release APK)
set -e
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
APK=${1:-app/build/outputs/apk/release/app-release.apk}
PKG=com.seenama.tv
adb install -r "$APK" >/dev/null
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity | grep TotalTime
sleep 12
key() { adb shell input keyevent "$@"; }
# Warm-up pass so image caches are filled, then the measured pass.
for pass in warm measure; do
  [ $pass = measure ] && adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for row in 1 2 3 4 5 6; do
    key KEYCODE_DPAD_DOWN
    key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
  done
  for row in 1 2 3 4 5 6; do key KEYCODE_DPAD_UP; done
  key KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT
  sleep 2
done
adb shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Slow UI thread|Number Slow bitmap|Number Slow issue draw|Number Frame deadline missed" | head -12
