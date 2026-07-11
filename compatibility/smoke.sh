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
#
# The suite fails fast on boot unless an authentication mechanism is configured,
# so we boot with the `localauth` profile (form login, in-memory users). We only
# make an anonymous request, so the auth mechanism itself is never exercised —
# it just lets the app start. `prod` is deliberately avoided (it needs encryption
# keys and a pre-migrated DB).
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
CONTRACT_SOURCE="label"   # overwritten to "image" if we read it from the image

# --- flag parsing --------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --image)    IMAGE="$2";            shift 2 ;;
    --suite)    SUITE_VERSION="$2";    shift 2 ;;
    --contract) CONTRACT_VERSION="$2"; shift 2 ;;
    --out)      OUT="$2";              shift 2 ;;
    --profile)  PROFILE="$2";          shift 2 ;;
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
    --arg now "${now}" '
    .generatedAt = $now
    | .cells = (
        [ .cells[]? | select(.suite != $suite or .contract != $contract) ]
        + [ { suite: $suite, contract: $contract, result: $result, method: $method,
              contractSource: $contractSource, image: $image, detail: $detail,
              verifiedAt: $now } ]
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
    record pass "POST /api/ping reported UP (with client-identity headers)"
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
