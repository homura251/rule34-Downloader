#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/live-e2e-evidence
status=0
app_package=com.homura251.rule34downloader
collect() {
  adb pull "/sdcard/Download/Rule34 Downloader Test Evidence/live/" app/build/live-e2e-evidence/ || true
  adb shell dumpsys webviewupdate > app/build/live-e2e-evidence/webview-provider.txt || true
  printf '%s\n' "$status" > app/build/live-e2e-evidence/exit-code.txt
}
trap collect EXIT

./gradlew --stacktrace -PliveSiteTest=true assembleDebug assembleDebugAndroidTest
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Run classes individually. Verify the expected number of executed tests so a
# runner/filter configuration cannot turn omitted live tests into a green job.
run_class() {
  local name="$1"
  local expected="$2"
  adb shell am instrument -w -r -e class "$app_package.$name" \
    "$app_package.test/androidx.test.runner.AndroidJUnitRunner" \
    | tee "app/build/live-e2e-evidence/$name.log"
  python3 - "$name" "$expected" <<'PY'
import sys,re
from pathlib import Path
name,expected=sys.argv[1],int(sys.argv[2])
log=Path(f'app/build/live-e2e-evidence/{name}.log').read_text()
if not re.search(rf'OK \({expected} tests?\)',log) or 'FAILURES!!!' in log or 'Process crashed' in log:
    raise SystemExit(f'Actual origin {name} failed or did not execute all {expected} tests.')
PY
}
run_class LiveArtistPauseResumeTest 1 || status=1
run_class LiveSiteEndToEndTest 2 || status=1
exit "$status"
