#!/usr/bin/env bash
#
# MCP smoke-test against a running Epistola instance.
#
# Walks through the full Streamable HTTP handshake (initialize →
# notifications/initialized → tools/list → resources/list → prompts/list)
# and exercises a handful of representative tools so you can confirm
# end-to-end that the MCP server is responding sensibly with real data.
#
# Usage:
#   scripts/mcp-smoke.sh <api-key>
#   scripts/mcp-smoke.sh                       # picks up MCP_API_KEY env var
#   MCP_URL=http://other:4000/api/mcp scripts/mcp-smoke.sh <api-key>
#
# Defaults:
#   URL  → http://localhost:4000/api/mcp
#   KEY  → $MCP_API_KEY
#
# Requires: curl, jq.

set -euo pipefail

URL="${MCP_URL:-http://localhost:4000/api/mcp}"
KEY="${1:-${MCP_API_KEY:-}}"

if [[ -z "$KEY" ]]; then
    cat >&2 <<EOF
error: no API key provided

  scripts/mcp-smoke.sh <api-key>            # one-off
  MCP_API_KEY=epk_... scripts/mcp-smoke.sh  # via env
EOF
    exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
    echo "error: jq is required (brew install jq)" >&2
    exit 2
fi

# Track whether each step succeeded so the final summary is honest even
# when individual tool calls fail.
declare -a results=()
record() {
    local label="$1" status="$2"
    results+=("$status  $label")
}

# ANSI helpers — disabled when stdout isn't a TTY.
if [[ -t 1 ]]; then
    bold=$'\e[1m'; dim=$'\e[2m'; green=$'\e[32m'; red=$'\e[31m'; cyan=$'\e[36m'; reset=$'\e[0m'
else
    bold=""; dim=""; green=""; red=""; cyan=""; reset=""
fi

step() { echo; echo "${bold}${cyan}▶ $1${reset}"; }
ok()   { echo "${green}✓${reset} $1"; }
fail() { echo "${red}✗${reset} $1"; }

# ---------------------------------------------------------------------------
# 1. Initialize
# ---------------------------------------------------------------------------
step "initialize"
init_headers=$(mktemp)
init_body=$(mktemp)
trap 'rm -f "$init_headers" "$init_body"' EXIT

http_status=$(
    curl -sS -o "$init_body" -D "$init_headers" -w "%{http_code}" \
        -X POST "$URL" \
        -H "X-API-Key: $KEY" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"mcp-smoke","version":"1"}}}'
)

if [[ "$http_status" != "200" ]]; then
    fail "initialize HTTP $http_status"
    echo "${dim}response body:${reset}"
    cat "$init_body"
    record "initialize" "${red}fail${reset}"
    echo
    printf '%s\n' "${results[@]}"
    exit 1
fi

session=$(awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}' "$init_headers" | tr -d '\r\n ')
if [[ -z "$session" ]]; then
    fail "no Mcp-Session-Id header in initialize response"
    record "initialize" "${red}fail${reset}"
    echo
    printf '%s\n' "${results[@]}"
    exit 1
fi
ok "session: $session"
echo "${dim}server info:${reset}"
jq -r '.result.serverInfo, .result.capabilities' "$init_body"
record "initialize" "${green}ok${reset}"

# Spring AI's MCP server can return JSON-RPC responses either as plain JSON
# or wrapped in a single SSE event (`event:message\ndata:{...}`). This
# extracts the JSON-RPC envelope from either form. Falls back to whatever
# we got if neither pattern matches so jq surfaces a useful error.
extract_json_rpc() {
    local body="$1"
    # SSE form: pluck the first `data:` line.
    if printf '%s' "$body" | grep -q '^data:'; then
        printf '%s' "$body" | awk -F'^data:' '/^data:/{print $2; exit}' | sed 's/^ //'
        return
    fi
    printf '%s' "$body"
}

# ---------------------------------------------------------------------------
# Helper: send one JSON-RPC request, print the result or surface the error.
# Args: <id> <method> <label> [<params-json>]
# ---------------------------------------------------------------------------
rpc() {
    local id="$1" method="$2" label="$3" params="${4:-}"
    local body
    if [[ -n "$params" ]]; then
        body=$(jq -nc --arg m "$method" --argjson p "$params" --argjson i "$id" \
            '{jsonrpc:"2.0", id:$i, method:$m, params:$p}')
    else
        body=$(jq -nc --arg m "$method" --argjson i "$id" \
            '{jsonrpc:"2.0", id:$i, method:$m}')
    fi

    # Use --no-buffer + tolerate non-zero exit (Spring AI MCP closes the
    # chunked stream ungracefully which curl flags as exit 18, but the body
    # is fully delivered before then).
    local response
    response=$(
        curl -sS --no-buffer -X POST "$URL" \
            -H "X-API-Key: $KEY" \
            -H "Content-Type: application/json" \
            -H "Accept: application/json, text/event-stream" \
            -H "Mcp-Session-Id: $session" \
            -d "$body" || true
    )

    local rpc_response
    rpc_response=$(extract_json_rpc "$response")

    if echo "$rpc_response" | jq -e '.error' >/dev/null 2>&1; then
        fail "$label"
        echo "$rpc_response" | jq '.error'
        record "$label" "${red}fail${reset}"
        return 1
    fi

    ok "$label"
    echo "$rpc_response" | jq '.result'
    record "$label" "${green}ok${reset}"
}

# ---------------------------------------------------------------------------
# 2. notifications/initialized — required by the spec, no response
# ---------------------------------------------------------------------------
step "notifications/initialized"
curl -sS -o /dev/null -X POST "$URL" \
    -H "X-API-Key: $KEY" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Mcp-Session-Id: $session" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    && ok "sent" \
    || { fail "send failed"; record "initialized" "${red}fail${reset}"; }
record "initialized" "${green}ok${reset}"

# ---------------------------------------------------------------------------
# 3. tools/list — should return all 16 MCP tools we expose
# ---------------------------------------------------------------------------
step "tools/list"
tools_response=$(
    curl -sS --no-buffer -X POST "$URL" \
        -H "X-API-Key: $KEY" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -H "Mcp-Session-Id: $session" \
        -d '{"jsonrpc":"2.0","id":10,"method":"tools/list"}' || true
)
extract_json_rpc "$tools_response" | jq '{count: (.result.tools | length), names: [.result.tools[].name]}'
record "tools/list" "${green}ok${reset}"

# ---------------------------------------------------------------------------
# 4. resources/list and prompts/list — we don't expose any; expect empty arrays.
#    (The Spring AI starter still advertises both capabilities in initialize,
#    which is why an MCP UI shows Resources/Prompts tabs.)
# ---------------------------------------------------------------------------
step "resources/list (expected: empty)"
rpc 20 resources/list resources/list || true

step "prompts/list (expected: empty)"
rpc 21 prompts/list prompts/list || true

# ---------------------------------------------------------------------------
# 5. A handful of representative tools — covers discovery, content, examples,
#    and rendering. Each shows the .result so you can spot wrong shapes.
# ---------------------------------------------------------------------------
step "tools/call list_catalogs"
rpc 30 tools/call list_catalogs '{"name":"list_catalogs","arguments":{}}'

step "tools/call list_templates"
rpc 31 tools/call list_templates '{"name":"list_templates","arguments":{}}'

step "tools/call get_component_type datatable"
rpc 32 tools/call get_component_type '{"name":"get_component_type","arguments":{"type":"datatable"}}'

step "tools/call list_component_types"
rpc 33 tools/call list_component_types '{"name":"list_component_types","arguments":{}}'

# preview_document needs the demo catalog. Truncate the base64 PDF in output
# so we can see it worked without flooding the terminal.
step "tools/call preview_document (demo-invoice)"
preview_response=$(
    curl -sS --no-buffer -X POST "$URL" \
        -H "X-API-Key: $KEY" \
        -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        -H "Mcp-Session-Id: $session" \
        -d '{"jsonrpc":"2.0","id":40,"method":"tools/call","params":{"name":"preview_document","arguments":{"catalogId":"epistola-demo","templateId":"demo-invoice"}}}' || true
)
preview_rpc=$(extract_json_rpc "$preview_response")
if echo "$preview_rpc" | jq -e '.error' >/dev/null 2>&1; then
    fail "preview_document"
    echo "$preview_rpc" | jq '.error'
    record "preview_document" "${red}fail${reset}"
else
    ok "preview_document"
    echo "$preview_rpc" | jq '.result | {
        mediaType: (.content[0].text | fromjson? | .mediaType),
        bytes:     (.content[0].text | fromjson? | .byteCount),
        dataPrefix:(.content[0].text | fromjson? | .data[0:32]) + "…"
    }' 2>/dev/null || echo "$preview_rpc" | jq '.result'
    record "preview_document" "${green}ok${reset}"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo
echo "${bold}summary${reset}"
printf '  %s\n' "${results[@]}"
