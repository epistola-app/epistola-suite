#!/usr/bin/env bash
#
# Compatibility matrix — single-cell smoke runner (v0, foundation).
#
# Boots a FIXED (published) epistola-suite container image against a throwaway
# Postgres, waits for readiness, and makes one anonymous `POST /api/ping` smoke
# request. Records the outcome as one cell in the compatibility matrix JSON.
#
# A "cell" is the pair (suite version, contract version). See ./README.md for
# the design and the deliberate limits of this v0 (anonymous reachability only —
# it does not yet verify the wire contract version or cross-version client skew).
#
# Usage:
#   compatibility/smoke.sh
#   IMAGE=ghcr.io/acme/epistola-suite:latest compatibility/smoke.sh
#   compatibility/smoke.sh --image ghcr.io/acme/epistola-suite:1.0.0 \
#                          --suite 1.0.0 --contract 0.10.0 --out compatibility/matrix.json
#
# Inputs (flags override env; env overrides repo-derived defaults):
#   --image    IMAGE            suite container image to boot   (required)
#   --suite    SUITE_VERSION    suite version label for the cell (default: gradle.properties)
#   --contract CONTRACT_VERSION fallback contract label (default: libs.versions.toml).
#                               The image's bundled contract JAR version is read and
#                               used instead whenever it can be inspected.
#   --out      OUT              matrix JSON to update            (default: compatibility/matrix.json)
#   --profile  PROFILE          Spring profile(s) to boot with   (default: localauth)
#   --api-key  API_KEY          API key for the authenticated ping that reads the
#                               server's DECLARED compatibility range from
#                               /api/ping `.details` (default: the seeded demo key).
#
# The suite fails fast on boot unless an authentication mechanism is configured,
# so we boot with the `localauth` profile (form login, in-memory users). The
# reachability gate is an anonymous request, so the auth mechanism itself is never
# exercised — it just lets the app start. `prod` is deliberately avoided (it needs
# encryption keys and a pre-migrated DB).
#
# Declared-range verification (best effort): after the app is UP, we additionally
# make an *authenticated* /api/ping (the `.details` object, incl. `apiVersion` and
# `minCompatibleApiVersion`, is only returned when authenticated) and check the
# cell's contract version falls in the server's declared range
# `[minCompatibleApiVersion .. apiVersion]`. This requires (a) a suite image new
# enough to expose `minCompatibleApiVersion` and (b) a valid API key — the default
# demo key only exists when a demo-capable profile is active (e.g.
# `--profile localauth,demo`). When either is missing the cell still records
# reachability; the range fields are simply omitted (rangeVerified unset).
#
# Requires: docker, curl, jq.

set -euo pipefail

# --- locate repo + this harness ------------------------------------------------
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

# --- defaults, derived from the repo when not supplied -------------------------
IMAGE="${IMAGE:-}"
SUITE_VERSION="${SUITE_VERSION:-}"
CONTRACT_VERSION="${CONTRACT_VERSION:-}"
OUT="${OUT:-${SCRIPT_DIR}/matrix.json}"
PROFILE="${PROFILE:-localauth}"
# Seeded demo API key (DemoLoader, under a demo-capable profile). Used only for the
# authenticated ping that reads the declared compatibility range.
API_KEY="${API_KEY:-epk_demo_000000000000000000000000000000000000}"
CONTRACT_SOURCE="label"   # overwritten to "image" if we read it from the image

# How long (seconds) to keep trying the authenticated range read after the app is
# UP — the API key may seed well after the anonymous UP (e.g. the demo key lands
# only after the demo catalog import, ~60-90s). Set low to degrade fast when you
# know the image/profile will never expose the range.
RANGE_TIMEOUT="${RANGE_TIMEOUT:-120}"

# Declared compatibility range, read from an authenticated /api/ping when available.
# Empty when the image is too old to expose it or the authenticated ping fails.
DECLARED_MIN=""
DECLARED_API=""
RANGE_VERIFIED=""   # "true" | "false" | "" (not checked)

# --- flag parsing --------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --image)    IMAGE="$2";            shift 2 ;;
    --suite)    SUITE_VERSION="$2";    shift 2 ;;
    --contract) CONTRACT_VERSION="$2"; shift 2 ;;
    --out)      OUT="$2";              shift 2 ;;
    --profile)  PROFILE="$2";          shift 2 ;;
    --api-key)  API_KEY="$2";          shift 2 ;;
    -h|--help)  grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# --- derive version labels from the repo if still unset ------------------------
if [[ -z "${SUITE_VERSION}" && -f "${REPO_ROOT}/gradle.properties" ]]; then
  SUITE_VERSION="$(sed -n 's/^version=//p' "${REPO_ROOT}/gradle.properties" | head -1)"
fi
if [[ -z "${CONTRACT_VERSION}" && -f "${REPO_ROOT}/gradle/libs.versions.toml" ]]; then
  CONTRACT_VERSION="$(sed -n 's/^epistola-contract *= *"\(.*\)"/\1/p' "${REPO_ROOT}/gradle/libs.versions.toml" | head -1)"
fi

# --- validate ------------------------------------------------------------------
for tool in docker curl jq; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "missing required tool: ${tool}" >&2; exit 3; }
done
[[ -n "${IMAGE}" ]]            || { echo "no image given (use --image or IMAGE=...)" >&2; exit 2; }
[[ -n "${SUITE_VERSION}" ]]    || { echo "could not determine suite version (use --suite)" >&2; exit 2; }
[[ -n "${CONTRACT_VERSION}" ]] || { echo "could not determine contract version (use --contract)" >&2; exit 2; }

# --- unique names so parallel runs don't collide -------------------------------
RUN_ID="$$"
NET="epistola-compat-net-${RUN_ID}"
PG="epistola-compat-pg-${RUN_ID}"
SUITE="epistola-compat-suite-${RUN_ID}"
HOST_PORT="${HOST_PORT:-4000}"

cleanup() {
  docker rm -f "${SUITE}" >/dev/null 2>&1 || true
  docker rm -f "${PG}"    >/dev/null 2>&1 || true
  docker network rm "${NET}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

log() { echo "[compat] $*" >&2; }

# --- semver range helpers (numeric-aware via sort -V) --------------------------
# ver_core strips a build/pre-release qualifier (everything from the first `-`, e.g.
# `-compat-SNAPSHOT`, `-RC1`) so a snapshot/RC of X compares as X — released
# contract versions have no qualifier, so this only matters for local/CI snapshots.
# `sort -V` also ranks `0.10.0-x` ABOVE `0.10.0` (opposite of semver), which the
# stripping avoids. ver_in_range MIN VER MAX → true when MIN <= VER <= MAX.
ver_core() { printf '%s' "${1%%-*}"; }
ver_le() { [[ "$1" == "$2" ]] || [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n1)" == "$1" ]]; }
ver_in_range() { ver_le "$(ver_core "$1")" "$(ver_core "$2")" && ver_le "$(ver_core "$2")" "$(ver_core "$3")"; }

# --- read the server's DECLARED compatibility range (best effort) --------------
# Makes an authenticated /api/ping (the `.details` object is auth-gated) and, when
# the response carries `apiVersion` + `minCompatibleApiVersion`, sets DECLARED_API
# / DECLARED_MIN and RANGE_VERIFIED (whether this cell's contract is in range).
# Silent no-op when the image predates the field or the key is not accepted.
read_declared_range() {
  local body details api min waited=0
  log "reading declared range via authenticated /api/ping (up to ${RANGE_TIMEOUT}s for the key to seed)…"
  # The API key backing this read is seeded during startup (e.g. the demo key lands
  # only after the demo catalog import), so an authenticated ping can return 401 for
  # a while AFTER the anonymous UP. Poll until we actually authenticate (details
  # present), then decide — don't give up on the early 401s.
  while (( waited < RANGE_TIMEOUT )); do
    body="$(curl -s -X POST "${BASE}/api/ping" \
      -H 'Content-Type: application/vnd.epistola.v1+json' \
      -H 'Accept: application/vnd.epistola.v1+json' \
      -H "User-Agent: ${UA}" \
      -H "X-EP-Node-Id: ${NODE}" \
      -H "X-API-Key: ${API_KEY}" || true)"
    details="$(printf '%s' "${body}" | jq -r 'if (.details // null) == null then "" else "yes" end' 2>/dev/null || true)"
    if [[ -n "${details}" ]]; then
      # Authenticated: .details is populated. Decide now — no more waiting.
      api="$(printf '%s' "${body}" | jq -r '.details.apiVersion // empty' 2>/dev/null || true)"
      min="$(printf '%s' "${body}" | jq -r '.details.minCompatibleApiVersion // empty' 2>/dev/null || true)"
      if [[ -z "${min}" || "${min}" == "unknown" || -z "${api}" || "${api}" == "unknown" ]]; then
        log "authenticated, but no compatibility range in /api/ping details (image predates it) — recording reachability only"
        return 0
      fi
      DECLARED_API="${api}"
      DECLARED_MIN="${min}"
      if ver_in_range "${DECLARED_MIN}" "${CONTRACT_VERSION}" "${DECLARED_API}"; then
        RANGE_VERIFIED="true"
        log "declared range [${DECLARED_MIN} .. ${DECLARED_API}] ✓ contains contract ${CONTRACT_VERSION}"
      else
        RANGE_VERIFIED="false"
        log "declared range [${DECLARED_MIN} .. ${DECLARED_API}] ✗ EXCLUDES contract ${CONTRACT_VERSION}"
      fi
      return 0
    fi
    sleep 3; waited=$(( waited + 3 ))
  done
  log "declared range: no authenticated /api/ping within ${RANGE_TIMEOUT}s (no valid key) — recording reachability only"
}

# --- record a cell result and exit ---------------------------------------------
# args: result(pass|fail|error) detail
record() {
  local result="$1" detail="${2:-}" now
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  mkdir -p "$(dirname -- "${OUT}")"
  [[ -f "${OUT}" ]] || echo '{"schemaVersion":1,"anchor":"epistola-contract","generatedAt":null,"cells":[]}' > "${OUT}"

  local tmp; tmp="$(mktemp)"
  jq \
    --arg suite "${SUITE_VERSION}" \
    --arg contract "${CONTRACT_VERSION}" \
    --arg result "${result}" \
    --arg method "client-identity-ping" \
    --arg contractSource "${CONTRACT_SOURCE}" \
    --arg image "${IMAGE}" \
    --arg detail "${detail}" \
    --arg declaredMin "${DECLARED_MIN}" \
    --arg declaredApi "${DECLARED_API}" \
    --arg rangeVerified "${RANGE_VERIFIED}" \
    --arg now "${now}" '
    .generatedAt = $now
    | .cells = (
        [ .cells[]? | select(.suite != $suite or .contract != $contract) ]
        + [ ( { suite: $suite, contract: $contract, result: $result, method: $method,
                contractSource: $contractSource, image: $image, detail: $detail,
                verifiedAt: $now }
              # Attach the declared range only when an authenticated ping read it.
              | if $declaredApi != "" then . + {
                    declaredRange: { min: $declaredMin, max: $declaredApi },
                    rangeVerified: ($rangeVerified == "true")
                  } else . end ) ]
        | sort_by(.suite, .contract)
      )
    ' "${OUT}" > "${tmp}"
  mv "${tmp}" "${OUT}"

  log "cell (suite=${SUITE_VERSION}, contract=${CONTRACT_VERSION}) → ${result} — ${detail}"
  log "wrote ${OUT}"
  [[ "${result}" == "pass" ]] && exit 0 || exit 1
}

# --- boot -----------------------------------------------------------------------
log "cell: suite=${SUITE_VERSION}  contract=${CONTRACT_VERSION}"
log "image: ${IMAGE}"

docker network create "${NET}" >/dev/null

log "starting postgres…"
docker run -d --name "${PG}" --network "${NET}" \
  -e POSTGRES_DB=epistola -e POSTGRES_USER=epistola -e POSTGRES_PASSWORD=epistola \
  --tmpfs /var/lib/postgresql:rw \
  postgres:18 >/dev/null

log "waiting for postgres…"
for _ in $(seq 1 30); do
  if docker exec "${PG}" pg_isready -U epistola >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec "${PG}" pg_isready -U epistola >/dev/null 2>&1 || record error "postgres did not become ready"

log "starting suite (profile=${PROFILE}; embedded Flyway migrates on boot)…"
docker run -d --name "${SUITE}" --network "${NET}" -p "${HOST_PORT}:4000" \
  -e SPRING_PROFILES_ACTIVE="${PROFILE}" \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://${PG}:5432/epistola" \
  -e SPRING_DATASOURCE_USERNAME=epistola \
  -e SPRING_DATASOURCE_PASSWORD=epistola \
  "${IMAGE}" >/dev/null

# --- read the contract version from the image (authoritative) ------------------
# The contract JAR filename in BOOT-INF/lib is the source of truth. The runtime
# apiVersion from /api/ping is unreliable ("unknown" when the JAR manifest lacks
# Implementation-Version), so we read the version off the packaged artifact.
log "reading contract version from image…"
for _ in $(seq 1 10); do
  jar="$(docker exec "${SUITE}" sh -c 'ls /workspace/BOOT-INF/lib 2>/dev/null | grep -iE "^server-kotlin-springboot4-[0-9]"' 2>/dev/null | head -1 || true)"
  if [[ -n "${jar}" ]]; then
    image_contract="$(printf '%s' "${jar}" | sed -n 's/^server-kotlin-springboot4-\(.*\)\.jar$/\1/p')"
    if [[ -n "${image_contract}" ]]; then
      [[ -n "${CONTRACT_VERSION}" && "${CONTRACT_VERSION}" != "${image_contract}" ]] &&
        log "note: image contract ${image_contract} overrides label ${CONTRACT_VERSION}"
      CONTRACT_VERSION="${image_contract}"
      CONTRACT_SOURCE="image"
      break
    fi
  fi
  [[ "$(docker inspect -f '{{.State.Running}}' "${SUITE}" 2>/dev/null || echo false)" == "true" ]] || break
  sleep 2
done
log "contract=${CONTRACT_VERSION} (source=${CONTRACT_SOURCE})"

# --- smoke: poll a client-identity POST /api/ping until it reports UP -----------
# This IS the readiness signal too: /readyz and /livez are not reliably 200 across
# versions (some redirect to a login page), whereas /api/ping with client-identity
# headers returns {"status":"UP"} once the app is serving. Older suites also REQUIRE
# these headers on /api/ping (400 without them), so we always send them.
BASE="http://localhost:${HOST_PORT}"
SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-180}"
NODE="compat-smoke-${RUN_ID}"
UA="epistola-contract/${CONTRACT_VERSION:-0.0.0} compat-smoke"
log "smoke: polling POST /api/ping (User-Agent: ${UA}) up to ${SMOKE_TIMEOUT}s…"
PING=""
for _ in $(seq 1 "$(( SMOKE_TIMEOUT / 3 ))"); do
  PING="$(curl -s -X POST "${BASE}/api/ping" \
    -H 'Content-Type: application/vnd.epistola.v1+json' \
    -H 'Accept: application/vnd.epistola.v1+json' \
    -H "User-Agent: ${UA}" \
    -H "X-EP-Node-Id: ${NODE}" || true)"
  if [[ "$(printf '%s' "${PING}" | jq -r '.status // empty' 2>/dev/null || true)" == "UP" ]]; then
    read_declared_range
    if [[ "${RANGE_VERIFIED}" == "false" ]]; then
      record fail "server declares range [${DECLARED_MIN} .. ${DECLARED_API}] which excludes bundled contract ${CONTRACT_VERSION}"
    fi
    pass_detail="POST /api/ping reported UP (with client-identity headers)"
    [[ "${RANGE_VERIFIED}" == "true" ]] &&
      pass_detail="${pass_detail}; declared range [${DECLARED_MIN} .. ${DECLARED_API}] verified"
    record pass "${pass_detail}"
  fi
  # bail early if the container has already died
  if [[ "$(docker inspect -f '{{.State.Running}}' "${SUITE}" 2>/dev/null || echo false)" != "true" ]]; then
    log "suite container exited during boot; recent logs:"
    docker logs --tail 40 "${SUITE}" >&2 || true
    record error "suite container exited during boot"
  fi
  sleep 3
done
docker logs --tail 40 "${SUITE}" >&2 || true
record fail "no UP from /api/ping within ${SMOKE_TIMEOUT}s; last response: ${PING:-<empty>}"
