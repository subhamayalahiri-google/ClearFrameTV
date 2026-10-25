#!/bin/sh
# Runs inside the Android TV 8.0 emulator in CI: installs the APK, makes it Home,
# and saves screenshots of the home screen, the top controls and All apps.
set -x
OUT=screenshots; mkdir -p "$OUT"
adb wait-for-device
adb install -r out/clearframe.apk || exit 1
adb shell cmd package set-home-activity tv.clearframe.home/.HomeActivity
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tee "$OUT/home-resolves-to.txt"
adb shell input keyevent KEYCODE_HOME
sleep 8
adb exec-out screencap -p > "$OUT/1-home.png"
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
adb exec-out screencap -p > "$OUT/2-dock-focus-moved.png"
adb shell input keyevent KEYCODE_DPAD_UP; adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
adb exec-out screencap -p > "$OUT/3-controls.png"
adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 2
adb exec-out screencap -p > "$OUT/4-all-apps.png"
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_DPAD_LEFT; adb shell input keyevent KEYCODE_DPAD_CENTER; sleep 3
adb exec-out screencap -p > "$OUT/5-next-wallpaper.png"
adb logcat -d -s AndroidRuntime:E ActivityManager:W > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && { echo "App crashed"; exit 1; }
exit 0
