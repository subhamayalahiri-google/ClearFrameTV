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

# Built-in ADB client: try as is, then with adbd listening on TCP 5555 (as on a TV).
adb logcat -c
adb shell am start -n tv.clearframe.home/.HomeActivity --ez selftest true
sleep 20
shot 7-cleanup-selftest 0
adb logcat -d -s ClearFrame:* > "$OUT/selftest-1.txt"
if ! grep -q "SELFTEST ok" "$OUT/selftest-1.txt"; then
  adb tcpip 5555; sleep 5; adb wait-for-device
  adb shell getprop service.adb.tcp.port > "$OUT/tcp-port.txt"
  adb logcat -c
  adb shell am force-stop tv.clearframe.home
  adb shell am start -n tv.clearframe.home/.HomeActivity --ez selftest true
  sleep 10; shot 8-allow-prompt 0
  # Accept "Allow USB debugging?" if it shows: tick Always allow, then OK.
  adb shell input keyevent KEYCODE_DPAD_DOWN; adb shell input keyevent KEYCODE_DPAD_CENTER
  adb shell input keyevent KEYCODE_DPAD_DOWN; adb shell input keyevent KEYCODE_DPAD_RIGHT; adb shell input keyevent KEYCODE_DPAD_CENTER
  sleep 20; shot 9-cleanup-selftest-tcp 0
  adb logcat -d -s ClearFrame:* > "$OUT/selftest-2.txt"
fi
grep -h "SELFTEST" "$OUT"/selftest-*.txt | tee "$OUT/selftest-result.txt"

adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt"
grep -q "FATAL EXCEPTION" "$OUT/crashes.txt" && fail "App crashed"
echo "CHECK PASSED" | tee -a "$OUT/result.txt"
exit 0
