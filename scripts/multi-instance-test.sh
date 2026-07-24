#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

# Multi-instance (3-node) local cluster test for Epistola Suite.
#
# Runs three app instances against one shared Postgres with a round-robin
# proxy in front, then verifies the cluster seams: node heartbeats,
# round-robin serving, shared JDBC sessions, load-test job distribution,
# exactly-once generation, REST write/read consistency, and (optionally)
# SIGKILL chaos with stale-job recovery and API-key cache staleness.
#
# Usage:
#   scripts/multi-instance-test.sh up          # build (unless SKIP_BUILD=1), start postgres + 3 nodes + proxy
#   scripts/multi-instance-test.sh verify      # phase 1+2 checks (cluster, round-robin, session)
#   scripts/multi-instance-test.sh loadtest [N]  # run embedded load test (default 900 docs) + assertions
#   scripts/multi-instance-test.sh chaos       # SIGKILL a worker mid-load-test, assert recovery
#   scripts/multi-instance-test.sh staleness   # measure API-key revocation staleness across nodes
#   scripts/multi-instance-test.sh down        # stop everything
#
# Notes learned from the first run (2026-07-10):
# - Postgres runs DISK-BACKED here, not the dev compose tmpfs: a 10k-doc load
#   test writes more WAL+data than the tmpfs default and kills the database.
# - Load-test stale-run recovery now keys off a progress heartbeat
#   (load_test_runs.last_progress_at, stamped every ~500ms), not claim age, so a
#   short epistola.loadtest.polling.stale-timeout-minutes no longer re-executes a
#   healthy long run (the duplicate-batch bug #725 is fixed).
# - Chaos kills use SIGKILL: SIGTERM lets JobPoller release its claims
#   gracefully, which defeats the recovery test.

set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${MIT_WORKDIR:-/tmp/epistola-multi-instance}"
JAR_DIR="$REPO/apps/epistola/build/libs"
PORTS=(4000 4010 4020)
NODES=(node-a node-b node-c)
LB_PORT=4444
LB="http://127.0.0.1:$LB_PORT"
PGPORT=4001
DEMO_KEY="epk_demo_000000000000000000000000000000000000"
VND="application/vnd.epistola.v1+json"
COMMON_ARGS=(
  --spring.profiles.active=local
  --epistola.support.enabled=false
  --spring.session.timeout=4h
  --epistola.generation.polling.stale-timeout-minutes=1
)

mkdir -p "$WORK"

psq() { PGPASSWORD=epistola psql -h 127.0.0.1 -p $PGPORT -U epistola -d epistola -tAc "$1"; }
api() { curl -s -H "X-API-Key: $DEMO_KEY" -H "Content-Type: $VND" -H "Accept: $VND" "$@"; }
die() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "PASS: $*"; }

wait_ready() { # port
  for _ in $(seq 1 120); do
    curl -sf "http://127.0.0.1:$1/readyz" >/dev/null 2>&1 && return 0
    sleep 1
  done
  die "port $1 never became ready"
}

node_pid() { # index
  cat "$WORK/${NODES[$1]}.pid" 2>/dev/null || true
}

# Resolve the bootable fat jar. `bootJar` also emits an `epistola-*-plain.jar`
# (the plain library jar, no Main-Class) — feeding that to `java -jar` fails with
# "no main manifest attribute", so exclude it explicitly.
boot_jar() {
  ls "$JAR_DIR"/epistola-*.jar 2>/dev/null | grep -v -- '-plain\.jar' | head -1
}

start_node() { # index
  local i=$1 port=${PORTS[$1]} name=${NODES[$1]}
  local jar; jar="$(boot_jar)"
  [ -n "$jar" ] || die "no bootable jar in $JAR_DIR (build first; the -plain jar is not bootable)"
  (cd "$REPO" && java -jar "$jar" "${COMMON_ARGS[@]}" \
    --server.port="$port" --epistola.node-id="$name" \
    > "$WORK/$name.log" 2>&1 &)
  wait_ready "$port"
  # Record the JVM's *own* pid, resolved by its unique node-id marker. Capturing
  # $! of the backgrounded `cd && java` subshell recorded the wrong pid (the
  # subshell/launcher, not the JVM), so `down` and the chaos victim-selection
  # targeted a pid that no longer existed — leaving orphaned nodes squatting on
  # the ports. This is the pid that cluster_nodes / claimed_by also report.
  pgrep -f "epistola.node-id=$name" | head -1 > "$WORK/$name.pid" || true
  echo "$name up (pid $(node_pid "$i"), port $port)"
}

write_lb() {
  cat > "$WORK/lb.mjs" <<'EOF'
import http from "node:http";
const listenPort = Number(process.argv[2]);
const backends = process.argv[3].split(",").map(Number);
let counter = 0;
http.createServer((req, res) => {
  const backend = backends[counter++ % backends.length];
  const p = http.request({ host: "127.0.0.1", port: backend, method: req.method, path: req.url, headers: req.headers }, (pr) => {
    console.log(`${req.method} ${req.url} -> ${backend} ${pr.statusCode}`);
    res.writeHead(pr.statusCode, { ...pr.headers, "x-lb-backend": String(backend) });
    pr.pipe(res);
  });
  p.on("error", (err) => {
    console.log(`${req.method} ${req.url} -> ${backend} ERROR ${err.code ?? err.message}`);
    if (!res.headersSent) res.writeHead(502, { "x-lb-backend": String(backend) });
    res.end(`lb: backend ${backend} unavailable`);
  });
  req.pipe(p);
}).listen(listenPort, "127.0.0.1", () => console.log(`lb on ${listenPort} -> ${backends.join(",")}`));
EOF
}

login() {
  rm -f "$WORK/cj.txt"
  curl -s -c "$WORK/cj.txt" "$LB/login" -o /dev/null
  local xsrf
  xsrf=$(awk '$6=="XSRF-TOKEN"{print $7}' "$WORK/cj.txt")
  local code
  code=$(curl -s -b "$WORK/cj.txt" -c "$WORK/cj.txt" -H "X-XSRF-TOKEN: $xsrf" \
    -d 'username=admin@local' -d 'password=admin' -o /dev/null -w '%{http_code}' "$LB/login")
  [ "$code" = "302" ] || die "login returned $code"
}

ui_post() { # url form-args...
  local url=$1; shift
  local xsrf
  xsrf=$(awk '$6=="XSRF-TOKEN"{print $7}' "$WORK/cj.txt")
  curl -s -b "$WORK/cj.txt" -c "$WORK/cj.txt" -H "X-XSRF-TOKEN: $xsrf" "$@" "$url"
}

start_loadtest() { # targetCount -> echoes run id
  local target=$1
  local run_url
  run_url=$(ui_post "$LB/tenants/demo/load-tests" -o /dev/null -w '%{redirect_url}' \
    --data-urlencode 'templateId=epistola-demo/hello-world' \
    --data-urlencode 'variantId=default' \
    --data-urlencode 'versionId=1' \
    --data-urlencode "targetCount=$target" \
    --data-urlencode 'testData={"companyName":"Globex","name":"World","message":"multi-instance test"}')
  [ -n "$run_url" ] || die "load test start returned no redirect"
  echo "${run_url##*/}"
}

cmd_up() {
  command -v psql >/dev/null || die "psql not found (brew install libpq or postgresql)"
  if [ "${SKIP_BUILD:-0}" != "1" ]; then
    (cd "$REPO" && pnpm install && pnpm build && ./gradlew :apps:epistola:bootJar)
  fi
  # Disk-backed postgres (NOT the dev compose tmpfs — see header note).
  docker rm -f epistola-postgres >/dev/null 2>&1 || true
  docker run -d --name epistola-postgres \
    -e POSTGRES_DB=epistola -e POSTGRES_USER=epistola -e POSTGRES_PASSWORD=epistola \
    -p $PGPORT:5432 postgres:18 >/dev/null
  until docker exec epistola-postgres pg_isready -U epistola >/dev/null 2>&1; do sleep 1; done
  echo "postgres ready (disk-backed, port $PGPORT)"
  start_node 0   # first node alone: lets DemoLoader seed without a race
  start_node 1
  start_node 2
  write_lb
  (nohup node "$WORK/lb.mjs" $LB_PORT "$(IFS=,; echo "${PORTS[*]}")" > "$WORK/lb.log" 2>&1 & echo $! > "$WORK/lb.pid")
  sleep 1
  curl -sf "$LB/livez" >/dev/null || die "proxy not serving"
  echo "proxy up on $LB_PORT"
}

cmd_verify() {
  local fresh
  fresh=$(psq "SELECT string_agg(node_id, ',' ORDER BY node_id) FROM cluster_nodes WHERE last_seen_at > now() - interval '10 seconds'")
  [ "$fresh" = "node-a,node-b,node-c" ] || die "fresh cluster nodes: $fresh"
  ok "cluster formed: $fresh"

  local seen=""
  for _ in 1 2 3 4 5 6; do
    seen+=" $(api -X POST -d '{}' "$LB/api/ping" | sed -E 's/.*"nodeId":"([^"]+)".*/\1/')"
  done
  for n in "${NODES[@]}"; do
    [ "$(grep -o "$n" <<<"$seen" | wc -l | tr -d ' ')" = "2" ] || die "round-robin uneven: $seen"
  done
  ok "round-robin: each node served exactly 2 of 6 pings"

  login
  for port in "${PORTS[@]}"; do
    local code
    code=$(curl -s -b "$WORK/cj.txt" -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/tenants/demo/load-tests")
    [ "$code" = "200" ] || die "session not honored on port $port ($code)"
  done
  ok "one session honored by all three backends (JDBC session store)"
}

assert_loadtest() { # runId targetCount minClaimers
  local run=$1 target=$2 min_claimers=$3 status
  for _ in $(seq 1 120); do
    status=$(psq "SELECT status FROM load_test_runs WHERE id='$run'")
    [ "$status" = "COMPLETED" ] && break
    [ "$status" = "FAILED" ] && die "load test $run FAILED"
    sleep 5
  done
  [ "$status" = "COMPLETED" ] || die "load test $run not completed in time (status $status)"

  local batch claimers other dup
  batch=$(psq "SELECT batch_id FROM load_test_runs WHERE id='$run'")
  claimers=$(psq "SELECT count(DISTINCT claimed_by) FROM document_generation_requests WHERE batch_id='$batch'")
  [ "$claimers" -ge "$min_claimers" ] || die "only $claimers claimer(s) processed the batch (expected >= $min_claimers)"
  ok "batch processed by $claimers instance(s)"
  other=$(psq "SELECT count(*) FROM document_generation_requests WHERE batch_id='$batch' AND status <> 'COMPLETED'")
  [ "$other" = "0" ] || die "$other requests not COMPLETED"
  ok "$target/$target requests COMPLETED"
  dup=$(psq "SELECT count(*) FROM (SELECT document_key FROM document_generation_requests WHERE batch_id='$batch' GROUP BY document_key HAVING count(*) > 1 OR document_key IS NULL) d")
  [ "$dup" = "0" ] || die "$dup duplicate/null document keys"
  ok "exactly-once: no duplicate or missing documents"
  psq "SELECT 'metrics: rps=' || round(requests_per_second::numeric,1) || ' p50=' || p50_response_time_ms || 'ms p95=' || p95_response_time_ms || 'ms p99=' || p99_response_time_ms || 'ms' FROM load_test_runs WHERE id='$run'"
}

cmd_loadtest() {
  local target=${1:-900}
  login
  local run
  run=$(start_loadtest "$target")
  echo "load test run: $run ($target docs)"
  # Cross-node distribution is bounded by the generation poll interval: the
  # submitting node drains its own batch in-process immediately, while peers only
  # notice new work on their next scheduled poll (~5s). A small, fast batch is
  # therefore swept by one node before peers poll — so the smoke asserts only the
  # real invariants (completion + exactly-once) and reports the spread for info.
  # Distribution is asserted hard on the 10k chaos run, which outlasts a poll cycle.
  # (A LISTEN/NOTIFY drain-wakeup would make spreading prompt regardless of size.)
  assert_loadtest "$run" "$target" 1
}

cmd_chaos() {
  login
  local run
  run=$(start_loadtest 10000)
  echo "chaos run: $run (10000 docs)"
  local claimer=""
  for _ in $(seq 1 60); do
    claimer=$(psq "SELECT claimed_by FROM load_test_runs WHERE id='$run'")
    [ -n "$claimer" ] && break
    sleep 0.5
  done
  local claimer_pid=${claimer##*-} victim="" victim_idx=""
  for i in 0 1 2; do
    if [ "$(node_pid "$i")" != "$claimer_pid" ]; then victim=$(node_pid "$i"); victim_idx=$i; break; fi
  done
  # Poll at 1s, not 0.2s: a 10k-doc batch keeps jobs in-flight for minutes, so 1s
  # catches the victim mid-flight just fine, and the slower cadence keeps the DB
  # connection rate well under Podman's port-forwarder limits (a 0.2s loop opening a
  # fresh psql connection each tick exhausts loopback ephemeral ports mid-run).
  local batch=""
  for _ in $(seq 1 120); do
    batch=$(psq "SELECT batch_id FROM load_test_runs WHERE id='$run'")
    if [ -n "$batch" ]; then
      local n
      n=$(psq "SELECT count(*) FROM document_generation_requests WHERE batch_id='$batch' AND status='IN_PROGRESS' AND claimed_by LIKE '%-$victim'")
      if [ "${n:-0}" -gt 0 ]; then
        kill -9 "$victim"
        echo "SIGKILLED ${NODES[$victim_idx]} (pid $victim) holding $n in-flight jobs"
        break
      fi
    fi
    sleep 1
  done
  # Recovery: stale threshold 1 min + recovery task cadence 60s -> allow 5 min.
  local orphans=-1
  for _ in $(seq 1 30); do
    orphans=$(psq "SELECT count(*) FROM document_generation_requests WHERE batch_id='$batch' AND status='IN_PROGRESS' AND claimed_by LIKE '%-$victim'")
    echo "orphaned in-flight jobs of dead node: $orphans"
    [ "$orphans" = "0" ] && break
    sleep 10
  done
  [ "$orphans" = "0" ] || die "orphaned claims never recovered"
  ok "stale-job recovery re-queued the dead node's claims"
  assert_loadtest "$run" 10000 2
  start_node "$victim_idx"
  cmd_verify
}

cmd_staleness() {
  login
  ui_post "$LB/tenants/demo/api-keys" -o "$WORK/key-created.html" \
    --data-urlencode 'name=cache-test' --data-urlencode 'roles=CONTENT_VIEWER' --data-urlencode 'expiresAt=' >/dev/null
  local key kid t0
  key=$(grep -oE 'epk_[A-Za-z0-9_]+' "$WORK/key-created.html" | head -1)
  [ -n "$key" ] || die "could not scrape created key"
  for port in "${PORTS[@]}"; do
    curl -s -H "X-API-Key: $key" -o /dev/null "http://127.0.0.1:$port/api/tenants/demo"
  done
  kid=$(psq "SELECT id FROM api_keys WHERE name='cache-test' ORDER BY created_at DESC LIMIT 1")
  t0=$(date +%s)
  ui_post "http://127.0.0.1:${PORTS[0]}/tenants/demo/api-keys/$kid/delete" -X POST -o /dev/null >/dev/null
  echo "revoked on ${NODES[0]} at t=0; polling others for 401 (expect <= ~60s cache TTL)"
  for port in "${PORTS[@]:1}"; do
    while :; do
      local code t
      code=$(curl -s -H "X-API-Key: $key" -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/tenants/demo")
      t=$(( $(date +%s) - t0 ))
      if [ "$code" = "401" ] || [ "$t" -gt 120 ]; then echo "port $port -> $code after ${t}s"; break; fi
      sleep 1
    done
  done
}

cmd_down() {
  for i in 0 1 2; do
    local p; p=$(node_pid "$i"); [ -n "$p" ] && kill "$p" 2>/dev/null || true
    rm -f "$WORK/${NODES[$i]}.pid"
  done
  # Backstop: reap any node JVM this harness started whose pidfile drifted,
  # matched by our own --epistola.node-id markers so unrelated java is never hit.
  sleep 1
  pkill -9 -f "epistola.node-id=node-" 2>/dev/null || true
  [ -f "$WORK/lb.pid" ] && { kill "$(cat "$WORK/lb.pid")" 2>/dev/null || true; rm -f "$WORK/lb.pid"; }
  pkill -f "$WORK/lb.mjs" 2>/dev/null || true
  docker rm -f epistola-postgres >/dev/null 2>&1 || true
  echo "all down (logs kept in $WORK)"
}

case "${1:-}" in
  up) cmd_up ;;
  verify) cmd_verify ;;
  loadtest) cmd_loadtest "${2:-900}" ;;
  chaos) cmd_chaos ;;
  staleness) cmd_staleness ;;
  down) cmd_down ;;
  all) cmd_up; cmd_verify; cmd_loadtest 900; cmd_chaos; cmd_staleness; cmd_down ;;
  *) grep '^#' "$0" | head -30; exit 1 ;;
esac
