#!/usr/bin/env bash
# Shared helpers for scripts/ci/*. Source it; do not execute it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export REPO_ROOT

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# with_timeout SECONDS cmd...  Portable (macOS has no coreutils `timeout`): perl's alarm
# delivers SIGALRM to the exec'd command, which terminates it; exit code 142 means timed out.
with_timeout() {
  local secs="$1"; shift
  perl -e 'alarm shift; exec @ARGV or die "exec failed: $!"' "$secs" "$@"
}

# JDK for the Gradle launcher (the daemon itself is pinned to JDK 21 by gradle-daemon-jvm.properties).
ensure_java() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then return; fi
  local studio="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [[ -x "$studio/bin/java" ]]; then export JAVA_HOME="$studio"; return; fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1 && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export JAVA_HOME; return
  fi
  die "Set JAVA_HOME to a JDK 17+ (JDK 21 recommended)."
}

gradlew() {
  ensure_java
  local args=(--stacktrace)
  [[ -n "${CI:-}" ]] && args+=(--no-daemon --console=plain)
  (cd "$REPO_ROOT" && ./gradlew "${args[@]}" "$@")
}

android_sdk() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  [[ -d "$sdk" ]] || die "Android SDK not found; set ANDROID_HOME."
  echo "$sdk"
}
