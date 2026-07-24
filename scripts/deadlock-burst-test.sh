#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

# Concurrent-render classloader-deadlock repro for #724.
#
# Boots a single app node from the BOOT FAT JAR, fires a burst of concurrent PDF
# renders (a large load test), and checks the node drains them instead of
# wedging. Repeats N times, each on a fresh node id. Exits non-zero if any
# attempt wedges.
#
# Why this is a script and not a JUnit test: the deadlock only manifests with
# BOTH (a) the Spring Boot fat-jar nested-jar loader AND (b) a concurrent cold
# first-load burst. Neither is present when tests run from the exploded
# classpath, so a unit/integration test physically cannot reproduce it — the
# repro has to run the real fat jar. See docs/cluster-resilience.md and #724.
#
# The fix under test: JobPoller renders on PLATFORM threads, not virtual threads
# (a virtual thread can unmount while holding the nested-jar loader monitor —
# JEP 491 — and deadlock against the JVM class-load lock). If someone refactors
# generation back onto virtual threads, attempts here wedge and this exits 1.
#
# Usage:
#   scripts/deadlock-burst-test.sh [attempts] [docs]   # defaults: 10 attempts, 2000 docs
#   SKIP_BUILD=1 scripts/deadlock-burst-test.sh         # reuse an already-built fat jar
#
# Requires: docker (Postgres), psql, and — unless SKIP_BUILD=1 — a frontend build
# (pnpm) + JDK. Uses the `local` Spring profile, whose datasource points at
# 127.0.0.1:4001, so it starts Postgres there. Do not run concurrently with
# scripts/multi-instance-test.sh (same port/profile) or a local bootRun.

set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${DBT_WORKDIR:-/tmp/epistola-deadlock-burst}"
JAR_DIR="$REPO/apps/epistola/build/libs"
PGPORT=4001
PORT=4000
PG_NAME=epistola-deadlock-pg
ATTEMPTS="${1:-10}"
DOCS="${2:-2000}"
STALL_SECS=90 # no completed_count progress for this long while RUNNING = wedge
JCMD="$(dirname "$(readlink -f "$(command -v java)")")/jcmd"

mkdir -p "$WORK"

psq() { PGPASSWORD=epistola psql -h 127.0.0.1 -p $PGPORT -U epistola -d epistola -tAc "$1"; }
die() { echo "FATAL: $*" >&2; exit 2; }
# Resolve the bootable fat jar. `bootJar` also emits `epistola-*-plain.jar` (the
# plain library jar, no Main-Class) — `java -jar` on that fails, so exclude it.
boot_jar() { ls "$JAR_DIR"/epistola-*.jar 2>/dev/null | grep -v -- '-plain\.jar' | head -1; }
now() { date +%s; }

# Runs one burst attempt on a fresh node id. Returns 0 if the batch drains,
# 1 if it wedges (captures a thread dump), 2 on infra/run failure.
run_attempt() { # attempt-number
  local n=$1
  local name="dbt-$n" jar pid
  jar="$(boot_jar)"; [ -n "$jar" ] || die "no bootable fat jar in $JAR_DIR"

  (cd "$REPO" && java -jar "$jar" \
    --spring.profiles.active=local --epistola.support.enabled=false \
    --server.port=$PORT --epistola.node-id="$name" \
    --epistola.generation.polling.max-concurrent-jobs=20 \
    > "$WORK/$name.log" 2>&1 &)
  local up=0
  for _ in $(seq 1 120); do curl -sf "http://127.0.0.1:$PORT/readyz" >/dev/null 2>&1 && { up=1; break; }; sleep 1; done
  pid=$(pgrep -f "epistola.node-id=$name" | head -1)
  [ "$up" = 1 ] && [ -n "$pid" ] || { echo "  attempt $n: node never became ready"; [ -n "${pid:-}" ] && kill -9 "$pid" 2>/dev/null; return 2; }

  # Log in (UI session) and fire the load test.
  rm -f "$WORK/cj.txt"
  curl -s -c "$WORK/cj.txt" "http://127.0.0.1:$PORT/login" -o /dev/null
  local xsrf; xsrf=$(awk '$6=="XSRF-TOKEN"{print $7}' "$WORK/cj.txt")
  curl -s -b "$WORK/cj.txt" -c "$WORK/cj.txt" -H "X-XSRF-TOKEN: $xsrf" \
    -d 'username=admin@local' -d 'password=admin' -o /dev/null "http://127.0.0.1:$PORT/login"
  xsrf=$(awk '$6=="XSRF-TOKEN"{print $7}' "$WORK/cj.txt")
  local run_url run
  run_url=$(curl -s -b "$WORK/cj.txt" -c "$WORK/cj.txt" -H "X-XSRF-TOKEN: $xsrf" \
    --data-urlencode 'templateId=epistola-demo/hello-world' --data-urlencode 'variantId=default' \
    --data-urlencode 'versionId=1' --data-urlencode "targetCount=$DOCS" \
    --data-urlencode 'testData={"companyName":"Globex","name":"World","message":"deadlock burst"}' \
    -o /dev/null -w '%{redirect_url}' "http://127.0.0.1:$PORT/tenants/demo/load-tests")
  run="${run_url##*/}"
  [ -n "$run" ] || { echo "  attempt $n: load test did not start"; kill -9 "$pid" 2>/dev/null; return 2; }

  # Poll completion. A wedge shows as completed_count frozen while status is
  # still RUNNING; the classloader deadlock parks the whole node, so nothing
  # advances. A healthy node drains steadily to target.
  local last=0 last_change status completed rc
  last_change=$(now)
  while :; do
    status=$(psq "SELECT status FROM load_test_runs WHERE id='$run'")
    completed=$(psq "SELECT completed_count FROM load_test_runs WHERE id='$run'"); completed=${completed:-0}
    if [ "$status" = "COMPLETED" ]; then echo "  attempt $n: PASS (drained $completed/$DOCS)"; rc=0; break; fi
    if [ "$status" = "FAILED" ]; then echo "  attempt $n: run FAILED (app error, not a wedge)"; rc=2; break; fi
    if [ "$completed" -gt "$last" ]; then last=$completed; last_change=$(now); fi
    if [ $(( $(now) - last_change )) -ge $STALL_SECS ]; then
      echo "  attempt $n: WEDGE — completed_count frozen at $last/$DOCS for ${STALL_SECS}s"
      "$JCMD" "$pid" Thread.print > "$WORK/$name-threaddump.txt" 2>/dev/null || true
      local sig
      sig=$(grep -cE "UrlNestedJarFile|ClassLoader\.loadClass" "$WORK/$name-threaddump.txt" 2>/dev/null || true)
      echo "         nested-jar-loader / loadClass frames in dump: ${sig:-0}  ->  $WORK/$name-threaddump.txt"
      rc=1; break
    fi
    sleep 2
  done

  kill -9 "$pid" 2>/dev/null || true
  sleep 1
  return "$rc"
}

command -v docker >/dev/null || die "docker not found"
command -v psql >/dev/null || die "psql not found (brew install libpq / postgresql)"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "Building fat jar (set SKIP_BUILD=1 to reuse an existing one)..."
  (cd "$REPO" && pnpm install && pnpm build && ./gradlew :apps:epistola:bootJar) || die "build failed"
fi
[ -n "$(boot_jar)" ] || die "no fat jar in $JAR_DIR — build first, or drop SKIP_BUILD=1"

docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
docker run -d --name "$PG_NAME" \
  -e POSTGRES_DB=epistola -e POSTGRES_USER=epistola -e POSTGRES_PASSWORD=epistola \
  -p $PGPORT:5432 postgres:18 >/dev/null
until docker exec "$PG_NAME" pg_isready -U epistola >/dev/null 2>&1; do sleep 1; done
echo "Postgres ready (port $PGPORT). Running $ATTEMPTS burst attempt(s) of $DOCS docs each."

cleanup() {
  pkill -9 -f "epistola.node-id=dbt-" 2>/dev/null || true
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail=0 infra=0
for a in $(seq 1 "$ATTEMPTS"); do
  echo "=== attempt $a/$ATTEMPTS ==="
  run_attempt "$a"; rc=$?
  [ "$rc" = 1 ] && fail=$((fail + 1))
  [ "$rc" = 2 ] && infra=$((infra + 1))
done

echo
echo "logs + any thread dumps: $WORK"
if [ "$fail" -gt 0 ]; then
  echo "FAIL: $fail/$ATTEMPTS attempt(s) WEDGED — concurrent-render deadlock (#724 regression?). See thread dumps above."
  exit 1
fi
if [ "$infra" -gt 0 ]; then
  echo "INCONCLUSIVE: $infra/$ATTEMPTS attempt(s) failed to run (infra/app error, not a wedge). No wedge in the rest."
  exit 2
fi
echo "PASS: $ATTEMPTS/$ATTEMPTS burst attempts drained with no wedge (concurrent render on platform threads holds)."
exit 0
