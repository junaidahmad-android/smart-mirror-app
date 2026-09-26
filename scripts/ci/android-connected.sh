#!/usr/bin/env bash
# CI job `android-instrumented`: runs :androidApp:connectedDebugAndroidTest (Android Test
# Orchestrator + clearPackageData, see androidApp/build.gradle.kts) on an attached device/emulator.
#
# Usage:
#   scripts/ci/android-connected.sh                      # use the one online device (ANDROID_SERIAL wins)
#   scripts/ci/android-connected.sh --avd Medium_Phone   # cold-boot this AVD headless, test, shut it down
#   RUNS=3 scripts/ci/android-connected.sh --avd Medium_Phone   # N consecutive runs (flakiness check)
# Env: RUNS (default 1), TEST_TIMEOUT_SECS (default 1800 per run), BOOT_TIMEOUT_SECS (default 300),
#      EMULATOR_MEMORY_MB (default 3072).
source "$(dirname "$0")/lib.sh"

avd=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd) avd="$2"; shift 2 ;;
    *) die "unknown argument $1" ;;
  esac
done
SDK="$(android_sdk)"
ADB="$SDK/platform-tools/adb"
RUNS="${RUNS:-1}"
started_emulator=""

cleanup() {
  if [[ -n "$started_emulator" ]]; then
    log "Shutting down $started_emulator"
    with_timeout 30 "$ADB" -s "$started_emulator" emu kill >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

online_devices() { with_timeout 15 "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'; }

if [[ -n "$avd" ]]; then
  port=5554
  while online_devices | grep -qx "emulator-$port"; do port=$((port + 2)); done
  log "Cold-booting AVD $avd headless on port $port"
  "$SDK/emulator/emulator" -avd "$avd" -port "$port" -no-window -no-snapshot -no-audio -no-boot-anim \
    -memory "${EMULATOR_MEMORY_MB:-3072}" >"${TMPDIR:-/tmp}/emulator-$port.log" 2>&1 &
  started_emulator="emulator-$port"
  export ANDROID_SERIAL="$started_emulator"
  deadline=$((SECONDS + ${BOOT_TIMEOUT_SECS:-300}))
  until [[ "$(with_timeout 10 "$ADB" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    (( SECONDS < deadline )) || die "Emulator did not boot within ${BOOT_TIMEOUT_SECS:-300}s"
    sleep 3
  done
fi

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  devices="$(online_devices)"
  [[ "$(echo "$devices" | grep -c .)" == "1" ]] || die "Need exactly one online device (or set ANDROID_SERIAL). adb devices: $(echo $devices)"
  export ANDROID_SERIAL="$devices"
fi
log "Device $ANDROID_SERIAL: API $(with_timeout 10 "$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"

# Deterministic device state: no animations, screen awake and unlocked, portrait.
with_timeout 20 "$ADB" shell settings put global window_animation_scale 0 || true
with_timeout 20 "$ADB" shell settings put global transition_animation_scale 0 || true
with_timeout 20 "$ADB" shell settings put global animator_duration_scale 0 || true
with_timeout 20 "$ADB" shell svc power stayon true || true
with_timeout 20 "$ADB" shell input keyevent KEYCODE_WAKEUP || true
with_timeout 20 "$ADB" shell wm dismiss-keyguard || true
with_timeout 20 "$ADB" shell settings put system accelerometer_rotation 0 || true
with_timeout 20 "$ADB" shell settings put system user_rotation 0 || true

results="$REPO_ROOT/androidApp/build/outputs/androidTest-results/connected"
status=0
for run in $(seq 1 "$RUNS"); do
  log "connectedDebugAndroidTest run $run/$RUNS"
  if with_timeout "${TEST_TIMEOUT_SECS:-1800}" "$REPO_ROOT/scripts/ci/_gradle.sh" :androidApp:connectedDebugAndroidTest; then
    echo "run $run: PASS"
  else
    echo "run $run: FAIL"; status=1
  fi
  if [[ "$RUNS" -gt 1 && -d "$results" ]]; then
    rm -rf "$results-run$run"; cp -R "$results" "$results-run$run"
  fi
  find "$results" -name 'TEST-*.xml' -exec grep -ho '<testsuite [^>]*tests="[0-9]*"[^>]*' {} + 2>/dev/null \
    | sed -E 's/.*name="([^"]*)".*tests="([0-9]+)".*failures="([0-9]+)".*errors="([0-9]+)".*skipped="([0-9]+)".*/  \1: tests=\2 failures=\3 errors=\4 skipped=\5/' || true
done
exit $status
