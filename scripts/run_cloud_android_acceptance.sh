#!/usr/bin/env bash
set -euo pipefail

# Runs only on the isolated CI emulator; all values are synthetic.
apk_dir=android/app/build/outputs/apk
adb install -r "$apk_dir/cloudAcceptance/app-cloudAcceptance.apk"
adb install -r "$apk_dir/androidTest/cloudAcceptance/app-cloudAcceptance-androidTest.apk"
adb reverse tcp:18443 tcp:18443
package=com.blackkcold.simhub.cloudtest
adb shell pm grant "$package" android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant "$package" android.permission.ACCESS_FINE_LOCATION
adb shell pm grant "$package" android.permission.READ_PHONE_STATE
adb shell cmd location set-location-enabled true
adb emu power capacity 65
(
  while true; do
    adb emu geo fix 114.057865 22.543096 >/dev/null
    sleep 5
  done
) &
geo_pid=$!
trap 'kill "$geo_pid" 2>/dev/null || true' EXIT

device_id=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["deviceId"])' "$RUNNER_TEMP/simhub-ci/enrollment.json")
device_token=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["deviceToken"])' "$RUNNER_TEMP/simhub-ci/enrollment.json")
adb shell am instrument -w \
  -e class com.blackkcold.simhub.CloudAcceptanceInstrumentedTest,com.blackkcold.simhub.CommandStatusInstrumentedTest \
  -e ciDeviceId "$device_id" -e ciDeviceToken "$device_token" -e ciAdminToken "$SIMHUB_ADMIN_TOKEN" \
  "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$RUNNER_TEMP/simhub-ci/instrumentation.log"

# Android's instrumentation command may exit 0 on test failure.
python3 - "$RUNNER_TEMP/simhub-ci/instrumentation.log" <<'PY'
import re, sys
text=open(sys.argv[1],encoding='utf-8').read()
if 'FAILURES!!!' in text or not re.search(r'OK \(3 tests?\)',text):
    raise SystemExit('Cloud emulator acceptance did not pass all three tests')
PY
