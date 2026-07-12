#!/usr/bin/env bash
#
# Compatibility matrix — render matrix.json to a human-readable Markdown table.
#
# The matrix JSON (produced by ./smoke.sh) is the source of truth; this renders a
# view of it (per R6 in DESIGN.md — the table is a *view* over declarations, kept
# current from the data, never hand-edited). Pure jq, no docker.
#
# Usage:
#   compatibility/render.sh                       # matrix.json → MATRIX.md
#   compatibility/render.sh --in matrix.json --out -   # write to stdout
#
# Inputs (flags override env):
#   --in        IN   matrix JSON to read    (default: compatibility/matrix.json)
#   --aggregate AGG  aggregate JSON (plugin↔suite verdicts from ./aggregate.sh);
#                    an extra table is rendered when it exists and has rows
#                                           (default: compatibility/aggregate.json)
#   --out       OUT  Markdown file to write, or `-` for stdout
#                                           (default: compatibility/MATRIX.md)
#
# Requires: jq.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
IN="${IN:-${SCRIPT_DIR}/matrix.json}"
AGG="${AGG:-${SCRIPT_DIR}/aggregate.json}"
OUT="${OUT:-${SCRIPT_DIR}/MATRIX.md}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --in)        IN="$2";  shift 2 ;;
    --aggregate) AGG="$2"; shift 2 ;;
    --out)       OUT="$2"; shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v jq >/dev/null 2>&1 || { echo "missing required tool: jq" >&2; exit 3; }
[[ -f "${IN}" ]] || { echo "matrix not found: ${IN}" >&2; exit 2; }

# Render with jq. Cells are already sorted by (suite, contract) in the JSON.
#   result:        pass ✅ / fail ❌ / error ⚠️ / other ❔
#   declared range: "min … max" when an authenticated ping read it, else —
#   range ✓:       ✅ verified / ❌ excluded / — not read (image predates the field)
markdown="$(jq -r '
  def resultSymbol:
    { "pass": "✅ pass", "fail": "❌ fail", "error": "⚠️ error" }[.] // ("❔ " + (. // "?"));
  def rangeCell:
    if .declaredRange then "`\(.declaredRange.min)` … `\(.declaredRange.max)`" else "—" end;
  def verifiedCell:
    if has("rangeVerified") then (if .rangeVerified then "✅" else "❌ **out of range**" end) else "—" end;

  "# Epistola compatibility matrix",
  "",
  "<!-- Generated from `compatibility/matrix.json` by `compatibility/render.sh`. Do not edit by hand. -->",
  "",
  "Anchor: **\(.anchor // "epistola-contract")** (the wire contract both the suite and external clients speak).",
  "A cell is one **(suite, contract)** pairing, established by booting a published suite image and observing `/api/ping` — see [`README.md`](./README.md).",
  "",
  "_Last generated: \(.generatedAt // "never")._",
  "",
  (
    if (.cells | length) == 0 then
      "_No cells recorded yet. Run `compatibility/smoke.sh` to add one._"
    else
      (
        "| Suite | Contract | Result | Declared range | In range | Verified at |",
        "| --- | --- | --- | --- | --- | --- |"
      ),
      ( .cells[]
        | "| \(.suite) | `\(.contract)` | \(.result | resultSymbol) | \(rangeCell) | \(verifiedCell) | \(.verifiedAt // "—") |"
      )
    end
  ),
  "",
  "### Legend",
  "",
  "- **Result** — `✅ pass`: the suite booted and served `/api/ping`. `❌ fail` / `⚠️ error`: see the cell `detail` in `matrix.json`.",
  "- **Declared range** — `[minCompatibleApiVersion .. apiVersion]` the suite reports it accepts, read from an authenticated `/api/ping`. `—` when the image predates the field.",
  "- **In range** — whether the cell'"'"'s contract falls inside the declared range. `—` when no range was read.",
  "" ' "${IN}")"

# Optional second table: plugin↔suite verdicts from aggregate.sh (D6 aggregate).
aggregate_md=""
if [[ -f "${AGG}" ]] && [[ "$(jq '(.rows // []) | length' "${AGG}" 2>/dev/null || echo 0)" -gt 0 ]]; then
  aggregate_md="$(jq -r '
    "## Plugin ↔ suite compatibility (derived)",
    "",
    "Each **client** feed (e.g. `valtimo-epistola-plugin`) declares the single contract version it targets; a pairing is compatible when that target falls in the suite'"'"'s declared range (`\(.rule // "floor <= target <= apiVersion")`).",
    "",
    "| Client | Target contract | Suite | Suite range | Compatible |",
    "| --- | --- | --- | --- | --- |",
    ( .rows[]
      | "| \(.plugin) `\(.pluginVersion)` | `\(.targetContract)` | \(.suite) | `\(.suiteRange.min)` … `\(.suiteRange.max)` | \(if .compatible then "✅ yes" else "❌ no" end) — \(.reason) |"
    ),
    "" ' "${AGG}")"
fi

markdown="${markdown}${aggregate_md:+

${aggregate_md}}"

if [[ "${OUT}" == "-" ]]; then
  printf '%s\n' "${markdown}"
else
  mkdir -p "$(dirname -- "${OUT}")"
  printf '%s\n' "${markdown}" > "${OUT}"
  echo "[render] wrote ${OUT} ($(jq '.cells | length' "${IN}") cell(s)$([[ -n "${aggregate_md}" ]] && printf ', %s aggregate row(s)' "$(jq '.rows | length' "${AGG}")"))" >&2
fi
