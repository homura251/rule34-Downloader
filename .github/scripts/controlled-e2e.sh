#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/e2e-evidence
status=0
./gradlew --stacktrace assembleDebug assembleDebugAndroidTest
bash .github/scripts/prepare-emulator.sh app/build/e2e-evidence
./gradlew --stacktrace connectedDebugAndroidTest || status=$?
adb shell dumpsys webviewupdate > app/build/e2e-evidence/webview-provider.txt
adb pull "/sdcard/Download/Rule34 Downloader Test Evidence/controlled/" app/build/e2e-evidence/ || true
if (( status != 0 )); then
  adb logcat -d -t 1000 > app/build/e2e-evidence/logcat.txt
  exit "$status"
fi

android --no-metrics --sdk="$ANDROID_HOME" info > app/build/e2e-evidence/android-info.txt
android --no-metrics --sdk="$ANDROID_HOME" install --apks=app/build/outputs/apk/debug/app-debug.apk --install-options=-g
adb shell am start -W -n com.homura251.rule34downloader/.MainActivity
android --no-metrics --sdk="$ANDROID_HOME" screen capture --output=app/build/e2e-evidence/cli-screen.png
android --no-metrics --sdk="$ANDROID_HOME" layout --output=app/build/e2e-evidence/cli-layout.json
