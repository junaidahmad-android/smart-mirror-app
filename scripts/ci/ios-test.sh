#!/usr/bin/env bash
# CI job `ios`: shared Kotlin/Native tests on the simulator, then the iosApp scheme
# (iosAppTests unit + iosAppUITests) on a dynamically resolved simulator, signing disabled.
# Usage: scripts/ci/ios-test.sh            (SIM_NAME="iPad Pro 11-inch (M5)" to pick another device)
# Env: XCODE_TEST_TIMEOUT_SECS (default 2400). Outputs: build/ios/TestResults.xcresult
source "$(dirname "$0")/lib.sh"

out="$REPO_ROOT/build/ios"
mkdir -p "$out"
export DERIVED_DATA_PATH="$out/DerivedData"

log "Kotlin/Native shared tests (iosSimulatorArm64Test)"
gradlew :sharedLogic:iosSimulatorArm64Test

udid="$(SIM_NAME="${SIM_NAME:-}" "$REPO_ROOT/scripts/ci/resolve-simulator.sh")"
log "Simulator: $(xcrun simctl list devices | grep "$udid" | sed 's/^ *//')"
shutdown_sim() { xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true; }
trap shutdown_sim EXIT
xcrun simctl boot "$udid" 2>/dev/null || true
with_timeout 300 xcrun simctl bootstatus "$udid" -b >/dev/null

rm -rf "$out/TestResults.xcresult"
log "xcodebuild test (scheme iosApp)"
# The "Compile Kotlin Framework" build phase runs Gradle and inherits JAVA_HOME from here.
ensure_java
set +e
with_timeout "${XCODE_TEST_TIMEOUT_SECS:-2400}" xcodebuild test \
  -project "$REPO_ROOT/iosApp/iosApp.xcodeproj" \
  -scheme iosApp \
  -destination "id=$udid" \
  -derivedDataPath "$DERIVED_DATA_PATH" \
  -resultBundlePath "$out/TestResults.xcresult" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  > "$out/xcodebuild-test.log" 2>&1
status=$?
set -e
grep -E '^(Test Suite .*(passed|failed)|Test case .*failed|.*error:)' "$out/xcodebuild-test.log" | tail -40 || true
grep -E '\*\* TEST (SUCCEEDED|FAILED) \*\*|Executed [0-9]+ tests' "$out/xcodebuild-test.log" | tail -3 || true
[[ $status -eq 0 ]] || die "xcodebuild test failed (exit $status); see $out/TestResults.xcresult"

app="$(find "$DERIVED_DATA_PATH/Build/Products" -maxdepth 2 -name '*.app' -path '*-iphonesimulator/*' ! -name '*Tests-Runner.app' | head -1)"
log "Privacy guard on $app"
"$REPO_ROOT/scripts/ci/privacy-guard.sh" --ios-only "$app"
