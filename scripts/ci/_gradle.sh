#!/usr/bin/env bash
# Runs the Gradle wrapper with the CI JDK/flags (lets other scripts wrap Gradle in with_timeout).
source "$(dirname "$0")/lib.sh"
gradlew "$@"
