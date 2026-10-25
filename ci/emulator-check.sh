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

# Clean up TV inside the emulator. Its adbd has no TCP port, so this shows the "can't reach" screen.
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
grep -h "SELFTEST" "$OUT"/8-selftest.txt | head -1 | tee "$OUT/selftest-result.txt"

# AdbClient against the emulator's real adbd, from this machine (the app can't reach it from
# inside the emulator: there adbd uses a pipe, not TCP 5555).
HC=$(mktemp -d)
javac -nowarn -d "$HC" ci/stubs/android/content/Context.java ci/stubs/android/util/Base64.java \
  app/src/tv/clearframe/home/AdbClient.java ci/AdbClientHostTest.java 2>&1 | grep -v JAVA_TOOL_OPTIONS
adb logcat -d -s AndroidRuntime:E > "$OUT/crashes.txt"
adb kill-server; sleep 2
timeout 60 java -cp "$HC" tv.clearframe.home.AdbClientHostTest host "$HOME/.android/adbkey" > "$OUT/adbclient-host.txt" 2>&1
# A key the emulator hasn't seen should make adbd show "Allow USB debugging?".
timeout 90 java -cp "$HC" tv.clearframe.home.AdbClientHostTest newkey "$HC/newkey" > "$OUT/adbclient-newkey.txt" 2>&1 &
sleep 12
adb start-server; adb wait-for-device
shot 10-allow-prompt 2
grep -q ADB_CLIENT_OK "$OUT/adbclient-host.txt" || fail "AdbClient could not run commands on the emulator's adbd: $(tail -3 "$OUT/adbclient-host.txt")"
grep -q FATAL "$OUT/crashes.txt" && fail "App crashed"
echo "CHECK PASSED" | tee -a "$OUT/result.txt"
exit 0
