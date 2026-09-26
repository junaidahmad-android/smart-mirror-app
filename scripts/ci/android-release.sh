#!/usr/bin/env bash
# CD: builds the Android release App Bundle (R8, shrink). Signed when the signing env vars are
# set, otherwise unsigned (the Gradle signingConfig falls back gracefully; nothing is uploaded).
#
# Env (all optional):
#   ANDROID_KEYSTORE_BASE64    base64 of the upload keystore (.jks); decoded to a temp file
#   ANDROID_KEYSTORE_PATH      path to an existing keystore (instead of the base64 form)
#   ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
#   ANDROID_VERSION_CODE, ANDROID_VERSION_NAME   (default: build.gradle.kts values)
# Output: androidApp/build/outputs/bundle/release/androidApp-release.aab
source "$(dirname "$0")/lib.sh"

tmp_keystore=""
cleanup() { if [[ -n "$tmp_keystore" ]]; then rm -f "$tmp_keystore"; fi; }
trap cleanup EXIT

if [[ -n "${ANDROID_KEYSTORE_BASE64:-}" && -z "${ANDROID_KEYSTORE_PATH:-}" ]]; then
  tmp_keystore="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/upload-keystore.XXXXXX")"
  printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$tmp_keystore"
  export ANDROID_KEYSTORE_PATH="$tmp_keystore"
fi

if [[ -n "${ANDROID_KEYSTORE_PATH:-}" && -n "${ANDROID_KEYSTORE_PASSWORD:-}" && -n "${ANDROID_KEY_ALIAS:-}" && -n "${ANDROID_KEY_PASSWORD:-}" ]]; then
  signed=true; log "Building SIGNED release bundle"
else
  signed=false; log "Signing secrets not set: building UNSIGNED release bundle (not uploadable)"
fi

gradlew :androidApp:bundleRelease

aab="$REPO_ROOT/androidApp/build/outputs/bundle/release/androidApp-release.aab"
[[ -f "$aab" ]] || die "AAB not found at $aab"
ls -l "$aab"
if [[ "$signed" == true ]]; then
  ensure_java
  # jarsigner ships with the JDK; -verify -strict fails on an unsigned or corrupt bundle.
  "$JAVA_HOME/bin/jarsigner" -verify "$aab" | grep -q "jar verified" || die "AAB signature verification failed"
  echo "Signature verified."
fi
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  { echo "aab=$aab"; echo "signed=$signed"; } >> "$GITHUB_OUTPUT"
fi
