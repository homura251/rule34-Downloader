#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/e2e-evidence
status=0
./gradlew --stacktrace assembleDebug assembleDebugAndroidTest
bash .github/scripts/prepare-emulator.sh app/build/e2e-evidence
./gradlew --stacktrace connectedDebugAndroidTest || status=$?
adb pull "/sdcard/Download/Rule34 Downloader Test Evidence/controlled/" app/build/e2e-evidence/ || true
if (( status != 0 )); then
  adb logcat -d -t 1000 > app/build/e2e-evidence/logcat.txt
  exit "$status"
fi

adb shell getprop > app/build/e2e-evidence/device-info.txt
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.homura251.rule34downloader/.MainActivity
adb exec-out screencap -p > app/build/e2e-evidence/screen.png
adb shell uiautomator dump /sdcard/rule34-layout.xml
adb pull /sdcard/rule34-layout.xml app/build/e2e-evidence/layout.xml
