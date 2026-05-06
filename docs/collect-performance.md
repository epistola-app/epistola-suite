# Collect performance — measured limits

This doc records measured performance numbers for the v0.3 collect
mechanism (`/api/tenants/{tid}/generation/collect`). Each section
below is a separate test scope; the matrix tables hold the numbers
we've actually seen on real hardware. Update by hand from
`build/perf-reports/collect-consumer-throughput.csv` after a run worth
recording.

## How to run

```bash
./gradlew :modules:epistola-core:perfTest \
  --tests app.epistola.suite.generation.collect.perf.CollectConsumerThroughputPerfTest \
  -Dperf.hardware=<short-machine-tag>
```

The `perf.hardware` system property is recorded in the CSV so numbers
from different machines (laptop, CI, prod-equiv) are distinguishable.
Default is `local-${user.name}`.

Output: console table per matrix row + appended CSV row at
`build/perf-reports/collect-consumer-throughput.csv`.

`perfTest` is a separate Gradle task gated by `@Tag("perf")`. Excluded
from `integrationTest` so the regular IT cycle stays fast. Not run in
CI by default; opt in when you want a fresh datapoint.

## Test setup conventions

- **Tuned Hikari pool** — `maximum-pool-size=64` (default test config
  is 10). With 16 parallel consumers the default would bottleneck on
  the pool before Postgres became the limit. The number below
  reflects pool=64; do not transplant directly into capacity-planning
  for a production deployment with default settings.
- **Postgres via Testcontainers** — single container per test class,
  fresh DB. Postgres 17 (whatever the suite's testcontainers config
  pins). No tuning beyond defaults; full WAL, no UNLOGGED override.
- **JobPoller disabled** —
  `epistola.generation.polling.enabled=false`. We're measuring drain
  only.
- **Bulk pre-seed** — rows inserted directly into `generation_results`
  via JDBI batch (`prepareBatch`, chunks of 1000). No
  `EmitGenerationResult` command, no executor, no fake PDF render.

## Consumer throughput (drain only)

**What this measures**: how fast N collectors drain a queue of K
pre-seeded rows. Producer is absent; we're isolating the collect path
(`TouchConsumerNode` ring assignment + `AcknowledgeGenerationResults`
cursor advance + `FetchGenerationResults` Postgres read +
cursor-contention).

**Test class**:
`modules/epistola-core/src/test/.../perf/CollectConsumerThroughputPerfTest.kt`

### Results

Recorded numbers go here. Format: one section per hardware tag.
Numbers are total drain throughput (msg/sec), with per-consumer min /
max to show fairness.

#### `mac-m4-pro` — 2026-05-03 — JVM Temurin 25

Postgres 17 in a podman-machine container (Fedora VM, ~2 GB RAM
allotted). Single test JVM with `-Xms512m -Xmx2g`, `-XX:+UseParallelGC`.
Hikari pool tuned to 64 (default test config is 10 — would have been
the bottleneck at 16 consumers).

##### 10k rows — burst measurement

These finish in <400 ms; they reflect single-batch latency more than
sustained throughput. Useful as a baseline but not the headline number.

| consumers | rows  | duration (ms) | throughput (msg/sec) | per-consumer min / max | polling efficiency |
| --------- | ----- | ------------- | -------------------- | ---------------------- | ------------------ |
| 1         | 10000 | 370           | 27 027               | 10000 / 10000          | 1.000              |
| 4         | 10000 | 179           | 55 866               | 1770 / 2954            | 0.647              |
| 16        | 10000 | 192           | 52 083               | 159 / 1384             | 0.339              |

##### 100k rows — steady-state

| consumers | rows   | duration (ms) | throughput (msg/sec) | per-consumer min / max | polling efficiency |
| --------- | ------ | ------------- | -------------------- | ---------------------- | ------------------ |
| 1         | 100000 | 4306          | 23 223               | 100000 / 100000        | 1.000              |
| 4         | 100000 | 1909          | 52 383               | 17301 / 29470          | 0.873              |
| 16        | 100000 | 1581          | 63 251               | 1565 / 14057           | 0.599              |

Headline: **~63 k msg/sec sustained at 16 consumers** on this machine,
**~52 k at 4**, **~23 k at 1**.

Observations:

- **Scaling is meaningfully sub-linear**: 16x consumers gets ~2.7x the
  throughput of 1. Postgres becomes the shared bottleneck (single PG
  instance, all 16 sessions reading + advancing cursors against the
  same pages). Realistic: in production, 4–8 consumers per PG is
  probably a sweet spot before you'd want to scale PG itself.
- **4 consumers at 10k drains in 179 ms** but **16 consumers at 10k
  takes 192 ms** — the 10k matrix is dominated by ring-stabilization
  overhead, not steady-state. Don't over-read those numbers.
- **Polling efficiency drops with consumer count**: 100% (1) → 87%
  (4) → 60% (16) at 100k rows. Many empty polls at high consumer
  count because most partitions are already drained while a few
  laggards finish. Increasing batch `limit` from 1000 helps this
  marginally.
- **Per-consumer fairness is poor at 16 consumers**: stddev ~3700 on
  a mean of ~6250. Two contributors: (a) routing keys
  `perf-0..perf-99999` don't perfectly distribute across the 64
  partitions via murmur3; some partitions hold ~1.5x more rows than
  others. (b) Consumers race for partitions on the first poll — whoever
  polls first within their assignment gets the bigger initial slice.
  In production with sustained traffic this evens out; the per-batch
  unfairness here is artifactual to the bulk-pre-seed setup.

## Idle polling cost

**What this measures**: empty polls — when N consumer nodes are
polling but no rows are ever emitted. This is the dominant
production load: most of the time most consumers are idle. Each
poll dispatches Touch + Ack + Fetch via the mediator (same path
as the real `/api/tenants/{tid}/generation/collect` endpoint),
but Ack is a no-op (no `lastSequence` ever materializes) and
Fetch returns empty.

**Test class**:
`modules/epistola-core/src/test/.../perf/CollectIdlePollPerfTest.kt`

The point of the measurement: decide whether a server-side cache
of "last emit per (tenant, partition)" would meaningfully cut
polling cost. Decision rule from the plan that commissioned the
test:

| If measured p99 per poll is… | Then…                                                        |
| ---------------------------- | ------------------------------------------------------------ |
| < 1 ms                       | Cache not worth it.                                          |
| 1–5 ms                       | Marginal. Defer cache, revisit if production shows pressure. |
| > 5 ms                       | Cache is worth pursuing.                                     |

### Results

#### `mac-m4-pro` — 2026-05-03 — JVM Temurin 25

Tight-loop polling (no inter-poll sleep) for 30 s per scenario.
Same Postgres + Hikari setup as the throughput test
(podman PG 17, pool tuned to 64).

| consumers | polls/sec aggregate | polls/sec per consumer | p50 (ms) | p95 (ms) | p99 (ms) | p99.9 (ms) |
| --------- | ------------------- | ---------------------- | -------- | -------- | -------- | ---------- |
| 1         | 256                 | 256.4                  | 3        | 4        | 5        | 10         |
| 16        | 730                 | 45.6                   | 21       | 29       | 36       | 63         |
| 64        | 535                 | 8.4                    | 113      | 179      | 233      | 628        |
| 256       | 113                 | 0.44                   | 610      | 12 043   | 25 918   | 30 323     |

**Decision: pursue the cache.** Even at 1 idle consumer p99 is
5 ms. At 64 concurrent it's 233 ms. At 256 it's 26 seconds —
the system is queueing on connections (Hikari pool size 64; 256
tight-looping consumers fight for connections).

What the data tells us:

- **Empty polls are not free.** ~5 ms each (1 consumer), driven
  by Touch UPSERT + Fetch index probe + mediator dispatch +
  connection acquisition + transaction overhead. Per poll
  cost dominates over per-row cost — a row of work is roughly
  the same wall-clock as zero rows of work.
- **Latency degrades super-linearly with concurrency** because
  Postgres connection pool waits stack up. With Hikari pool at
  64 and 256 hot consumers, the queue depth blows past what's
  serviceable.
- **In production with 16k consumers** (per the v0.5 push-collect
  doc): at default 1 s `minInterval` the aggregate poll rate
  hits 16 k/sec across the cluster. With 4 suite instances that's
  4 k polls/sec/instance, ~20 cpu-seconds/sec at the measured
  5 ms/poll baseline — saturates 5 cores per instance for
  _empty_ polls. Untenable.

This validates two follow-on directions, both worth doing:

1. **Phase 2 (this PR)** — client-side `kick()` + 3× backoff +
   modest `maxInterval` increase. Reduces idle poll rate per
   consumer in the steady state. Doesn't address per-poll cost.
2. **Phase 3 (separate work)** — server-side cache or `last_emit`
   sentinel table to short-circuit empty polls at sub-ms cost.
   Addresses per-poll cost directly. Sketched in the plan file
   that commissioned this test; will get its own design doc when
   we commit to it.

Re-running this test on real hardware (RDS, dedicated PG box) is
likely to improve the absolute numbers 2-3×, but the relative
shape — empty polls cost ~ms each, scaling badly with
concurrency — won't change. The optimization is independently
worth it.

## Out of scope (planned follow-on perf work)

These are tracked in
[`/Users/sdegroot/.claude/plans/`](../README.md#planning) and the
matching v0.3 PR thread:

- **Producer throughput** — bulk-call `EmitGenerationResult` from N
  threads, measure rows/sec INTO `generation_results`. Symmetric to
  the consumer-throughput test above but on the emit side.
- **End-to-end realistic** — `FakeDocumentGenerationExecutor` +
  `JobPoller` + collectors. Captures the full async path overhead
  including JobPoller scheduling. Lower ceiling than either pure
  test; more useful for capacity planning.
- **Table-size scaling** — re-run consumer drain at 0, 100k, 1M, 10M
  existing rows to map where p99 query latency degrades.
- **Rebalance recovery time** — kill one consumer mid-drain, measure
  how long until the remaining consumers absorb its share.
- **JMH microbenchmarks** for `Partition.partitionFor()`,
  `ConsistentHashRing.partitionsFor()`, `NdjsonResultStream.writeTo()`
  — different domain (single-call ns-scale), separate Gradle source
  set under `modules/loadtest/src/jmh/`.
