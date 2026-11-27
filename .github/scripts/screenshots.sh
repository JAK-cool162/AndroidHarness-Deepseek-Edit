#!/usr/bin/env bash
# Installs the branch APK on the emulator and captures the first screens in light, dark and landscape.
set -u
pkg=com.androidharness.app.deepseek
out=screenshots
mkdir -p "$out"
adb install -r -g AndroidHarness-DeepSeek-cache.apk
shot() { sleep 6; adb exec-out screencap -p > "$out/$1.png"; echo "captured $1"; }
launch() { adb shell am force-stop "$pkg"; adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null; }
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell cmd uimode night no
launch; sleep 20; shot 1-light-start
adb shell input keyevent KEYCODE_BACK; shot 2-light-after-back
adb shell cmd uimode night yes
launch; sleep 10; shot 3-dark-start
adb shell settings put system user_rotation 1
shot 4-dark-landscape
adb shell cmd uimode night no
shot 5-light-landscape
adb logcat -d -t 300 | grep -iE "FATAL|AndroidRuntime" > "$out/crashes.txt" || true
