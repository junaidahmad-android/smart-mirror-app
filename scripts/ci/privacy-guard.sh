#!/usr/bin/env bash
# Privacy guard (MVP-13.AC2, NFR-11, QA-BUG-04/05):
#  - the merged RELEASE AndroidManifest must not request INTERNET or ACCESS_NETWORK_STATE;
#  - PrivacyInfo.xcprivacy must exist in the iOS sources and, if an .app is given/found, in the bundle.
# Usage: scripts/ci/privacy-guard.sh [--android-only | --ios-only] [path/to/App.app]
source "$(dirname "$0")/lib.sh"

mode="all"; app_path=""
for arg in "$@"; do
  case "$arg" in
    --android-only) mode="android" ;;
    --ios-only) mode="ios" ;;
    *) app_path="$arg" ;;
  esac
done
failed=0

if [[ "$mode" != "ios" ]]; then
  log "Android: merged release manifest"
  gradlew :androidApp:processReleaseMainManifest
  manifest="$REPO_ROOT/androidApp/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"
  [[ -f "$manifest" ]] || manifest="$(find "$REPO_ROOT/androidApp/build/intermediates" -path '*release*' -name AndroidManifest.xml -path '*merged_manifest*' | head -1)"
  [[ -f "$manifest" ]] || die "Merged release manifest not found."
  echo "Checking $manifest"
  for perm in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE; do
    # Match <uses-permission android:name="..."> declarations only (not tools:node="remove" leftovers).
    if grep -E "<uses-permission[^>]*android:name=\"$perm\"" "$manifest" | grep -vq 'tools:node="remove"'; then
      echo "FAIL: merged release manifest requests $perm"; failed=1
    else
      echo "OK: no $perm"
    fi
  done
fi

if [[ "$mode" != "android" ]]; then
  log "iOS: PrivacyInfo.xcprivacy"
  src="$REPO_ROOT/iosApp/iosApp/PrivacyInfo.xcprivacy"
  if [[ ! -f "$src" ]]; then
    echo "FAIL: $src missing"; failed=1
  elif command -v plutil >/dev/null 2>&1 && ! plutil -lint "$src" >/dev/null; then
    echo "FAIL: $src is not a valid plist"; failed=1
  else
    echo "OK: $src present"
  fi
  if [[ -z "$app_path" ]]; then
    # Newest simulator build of the app, if one exists (xcodebuild test/build output).
    app_path="$(find "${DERIVED_DATA_PATH:-$REPO_ROOT/build/ios/DerivedData}" "$HOME/Library/Developer/Xcode/DerivedData" \
      -maxdepth 6 -type d -path '*Build/Products/*-iphonesimulator/Smartmirrorapp.app' 2>/dev/null \
      | head -1 || true)"
  fi
  if [[ -n "$app_path" ]]; then
    if [[ -f "$app_path/PrivacyInfo.xcprivacy" ]]; then
      echo "OK: bundle $app_path contains PrivacyInfo.xcprivacy"
    else
      echo "FAIL: bundle $app_path has no PrivacyInfo.xcprivacy"; failed=1
    fi
  else
    echo "NOTE: no built .app found; bundle check skipped (source check only)."
  fi
fi

[[ $failed -eq 0 ]] || die "Privacy guard failed."
log "Privacy guard passed"
