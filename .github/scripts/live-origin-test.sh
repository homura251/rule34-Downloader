#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/live-e2e-evidence
status=0
./gradlew --stacktrace -PliveSiteTest=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.homura251.rule34downloader.LiveArtistPauseResumeTest,com.homura251.rule34downloader.LiveSiteEndToEndTest \
  connectedDebugAndroidTest || status=$?
adb pull "/sdcard/Download/Rule34 Downloader Test Evidence/live/" app/build/live-e2e-evidence/ || true
adb shell dumpsys webviewupdate > app/build/live-e2e-evidence/webview-provider.txt
echo "$status" > app/build/live-e2e-evidence/exit-code.txt
exit "$status"
