#!/usr/bin/env bash
set -euo pipefail

evidence="$1"
mkdir -p "$evidence"
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 2147483647
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
# The stock image can leave a Pixel Launcher ANR dialog over the tested app
# after its first boot. Reset only that unrelated process; app failures and
# permission dialogs remain visible and fail the instrumentation normally.
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell dumpsys activity activities > "$evidence/emulator-activities.txt"
adb shell dumpsys connectivity > "$evidence/emulator-connectivity.txt"
