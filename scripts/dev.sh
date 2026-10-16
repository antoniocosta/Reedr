#!/usr/bin/env bash
# Dev helper. Uses the SDK/JDK/emulator from android-dev-toolkit
# (default ../android-dev-toolkit; override with TOOLKIT=/path).
#
#   scripts/dev.sh build        assembleDebug
#   scripts/dev.sh install      installDebug on the emulator (emulator-5554)
#   scripts/dev.sh run          install + launch the app
#   scripts/dev.sh test         JVM unit tests
#   scripts/dev.sh phone        release build (R8) installed on the USB phone for user 0
#   scripts/dev.sh emu          release build (R8) installed on the emulator
#   scripts/dev.sh log          logcat for the app only
#   scripts/dev.sh share URL    share a link to Reedr the way another app would
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLKIT="${TOOLKIT:-$(cd .. && pwd)/android-dev-toolkit}"
[ -f "$TOOLKIT/setup/env.sh" ] || TOOLKIT="$(cd ../.. && pwd)"     # Reedr checked out inside the toolkit (work/Reedr)
[ -f "$TOOLKIT/setup/env.sh" ] || { echo "android-dev-toolkit not found at $TOOLKIT (set TOOLKIT=)" >&2; exit 1; }
. "$TOOLKIT/setup/env.sh"

# Point Gradle at the toolkit SDK (local.properties is gitignored)
grep -qs "^sdk.dir=$ANDROID_SDK_ROOT\$" local.properties || echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
export ANDROID_SERIAL="$EMU_SERIAL"
PKG=net.uncorp.reedr
# one Gradle build at a time on this machine (the toolkit's lock), when it is there
gradle() { if [ -x "$TOOLKIT/tools/glock.sh" ]; then "$TOOLKIT/tools/glock.sh" gradle ./gradlew "$@"; else ./gradlew "$@"; fi; }
RELEASE=app/build/outputs/apk/release/app-release.apk

case "${1:-build}" in
  build)   gradle assembleDebug ;;
  install) gradle installDebug ;;
  run)     gradle installDebug && emu_adb shell am start -n "$PKG/.ui.MainActivity" ;;
  test)    gradle testDebugUnitTest ;;
  phone)   gradle assembleRelease
           phone=$(adb devices | awk 'NR>1 && $2=="device" && $1!~/^emulator-/ {print $1; exit}')
           [ -n "$phone" ] || { echo "no USB phone found (adb devices)" >&2; exit 1; }
           adb -s "$phone" install -r --user 0 "$RELEASE" ;;
  emu)     gradle assembleRelease && emu_adb install -r "$RELEASE" && emu_adb shell am start -n "$PKG/.ui.MainActivity" ;;
  log)     emu_adb logcat --pid="$(emu_adb shell pidof -s $PKG | tr -d '\r')" ;;
  share)   emu_adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'${2:?URL}'" -n "$PKG/.ui.SaveActivity" ;;
  *)       sed -n '2,13p' "$0"; exit 1 ;;
esac
