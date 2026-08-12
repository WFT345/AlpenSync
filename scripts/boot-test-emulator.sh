#!/usr/bin/env bash
# AlpenSync live-test helper (M1/M2 acceptance runs, plan §7).
# Boots the emulator with a window (default) or headless (--headless),
# waits for full boot, and installs the debug APK. Run from the repo root:
#   bash scripts/boot-test-emulator.sh [--headless]
set -euo pipefail

AVD="Medium_Phone_API_36.1"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
EMU_LOG="${TMPDIR:-/tmp}/alpensync-emulator.log"

EMULATOR_FLAGS=(-no-boot-anim)
if [[ "${1:-}" == "--headless" ]]; then
  EMULATOR_FLAGS+=(-no-window -no-audio)
fi

if [[ ! -f "$APK" ]]; then
  echo "Debug APK not found at $APK — build it first: ./gradlew assembleDebug" >&2
  exit 1
fi

echo "Starting emulator $AVD (log: $EMU_LOG) ..."
# Redirect output so the emulator never holds this script's stdout open.
nohup "$SDK/emulator/emulator" -avd "$AVD" "${EMULATOR_FLAGS[@]}" >"$EMU_LOG" 2>&1 &

echo "Waiting for device ..."
"$ADB" wait-for-device

echo "Waiting for boot to complete ..."
until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  sleep 5
done

"$ADB" install -r "$APK"
echo "Installed $APK on $AVD."
echo "The emulator stays running; close its window or run: adb emu kill"
