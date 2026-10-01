#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/bulk-e2e-evidence
bulk_key="$(date +%s)_$RANDOM"
app_package=com.homura251.rule34downloader
runner="$app_package.test/$app_package.BulkAndroidJUnitRunner"
app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

collect() {
  adb pull "/sdcard/Download/Rule34 Downloader Test Evidence/bulk-$bulk_key/" app/build/bulk-e2e-evidence/ || true
  adb shell dumpsys webviewupdate > app/build/bulk-e2e-evidence/webview-provider.txt || true
  adb logcat -d -t 4000 > app/build/bulk-e2e-evidence/logcat.txt || true
}
trap collect EXIT

./gradlew --stacktrace -PbulkSiteTest=true assembleDebug assembleDebugAndroidTest
android --no-metrics --sdk="$ANDROID_HOME" install --apks="$app_apk" --install-options=-g
adb install -r "$test_apk"

run_phase() {
  local phase="$1"
  local count="$2"
  adb shell am instrument -w -r \
    -e class "$app_package.BulkDownloadEndToEndTest" \
    -e bulkPhase "$phase" -e bulkCount "$count" -e bulkKey "$bulk_key" \
    "$runner" | tee "app/build/bulk-e2e-evidence/$phase.log"
  python3 - "$phase" <<'PY'
import sys,re
from pathlib import Path
phase=sys.argv[1]
log=Path(f'app/build/bulk-e2e-evidence/{phase}.log').read_text()
if not re.search(r'OK \(1 test\)',log) or 'FAILURES!!!' in log or 'Process crashed' in log:
    raise SystemExit(f'Android phase {phase} failed; inspect its full instrumentation log.')
PY
}

# True process death while an original is partly written. The expected killed
# instrumentation is validated by a separate, successful recovery phase.
adb shell am instrument -w -r \
  -e class "$app_package.BulkDownloadEndToEndTest" \
  -e bulkPhase crash -e bulkCount 84 -e bulkKey "$bulk_key" \
  "$runner" > app/build/bulk-e2e-evidence/crash.log 2>&1 &
crash_process=$!
crash_ready=false
for (( attempt=0; attempt<120; attempt++ )); do
  if ! kill -0 "$crash_process" 2>/dev/null; then
    cat app/build/bulk-e2e-evidence/crash.log
    exit 1
  fi
  if adb shell "test -f '/sdcard/Download/Rule34 Downloader Test Evidence/bulk-$bulk_key/crash-ready.txt'"; then
    crash_ready=true
    break
  fi
  sleep 1
done
if [[ "$crash_ready" != true ]]; then
  cat app/build/bulk-e2e-evidence/crash.log
  exit 1
fi
adb shell am force-stop "$app_package"
wait "$crash_process" || true
run_phase recover 84
run_phase browser 84
run_phase checkpoint 3000

# A paused batch must stay paused after the application process is stopped.
adb shell am force-stop "$app_package"
adb shell am start -W -n "$app_package/.MainActivity"
android --no-metrics --sdk="$ANDROID_HOME" layout --full --output=app/build/bulk-e2e-evidence/paused-after-force-stop.json
android --no-metrics --sdk="$ANDROID_HOME" screen capture --output=app/build/bulk-e2e-evidence/paused-after-force-stop.png
python3 - <<'PY'
import json
from pathlib import Path
layout=json.loads(Path('app/build/bulk-e2e-evidence/paused-after-force-stop.json').read_text())
assert '已暂停' in json.dumps(layout,ensure_ascii=False), 'Paused state did not survive process restart'
PY
run_phase resume 3000

# A real package uninstall clears the database, UID ownership and SAF grants.
adb uninstall "$app_package.test"
adb uninstall "$app_package"
if adb shell pm list packages | rg -q '^package:com\.homura251\.rule34downloader$'; then
  echo 'Application is still installed'
  exit 1
fi
sleep 2
adb shell "ls -1 '/sdcard/Download/Rule34 Downloader/test_bulk_$bulk_key/'" > app/build/bulk-e2e-evidence/retained-old-files.txt
python3 - <<'PY'
from pathlib import Path
files=Path('app/build/bulk-e2e-evidence/retained-old-files.txt').read_text().splitlines()
assert len(files)==2998, f'Expected 2998 retained old files after 2 deliberate deletions, found {len(files)}'
PY
android --no-metrics --sdk="$ANDROID_HOME" install --apks="$app_apk" --install-options=-g
adb install -r "$test_apk"
run_phase restore 3000
collect
printf 'PASS: actual process death; Chromium UI pause/resume; 3000 verified files; real uninstall and SAF reuse\n' > app/build/bulk-e2e-evidence/result.txt
