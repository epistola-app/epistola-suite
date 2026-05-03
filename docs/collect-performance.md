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
