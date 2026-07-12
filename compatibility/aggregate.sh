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
# A feed source is a local path OR an http(s) URL (each client publishes its
# compatibility.json at its own repo, so remote is the normal case). URL fetches
# and feeds-file entries are BEST EFFORT: an unreachable feed (e.g. the plugin
# branch not merged yet → 404) is warned and skipped, not fatal — the aggregate
# is simply missing that row. A missing local path passed via --feed is an error
# (typo protection).
#
# Inputs (flags override env):
#   --matrix     IN    suite matrix JSON (declaredRange cells)  (default: compatibility/matrix.json)
#   --feed       FEED  a client feed (local path or http[s] URL); repeatable
#   --feeds-file FILE  a file of feed sources, one per line (`#` comments allowed)
#   --out        OUT   aggregate JSON to write, or `-` for stdout (default: compatibility/aggregate.json)
#
# At least one source (--feed or --feeds-file) is required.
#
# Requires: jq (and curl when any source is a URL).

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MATRIX="${MATRIX:-${SCRIPT_DIR}/matrix.json}"
OUT="${OUT:-${SCRIPT_DIR}/aggregate.json}"
FEEDS=()          # explicit --feed sources (missing local path = error)
FEEDS_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --matrix)     MATRIX="$2";     shift 2 ;;
    --feed)       FEEDS+=("$2");   shift 2 ;;
    --feeds-file) FEEDS_FILE="$2"; shift 2 ;;
    --out)        OUT="$2";        shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v jq >/dev/null 2>&1 || { echo "missing required tool: jq" >&2; exit 3; }
[[ -f "${MATRIX}" ]] || { echo "matrix not found: ${MATRIX}" >&2; exit 2; }

log() { echo "[aggregate] $*" >&2; }

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

# --- resolve feed sources to local JSON files (best effort) --------------------
# Collect sources: explicit --feed first (strict), then feeds-file lines (lenient).
SOURCES=("${FEEDS[@]}")
STRICT_COUNT="${#FEEDS[@]}"   # first N sources are strict (missing local = error)
if [[ -n "${FEEDS_FILE}" ]]; then
  [[ -f "${FEEDS_FILE}" ]] || { echo "feeds file not found: ${FEEDS_FILE}" >&2; exit 2; }
  while IFS= read -r line; do
    line="${line%%#*}"; line="${line#"${line%%[![:space:]]*}"}"; line="${line%"${line##*[![:space:]]}"}"
    [[ -n "${line}" ]] && SOURCES+=("${line}")
  done < "${FEEDS_FILE}"
fi
[[ "${#SOURCES[@]}" -ge 1 ]] || { echo "no feed source given (use --feed or --feeds-file)" >&2; exit 2; }

resolved=()
i=0
for src in "${SOURCES[@]}"; do
  strict=$(( i < STRICT_COUNT )); i=$(( i + 1 ))
  dest="${tmp}/feed-${i}.json"
  if [[ "${src}" =~ ^https?:// ]]; then
    command -v curl >/dev/null 2>&1 || { echo "curl required to fetch URL feeds" >&2; exit 3; }
    if curl -fsSL --max-time 30 "${src}" -o "${dest}" 2>/dev/null && jq -e . "${dest}" >/dev/null 2>&1; then
      resolved+=("${dest}"); log "fetched ${src}"
    else
      log "WARN: could not fetch ${src} — skipping (feed absent from the aggregate)"
    fi
  elif [[ -f "${src}" ]]; then
    resolved+=("${src}")
  elif [[ "${strict}" -eq 1 ]]; then
    echo "feed not found: ${src}" >&2; exit 2
  else
    log "WARN: feed not found: ${src} — skipping"
  fi
done

now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [[ "${#resolved[@]}" -ge 1 ]]; then
  feeds_json="$(jq -s '.' "${resolved[@]}")"
else
  log "no feeds resolved — writing an empty aggregate"
  feeds_json="[]"
fi
out_tmp="${tmp}/out.json"

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
  ' > "${out_tmp}"

if [[ "${OUT}" == "-" ]]; then
  cat "${out_tmp}"
else
  mkdir -p "$(dirname -- "${OUT}")"
  cp "${out_tmp}" "${OUT}"
  rows="$(jq '.rows | length' "${OUT}")"
  compat="$(jq '[.rows[] | select(.compatible)] | length' "${OUT}")"
  log "wrote ${OUT} (${rows} row(s), ${compat} compatible)"
fi
