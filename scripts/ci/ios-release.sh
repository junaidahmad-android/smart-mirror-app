#!/usr/bin/env bash
# CD: archives the iOS app (Release) and, when signing secrets are present, exports a signed
# App Store .ipa using a generated exportOptions.plist. Without secrets it produces an UNSIGNED
# archive only (compile check) and exits 0. Uploading to TestFlight is a separate workflow step.
#
# Env (signing; all four required together):
#   APPLE_TEAM_ID                      10-character team ID
#   IOS_DIST_CERT_P12_BASE64           base64 of the Apple Distribution certificate (.p12)
#   IOS_DIST_CERT_PASSWORD             its password
#   IOS_PROVISIONING_PROFILE_BASE64    base64 of the App Store provisioning profile
# Optional: IOS_BUNDLE_ID (default: taken from the profile), IOS_BUILD_NUMBER, IOS_MARKETING_VERSION
# Outputs: build/ios/release/Smartmirrorapp.xcarchive, build/ios/release/export/*.ipa
source "$(dirname "$0")/lib.sh"

out="$REPO_ROOT/build/ios/release"
rm -rf "$out"; mkdir -p "$out"
archive="$out/Smartmirrorapp.xcarchive"
ensure_java   # the "Compile Kotlin Framework" build phase runs Gradle

version_args=()
if [[ -n "${IOS_BUILD_NUMBER:-}" ]]; then version_args+=("CURRENT_PROJECT_VERSION=$IOS_BUILD_NUMBER"); fi
if [[ -n "${IOS_MARKETING_VERSION:-}" ]]; then version_args+=("MARKETING_VERSION=$IOS_MARKETING_VERSION"); fi

if [[ -z "${APPLE_TEAM_ID:-}" || -z "${IOS_DIST_CERT_P12_BASE64:-}" || -z "${IOS_DIST_CERT_PASSWORD:-}" || -z "${IOS_PROVISIONING_PROFILE_BASE64:-}" ]]; then
  log "iOS signing secrets not set: UNSIGNED archive only (no export, no upload)"
  xcodebuild archive -project "$REPO_ROOT/iosApp/iosApp.xcodeproj" -scheme iosApp -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$archive" \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" ${version_args[@]+"${version_args[@]}"}
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then echo "signed=false" >> "$GITHUB_OUTPUT"; fi
  exit 0
fi

# --- temporary keychain + profile, removed on exit -------------------------------------------
work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ios-signing.XXXXXX")"
keychain="$work/build.keychain-db"
keychain_password="$(uuidgen)"
old_keychains="$(security list-keychains -d user | tr -d '"' | xargs)"
profile_dest=""
cleanup() {
  # shellcheck disable=SC2086
  security list-keychains -d user -s $old_keychains >/dev/null 2>&1 || true
  security delete-keychain "$keychain" >/dev/null 2>&1 || true
  if [[ -n "$profile_dest" ]]; then rm -f "$profile_dest"; fi
  rm -rf "$work"
}
trap cleanup EXIT

printf '%s' "$IOS_DIST_CERT_P12_BASE64" | base64 --decode > "$work/dist.p12"
printf '%s' "$IOS_PROVISIONING_PROFILE_BASE64" | base64 --decode > "$work/profile.mobileprovision"

security create-keychain -p "$keychain_password" "$keychain"
security set-keychain-settings -lut 21600 "$keychain"
security unlock-keychain -p "$keychain_password" "$keychain"
security import "$work/dist.p12" -P "$IOS_DIST_CERT_PASSWORD" -A -t cert -f pkcs12 -k "$keychain"
security set-key-partition-list -S apple-tool:,apple: -k "$keychain_password" "$keychain" >/dev/null
# shellcheck disable=SC2086
security list-keychains -d user -s "$keychain" $old_keychains

security cms -D -i "$work/profile.mobileprovision" > "$work/profile.plist"
profile_uuid="$(/usr/libexec/PlistBuddy -c 'Print :UUID' "$work/profile.plist")"
profile_name="$(/usr/libexec/PlistBuddy -c 'Print :Name' "$work/profile.plist")"
profile_app_id="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' "$work/profile.plist")"
bundle_id="${IOS_BUNDLE_ID:-${profile_app_id#*.}}"
profiles_dir="$HOME/Library/MobileDevice/Provisioning Profiles"
mkdir -p "$profiles_dir"
profile_dest="$profiles_dir/$profile_uuid.mobileprovision"
cp "$work/profile.mobileprovision" "$profile_dest"
log "Signing $bundle_id with profile '$profile_name' (team $APPLE_TEAM_ID)"

xcodebuild archive -project "$REPO_ROOT/iosApp/iosApp.xcodeproj" -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$archive" \
  DEVELOPMENT_TEAM="$APPLE_TEAM_ID" PRODUCT_BUNDLE_IDENTIFIER="$bundle_id" \
  CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY="Apple Distribution" \
  PROVISIONING_PROFILE_SPECIFIER="$profile_name" OTHER_CODE_SIGN_FLAGS="--keychain $keychain" \
  ${version_args[@]+"${version_args[@]}"}

# exportOptions.plist: App Store Connect export, manual signing with the installed profile.
export_options="$out/exportOptions.plist"
plutil -create xml1 "$export_options"
plutil -insert method -string app-store-connect "$export_options"
plutil -insert destination -string export "$export_options"
plutil -insert teamID -string "$APPLE_TEAM_ID" "$export_options"
plutil -insert signingStyle -string manual "$export_options"
plutil -insert signingCertificate -string "Apple Distribution" "$export_options"
plutil -insert uploadSymbols -bool YES "$export_options"
plutil -insert manageAppVersionAndBuildNumber -bool NO "$export_options"
plutil -insert provisioningProfiles -dictionary "$export_options"
/usr/libexec/PlistBuddy -c "Add :provisioningProfiles:$bundle_id string $profile_name" "$export_options"

xcodebuild -exportArchive -archivePath "$archive" -exportPath "$out/export" -exportOptionsPlist "$export_options"
ipa="$(find "$out/export" -name '*.ipa' | head -1)"
[[ -f "$ipa" ]] || die "No .ipa exported"
unzip -l "$ipa" | grep -q 'PrivacyInfo.xcprivacy' || die "PrivacyInfo.xcprivacy missing from the .ipa"
ls -l "$ipa"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then { echo "signed=true"; echo "ipa=$ipa"; } >> "$GITHUB_OUTPUT"; fi
