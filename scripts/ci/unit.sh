#!/usr/bin/env bash
# CI job `shared-and-android-unit`: shared JVM tests, Android JVM unit tests, debug APK, lint.
# Usage: scripts/ci/unit.sh
source "$(dirname "$0")/lib.sh"
log "Shared (JVM host) tests, Android unit tests, debug APK, Android lint"
gradlew \
  :sharedLogic:testAndroidHostTest \
  :androidApp:testDebugUnitTest \
  :androidApp:assembleDebug \
  :androidApp:lintDebug
log "Outputs"
ls -1 "$REPO_ROOT"/androidApp/build/outputs/apk/debug/*.apk
