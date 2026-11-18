#!/usr/bin/env bash
# Asserts that a built Epistola image starts its JVM with the Base64 intrinsic disabled
# (CVE-2026-114938, Java4Shell), also when the deployment sets its own JAVA_TOOL_OPTIONS
# the way the Helm chart's jvm.options does.
#
# Usage: scripts/check-image-jvm-flags.sh <image>
set -euo pipefail

image="${1:?usage: $0 <image>}"
engine="${CONTAINER_ENGINE:-docker}"

flags() {
  # The buildpack launcher applies the image's launch environment, as it does for the app itself.
  "$engine" run --rm --entrypoint /cnb/lifecycle/launcher "$@" "$image" \
    java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E '^ *bool UseBASE64Intrinsics '
}

check() {
  local label="$1"
  shift
  local line
  line="$(flags "$@")" || { echo "FAIL ($label): UseBASE64Intrinsics not reported" >&2; return 1; }
  if [[ "$line" =~ =\ +false ]]; then
    echo "ok ($label): $line"
  else
    echo "FAIL ($label): $line" >&2
    return 1
  fi
}

check "image defaults"
check "deployment JAVA_TOOL_OPTIONS" -e "JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
