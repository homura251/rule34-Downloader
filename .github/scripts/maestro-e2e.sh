#!/usr/bin/env bash
set -euo pipefail

evidence=app/build/maestro-evidence
package=com.homura251.rule34downloader.e2e
mkdir -p "$evidence"
export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Use a disposable Android emulator for this fixture suite'; exit 1; }
[[ -z "${RULE34_API_KEY:-}" ]] || { echo 'Run the Maestro fixture suite without production credentials'; exit 1; }
./gradlew --stacktrace :app:assembleE2e
bash .github/scripts/prepare-emulator.sh "$evidence"

python3 .github/scripts/maestro-origin.py --seed-dir "$evidence/seed" > "$evidence/origin.log" 2>&1 &
origin_pid=$!
collect() {
  curl --fail --silent http://127.0.0.1:18734/__state > "$evidence/final-http-state.json" || true
  adb logcat -d -t 4000 > "$evidence/logcat.txt" || true
  adb exec-out screencap -p > "$evidence/final-screen.png" || true
  adb shell dumpsys jobscheduler > "$evidence/jobscheduler.txt" || true
  adb shell dumpsys connectivity > "$evidence/connectivity.txt" || true
  adb shell svc wifi enable || true
  adb shell svc data enable || true
  kill "$origin_pid" 2>/dev/null || true
}
trap collect EXIT
for ((attempt=0; attempt<50; attempt++)); do
  if curl --fail --silent http://127.0.0.1:18734/__state > /dev/null; then break; fi
  sleep 0.2
done
adb reverse tcp:18734 tcp:18734
adb shell "mkdir -p '/sdcard/Download/Rule34 Downloader/'"
adb push "$evidence/seed/maestro_restore" '/sdcard/Download/Rule34 Downloader/'
adb install -r app/build/outputs/apk/e2e/app-e2e.apk
maestro --version > "$evidence/maestro-version.txt"
maestro test --format junit --output "$evidence/results.xml" \
  --test-output-dir "$evidence" --debug-output "$evidence/debug" .maestro

run_network_phase() {
  local phase="$1"
  maestro test --format junit --output "$evidence/$phase.xml" \
    --test-output-dir "$evidence/$phase" --debug-output "$evidence/debug-$phase" \
    ".maestro/helpers/$phase.yaml"
}
# No files are seeded in these author directories: recovery must use their names.
adb shell "mkdir -p '/sdcard/Download/Rule34 Downloader/empty_recovery_case/maestro_empty_one' '/sdcard/Download/Rule34 Downloader/empty_recovery_case/maestro empty two/images'"
run_network_phase empty-authors
run_network_phase network-prepare
adb shell svc wifi disable
adb shell svc data disable
adb shell dumpsys connectivity > "$evidence/offline-connectivity.txt"
run_network_phase network-queued
adb shell svc wifi enable
adb shell svc data enable
run_network_phase network-resumed
python3 .github/scripts/verify-maestro-files.py "$evidence"
