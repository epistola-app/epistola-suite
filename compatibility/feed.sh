#!/usr/bin/env bash
#
# Compatibility feed — generate or verify the suite's own compatibility.json.
#
# Every artifact in the compatibility system publishes the same kind of feed
# (R8: declarations live with each artifact). The suite is a SERVER: its feed
# declares the contract version it implements, derived from the
# `epistola-contract` dependency pin — never hand-authored:
#
#   { "schemaVersion": 1, "artifact": "epistola-suite", "role": "server",
#     "version": "<suite version>", "contractVersion": "<contract pin>" }
#
# The matrix (hosted in the epistola-contract repo, compatibility/ there) reads
# this feed next to each client's feed and the breaking-change log, and judges
# the pairings. The floor is NOT declared here — the aggregator derives it from
# the log, and the runtime /ping range keeps deriving it from the contract jar.
#
# Usage:
#   compatibility/feed.sh             # (re)write compatibility.json at the repo root
#   compatibility/feed.sh --verify    # fail if the committed file drifts (CI)
#
# Requires: jq.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
OUT="${REPO_ROOT}/compatibility.json"
MODE="generate"

case "${1:-}" in
  "") ;;
  --verify) MODE="verify" ;;
  -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "unknown argument: $1" >&2; exit 2 ;;
esac

command -v jq >/dev/null 2>&1 || { echo "missing required tool: jq" >&2; exit 3; }

SUITE_VERSION="$(sed -n 's/^version=//p' "${REPO_ROOT}/gradle.properties" | head -1)"
CONTRACT_VERSION="$(sed -n 's/^epistola-contract *= *"\(.*\)"/\1/p' "${REPO_ROOT}/gradle/libs.versions.toml" | head -1)"
[[ -n "${SUITE_VERSION}" ]]    || { echo "could not read version from gradle.properties" >&2; exit 2; }
[[ -n "${CONTRACT_VERSION}" ]] || { echo "could not read epistola-contract from gradle/libs.versions.toml" >&2; exit 2; }

expected="$(jq -n \
  --arg version "${SUITE_VERSION}" \
  --arg contract "${CONTRACT_VERSION}" '
  {
    schemaVersion: 1,
    artifact: "epistola-suite",
    anchor: "epistola-contract",
    role: "server",
    version: $version,
    contractVersion: $contract
  }')"

if [[ "${MODE}" == "verify" ]]; then
  [[ -f "${OUT}" ]] || { echo "compatibility.json is missing — run compatibility/feed.sh and commit it" >&2; exit 1; }
  if ! diff <(printf '%s\n' "${expected}") <(jq . "${OUT}") >/dev/null; then
    echo "compatibility.json drifted from the build files (gradle.properties / libs.versions.toml)." >&2
    echo "Run compatibility/feed.sh and commit the result. Expected:" >&2
    printf '%s\n' "${expected}" >&2
    exit 1
  fi
  echo "[feed] compatibility.json OK (version=${SUITE_VERSION}, contractVersion=${CONTRACT_VERSION})"
else
  printf '%s\n' "${expected}" > "${OUT}"
  echo "[feed] wrote ${OUT} (version=${SUITE_VERSION}, contractVersion=${CONTRACT_VERSION})"
fi
