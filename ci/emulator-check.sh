#!/bin/sh
# Runs against the Android TV 8.0 emulator in CI. Installs the APK, does what Clean up TV does
# to take over Home (turn the stock launcher off), checks ClearFrame is really on screen, saves
# screenshots, and tests the built-in ADB client against the emulator's own adbd.
set -x
OUT=screenshots; mkdir -p "$OUT"
fail() { echo "CHECK FAILED: $*" | tee -a "$OUT/result.txt"; exit 1; }
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; }
front() { adb shell dumpsys window windows | grep -m1 mCurrentFocus | tr -d '\r'; }

adb wait-for-device
adb install -r out/clearframe.apk || fail "install"
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/home-candidates.txt"
adb shell pm disable-user --user 0 com.google.android.tvlauncher
adb shell pm disable-user --user 0 com.google.android.leanbacklauncher
adb shell cmd package set-home-activity tv.clearframe.home/.HomeActivity
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/home-resolves-to.txt"
adb shell input keyevent KEYCODE_HOME
shot 1-home 8
front | tee "$OUT/front-home.txt" | grep -q tv.clearframe.home || fail "Home did not open ClearFrame: $(front)"

adb shell input keyevent KEYCODE_DPAD_RIGHT; shot 2-dock-focus-moved 1
adb shell input keyevent KEYCODE_DPAD_UP; adb shell input keyevent KEYCODE_DPAD_RIGHT; shot 3-controls 1
adb shell input keyevent KEYCODE_DPAD_CENTER; shot 4-all-apps 2
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_DPAD_RIGHT; adb shell input keyevent KEYCODE_DPAD_CENTER; shot 5-settings-menu 1
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_DPAD_LEFT; adb shell input keyevent KEYCODE_DPAD_LEFT; adb shell input keyevent KEYCODE_DPAD_CENTER; shot 6-next-wallpaper 3
front | grep -q tv.clearframe.home || fail "Left ClearFrame during navigation: $(front)"

# Dock tiles: put two apps the emulator has on the dock.
adb shell am start -n tv.clearframe.home/.HomeActivity --es apps "com.google.android.videos,com.android.tv.settings,com.google.android.youtube.tv"
adb shell input keyevent KEYCODE_DPAD_DOWN
shot 7-dock-with-apps 3

# Built-in ADB client against the emulator's own adbd, as Clean up TV does on the TV.
{
  echo "ro.adb.secure=$(adb shell getprop ro.adb.secure | tr -d '\r')"
  echo "service.adb.tcp.port=$(adb shell getprop service.adb.tcp.port | tr -d '\r')"
  echo "listening on 5555: $(adb shell cat /proc/net/tcp /proc/net/tcp6 | grep -ci ':15B3 .* 0A ')"
} > "$OUT/adbd.txt"
selftest() {
  adb logcat -c
  adb shell am start -n tv.clearframe.home/.HomeActivity --ez selftest true
  sleep 25
  shot "$1" 0
  adb logcat -d -s ClearFrame:* > "$OUT/$1.txt"
}
selftest 8-selftest
if ! grep -q "SELFTEST ok" "$OUT/8-selftest.txt"; then
  adb shell input keyevent KEYCODE_BACK
  adb tcpip 5555; sleep 10; adb wait-for-device; sleep 5
  echo "after tcpip, listening on 5555: $(adb shell cat /proc/net/tcp /proc/net/tcp6 | grep -ci ':15B3 .* 0A ')" >> "$OUT/adbd.txt"
  selftest 9-selftest-tcp
fi
grep -h "SELFTEST" "$OUT"/8-selftest.txt "$OUT"/9-selftest-tcp.txt 2>/dev/null | tee "$OUT/selftest-result.txt"

adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt"
grep -q "FATAL EXCEPTION" "$OUT/crashes.txt" && fail "App crashed"
echo "CHECK PASSED" | tee -a "$OUT/result.txt"
exit 0
