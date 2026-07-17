#!/usr/bin/env bash
#
# Compatibility matrix — aggregate step.
#
# Combines three kinds of declaration into plugin↔suite verdicts:
#   - the SUITE's declared range, from matrix.json cells that carry a
#     `declaredRange` (produced by ./smoke.sh against a compat-aware suite image);
#   - each CLIENT's declaration feed (e.g. valtimo-epistola-plugin's
#     compatibility.json): the contract version it targets and, optionally, the
#     `operations` it calls;
#   - the ANCHOR's compatibility log (epistola-contract's compatibility-log.json):
#     per released contract version, whether it broke wire compatibility and
#     exactly which operations it broke.
#
# Verdicts are OPERATION-LEVEL when possible: a client is compatible with a
# suite unless a breaking change between the client's target version and the
# suite's contract version touches an operation the client actually uses. A
# contract release that breaks only calls a client never makes does not mark
# that client incompatible. When the operation-level join is not possible (the
# feed declares no operations, the log is unreachable, or the log does not
# fully cover the version window — an incomplete log must never yield a false
# green), the verdict falls back to the coarse range rule (R4):
# `floor <= target <= apiVersion`. Every row records which rule judged it
# (`basis: "operations" | "range"`).
#
# Declarations live with each artifact; the aggregator only reads the feeds and
# applies the rule (R8).
#
# Usage:
#   compatibility/aggregate.sh --feed ../valtimo-epistola-plugin/compatibility.json
#   compatibility/aggregate.sh --matrix matrix.json --feed a.json --feed b.json --out aggregate.json
#
# A feed source is a local path OR an http(s) URL (each client publishes its
# compatibility.json at its own repo, so remote is the normal case). URL fetches
# and feeds-file entries are BEST EFFORT: an unreachable feed (e.g. the plugin
# branch not merged yet → 404) is warned and skipped, not fatal — the aggregate
# is simply missing that row. The same applies to a feed whose SHAPE is not a
# v1 client declaration (missing targetContractVersion, unknown schemaVersion):
# feeds come from repos we don't control, so a malformed one must never fail the
# aggregate. A missing or malformed local path passed via --feed is an error
# (typo protection).
#
# Inputs (flags override env):
#   --matrix     IN    suite matrix JSON (declaredRange cells)  (default: compatibility/matrix.json)
#   --feed       FEED  a client feed (local path or http[s] URL); repeatable
#   --feeds-file FILE  a file of feed sources, one per line (`#` comments allowed)
#   --log        LOG   the contract's compatibility-log.json (local path or URL);
#                      fetched best-effort (default: the raw URL of
#                      epistola-contract's main branch). Pass `none` to disable.
#   --out        OUT   aggregate JSON to write, or `-` for stdout (default: compatibility/aggregate.json)
#
# At least one source (--feed or --feeds-file) is required.
#
# Requires: jq (and curl when any source is a URL).

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MATRIX="${MATRIX:-${SCRIPT_DIR}/matrix.json}"
OUT="${OUT:-${SCRIPT_DIR}/aggregate.json}"
LOG_SRC="${LOG_SRC:-https://raw.githubusercontent.com/epistola-app/epistola-contract/main/compatibility-log.json}"
FEEDS=()          # explicit --feed sources (missing local path = error)
FEEDS_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --matrix)     MATRIX="$2";     shift 2 ;;
    --feed)       FEEDS+=("$2");   shift 2 ;;
    --feeds-file) FEEDS_FILE="$2"; shift 2 ;;
    --log)        LOG_SRC="$2";    shift 2 ;;
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

# A usable client feed: schemaVersion 1 (the only shape this aggregator knows),
# a string artifact, a string targetContractVersion, and — when present — an
# operations array of strings. Anything else is skipped (lenient sources) or an
# error (--feed), never passed into the join — a wrong-shaped feed would
# otherwise crash the jq program mid-aggregate.
feed_valid() {
  jq -e '(.schemaVersion == 1)
         and (.artifact | type == "string")
         and (.targetContractVersion | type == "string")
         and ((.operations == null) or ((.operations | type == "array") and all(.operations[]?; type == "string")))' \
    "$1" >/dev/null 2>&1
}

# A usable contract compatibility log: schemaVersion 1 and an entries array of
# {version, …} objects (see epistola-contract's compatibility-log.json).
log_valid() {
  jq -e '(.schemaVersion == 1)
         and (.entries | type == "array")
         and all(.entries[]?; .version | type == "string")' "$1" >/dev/null 2>&1
}

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
    if ! curl -fsSL --max-time 30 "${src}" -o "${dest}" 2>/dev/null; then
      log "WARN: could not fetch ${src} — skipping (feed absent from the aggregate)"
    elif ! feed_valid "${dest}"; then
      log "WARN: ${src} is not a valid v1 client declaration — skipping (feed absent from the aggregate)"
    else
      resolved+=("${dest}"); log "fetched ${src}"
    fi
  elif [[ -f "${src}" ]]; then
    if feed_valid "${src}"; then
      resolved+=("${src}")
    elif [[ "${strict}" -eq 1 ]]; then
      echo "feed is not a valid v1 client declaration: ${src}" >&2; exit 2
    else
      log "WARN: ${src} is not a valid v1 client declaration — skipping"
    fi
  elif [[ "${strict}" -eq 1 ]]; then
    echo "feed not found: ${src}" >&2; exit 2
  else
    log "WARN: feed not found: ${src} — skipping"
  fi
done

# --- resolve the contract compatibility log (best effort) ----------------------
# Unusable (unreachable, malformed, disabled) → operation-level verdicts are
# impossible and every row falls back to the range rule; never fatal.
log_json="null"
if [[ "${LOG_SRC}" != "none" ]]; then
  log_file=""
  if [[ "${LOG_SRC}" =~ ^https?:// ]]; then
    if command -v curl >/dev/null 2>&1 && curl -fsSL --max-time 30 "${LOG_SRC}" -o "${tmp}/log.json" 2>/dev/null; then
      log_file="${tmp}/log.json"
    else
      log "WARN: could not fetch compatibility log ${LOG_SRC} — verdicts fall back to the range rule"
    fi
  elif [[ -f "${LOG_SRC}" ]]; then
    log_file="${LOG_SRC}"
  else
    log "WARN: compatibility log not found: ${LOG_SRC} — verdicts fall back to the range rule"
  fi
  if [[ -n "${log_file}" ]]; then
    if log_valid "${log_file}"; then
      log_json="$(cat "${log_file}")"
      log "using compatibility log ${LOG_SRC} ($(jq '.entries | length' "${log_file}") release entries)"
    else
      log "WARN: ${LOG_SRC} is not a valid v1 compatibility log — verdicts fall back to the range rule"
    fi
  fi
fi

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
# compares number arrays element-wise, numerically).
#
# Per (feed, cell) pair the verdict is chosen in this order:
#   1. target above the suite contract → incompatible (a newer client may call
#      operations the suite does not have yet; no log can clear that);
#   2. operation-level, when the feed declares operations AND the log fully
#      covers the window (target, suiteContract] with computed entries:
#      incompatible iff some entry in the window broke an operation the client
#      uses — the reason names the release(s) and operation(s);
#   3. otherwise the range rule (R4): floor <= target <= apiVersion.
jq -n \
  --argjson feeds "${feeds_json}" \
  --argjson compatLog "${log_json}" \
  --slurpfile matrix "${MATRIX}" \
  --arg now "${now}" '
  def parts: (sub("-.*$"; "") | split(".") | map(tonumber? // 0)) + [0,0,0] | .[0:3];
  def le($a; $b): ($a | parts) <= ($b | parts);
  def lt($a; $b): ($a | parts) < ($b | parts);
  def inRange($mn; $v; $mx): le($mn; $v) and le($v; $mx);

  ($matrix[0]) as $m
  | (($compatLog.entries // []) | sort_by(.version | parts)) as $entries
  | {
      schemaVersion: 1,
      anchor: ($m.anchor // "epistola-contract"),
      rule: "no breaking change in (target .. suiteContract] touches a used operation; else floor <= target <= apiVersion",
      generatedAt: $now,
      rows: [
        $feeds[] as $f
        | ($m.cells[]? | select(.declaredRange)) as $c
        | ($c.declaredRange.min) as $min
        | ($c.declaredRange.max) as $max
        | ($f.targetContractVersion) as $target
        | ($f.operations) as $ops
        # The releases between the client'"'"'s target (exclusive) and the suite
        # contract (inclusive) — the changes the client "skips over".
        | ([$entries[] | select(lt($target; .version) and le(.version; $max))]) as $window
        # Operation-level judging needs: declared operations, a log whose span
        # covers the whole window (oldest entry <= target, newest >= max), and
        # no uncomputed entry inside the window. Anything less falls back to
        # the range rule — an incomplete log must never produce a false green.
        | (
            ($ops != null)
            and (($entries | length) > 0)
            and le($entries[0].version; $target)
            and le($max; $entries[-1].version)
            and ($window | all(.computed != false))
          ) as $opCapable
        | ([$window[]
            | select(.breaking == true)
            | {version, ops: [.brokenOperations[]? | select(. as $o | ($ops // []) | index($o) != null)]}
            | select(.ops | length > 0)
           ]) as $hits
        | (
            if (le($target; $max) | not) then
              {compatible: false, basis: "range",
               reason: "target \($target) above suite contract \($max)"}
            elif $opCapable then
              (if ($hits | length) == 0 then
                {compatible: true, basis: "operations",
                 reason: "no breaking change in (\($target) .. \($max)] touches an operation it uses"}
              else
                {compatible: false, basis: "operations",
                 reason: ("breaks operation(s) it uses: " + ($hits | map("\(.version) breaks \(.ops | join(", "))") | join("; ")))}
              end)
            elif inRange($min; $target; $max) then
              {compatible: true, basis: "range", reason: "target \($target) within [\($min) .. \($max)]"}
            else
              {compatible: false, basis: "range", reason: "target \($target) below floor \($min)"}
            end
          ) as $verdict
        | {
            plugin: $f.artifact,
            pluginVersion: $f.version,
            role: ($f.role // "client"),
            targetContract: $target,
            operations: $ops,
            suite: $c.suite,
            suiteContract: $c.contract,
            suiteRange: $c.declaredRange,
            compatible: $verdict.compatible,
            basis: $verdict.basis,
            reason: $verdict.reason
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
