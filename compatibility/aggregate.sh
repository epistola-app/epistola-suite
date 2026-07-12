#!/usr/bin/env bash
#
# Compatibility matrix — aggregate step.
#
# Combines the two kinds of declaration into plugin↔suite verdicts:
#   - the SUITE's declared range, from matrix.json cells that carry a
#     `declaredRange` (produced by ./smoke.sh against a compat-aware suite image);
#   - each CLIENT's declaration feed (e.g. valtimo-epistola-plugin's
#     compatibility.json), which states the single contract version it targets.
#
# It applies the one compatibility rule (R4) — `floor <= target <= apiVersion` —
# for every (feed, suite-cell) pair and writes the result to aggregate.json. This
# is the "aggregation" half of D6: declarations live with each artifact, the
# aggregator only reads the feeds and applies the rule (R8).
#
# Usage:
#   compatibility/aggregate.sh --feed ../valtimo-epistola-plugin/compatibility.json
#   compatibility/aggregate.sh --matrix matrix.json --feed a.json --feed b.json --out aggregate.json
#
# Inputs (flags override env):
#   --matrix IN    suite matrix JSON (declaredRange cells)  (default: compatibility/matrix.json)
#   --feed   FEED  a client declaration feed; repeatable    (required, >=1)
#   --out    OUT   aggregate JSON to write, or `-` for stdout (default: compatibility/aggregate.json)
#
# Requires: jq.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MATRIX="${MATRIX:-${SCRIPT_DIR}/matrix.json}"
OUT="${OUT:-${SCRIPT_DIR}/aggregate.json}"
FEEDS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --matrix) MATRIX="$2";     shift 2 ;;
    --feed)   FEEDS+=("$2");   shift 2 ;;
    --out)    OUT="$2";        shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v jq >/dev/null 2>&1 || { echo "missing required tool: jq" >&2; exit 3; }
[[ -f "${MATRIX}" ]]     || { echo "matrix not found: ${MATRIX}" >&2; exit 2; }
[[ "${#FEEDS[@]}" -ge 1 ]] || { echo "no feed given (use --feed <compatibility.json>)" >&2; exit 2; }
for f in "${FEEDS[@]}"; do
  [[ -f "$f" ]] || { echo "feed not found: $f" >&2; exit 2; }
done

now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
feeds_json="$(jq -s '.' "${FEEDS[@]}")"
tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT

# Version comparison happens in jq: strip a build/pre-release qualifier (from the
# first `-`), split into numeric parts padded to 3, and compare the arrays (jq
# compares number arrays element-wise, numerically). floor <= target <= max.
jq -n \
  --argjson feeds "${feeds_json}" \
  --slurpfile matrix "${MATRIX}" \
  --arg now "${now}" '
  def parts: (sub("-.*$"; "") | split(".") | map(tonumber? // 0)) + [0,0,0] | .[0:3];
  def le($a; $b): ($a | parts) <= ($b | parts);
  def inRange($mn; $v; $mx): le($mn; $v) and le($v; $mx);

  ($matrix[0]) as $m
  | {
      schemaVersion: 1,
      anchor: ($m.anchor // "epistola-contract"),
      rule: "floor <= target <= apiVersion",
      generatedAt: $now,
      rows: [
        $feeds[] as $f
        | ($m.cells[]? | select(.declaredRange)) as $c
        | ($c.declaredRange.min) as $min
        | ($c.declaredRange.max) as $max
        | ($f.targetContractVersion) as $target
        | {
            plugin: $f.artifact,
            pluginVersion: $f.version,
            role: ($f.role // "client"),
            targetContract: $target,
            suite: $c.suite,
            suiteContract: $c.contract,
            suiteRange: $c.declaredRange,
            compatible: inRange($min; $target; $max),
            reason: (
              if inRange($min; $target; $max) then "target \($target) within [\($min) .. \($max)]"
              elif (le($min; $target) | not) then "target \($target) below floor \($min)"
              else "target \($target) above \($max)" end
            )
          }
      ]
      | sort_by(.plugin, .pluginVersion, .suite)
    }
  ' > "${tmp}"

if [[ "${OUT}" == "-" ]]; then
  cat "${tmp}"
else
  mkdir -p "$(dirname -- "${OUT}")"
  cp "${tmp}" "${OUT}"
  rows="$(jq '.rows | length' "${OUT}")"
  compat="$(jq '[.rows[] | select(.compatible)] | length' "${OUT}")"
  echo "[aggregate] wrote ${OUT} (${rows} row(s), ${compat} compatible)" >&2
fi
