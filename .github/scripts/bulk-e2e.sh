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
  adb shell dumpsys connectivity > app/build/bulk-e2e-evidence/connectivity.txt || true
  adb shell dumpsys jobscheduler > app/build/bulk-e2e-evidence/jobscheduler.txt || true
  adb logcat -d -t 4000 > app/build/bulk-e2e-evidence/logcat.txt || true
}
trap collect EXIT

./gradlew --stacktrace -PbulkSiteTest=true assembleDebug assembleDebugAndroidTest
adb install -r -g "$app_apk"
adb install -r "$test_apk"

run_phase() {
  local phase="$1"
  local count="$2"
  bash .github/scripts/prepare-emulator.sh app/build/bulk-e2e-evidence
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
bash .github/scripts/prepare-emulator.sh app/build/bulk-e2e-evidence
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
run_phase api 84
run_phase checkpoint 3000

# A paused batch must stay paused after the application process is stopped.
adb shell am force-stop "$app_package"
adb shell am start -W -n "$app_package/.MainActivity"
adb exec-out screencap -p > app/build/bulk-e2e-evidence/paused-after-force-stop.png
# The resume instrumentation asserts the persisted PAUSED state and the two
# completed URIs after this real process restart. Android CLI accessibility
# sessions run after the last instrumentation to avoid simultaneous registration.
run_phase resume 3000

# A real package uninstall clears the database, UID ownership and SAF grants.
adb uninstall "$app_package.test"
adb uninstall "$app_package"
adb shell pm list packages > app/build/bulk-e2e-evidence/packages-after-uninstall.txt
python3 - <<'PY_UNINSTALL'
from pathlib import Path
packages = Path('app/build/bulk-e2e-evidence/packages-after-uninstall.txt').read_text().splitlines()
assert 'package:com.homura251.rule34downloader' not in packages, 'Application is still installed'
PY_UNINSTALL
sleep 2
adb shell "ls -1 '/sdcard/Download/Rule34 Downloader/test_bulk_$bulk_key/'" > app/build/bulk-e2e-evidence/retained-old-files.txt
python3 - <<'PY'
from pathlib import Path
files=Path('app/build/bulk-e2e-evidence/retained-old-files.txt').read_text().splitlines()
assert len(files)==2998, f'Expected 2998 retained old files after 2 deliberate deletions, found {len(files)}'
PY
adb install -r -g "$app_apk"
adb install -r "$test_apk"
run_phase restore 3000
adb shell am start -W -n "$app_package/.MainActivity"
adb shell uiautomator dump /sdcard/rule34-layout.xml
adb pull /sdcard/rule34-layout.xml app/build/bulk-e2e-evidence/complete-after-reinstall.xml
adb exec-out screencap -p > app/build/bulk-e2e-evidence/complete-after-reinstall.png
python3 - "$bulk_key" <<'PY_CHECK'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse('app/build/bulk-e2e-evidence/complete-after-reinstall.xml').getroot()
texts = {node.get('text', '') for node in root.iter('node')}
assert {'作者', '全部作品', '下载'} <= texts, 'The three main navigation entries are missing'
authors = {f'test_bulk_{sys.argv[1]}', f'test_bulk_api_{sys.argv[1]}', f'test_bulk_crash_{sys.argv[1]}'}
assert authors & texts, 'No restored author album is visible'
assert any(re.fullmatch(r'(84|3000) 件作品 · \1 已下载', text) for text in texts), 'A completed author album is missing'
PY_CHECK
collect
printf 'PASS: actual process death; API UI pause/resume; 3000 verified files; real uninstall and SAF reuse\n' > app/build/bulk-e2e-evidence/result.txt
