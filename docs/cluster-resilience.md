# Cluster Resilience

## Summary

This document covers how the Epistola cluster survives a **partially-failed
node** — one that is degraded or wedged but not cleanly dead. It builds on the
node registry and scheduled-task runtime described in
[`horizontal-scaling-phase1.md`](horizontal-scaling-phase1.md).

The guiding invariant is:

> **Work must never be permanently orphaned.** A job may run late or, in rare
> failure recovery, twice — but it must never be silently stuck forever.

That is a deliberate choice of **at-least-once over at-most-once**: given a
partial failure, we prefer duplicated or delayed work to lost work.

The hardening here came out of the first 3-node multi-instance test
(2026-07-10), which surfaced three interacting bugs (#723, #724, #725). The
repro harness is [`scripts/multi-instance-test.sh`](../scripts/multi-instance-test.sh).

## The failure that started it: heartbeat ≠ liveness

Cluster nodes run two independent loops:

- the **heartbeat**, on the dedicated `cluster-maintenance` thread, writes
  `cluster_nodes.last_seen_at`. This is what marks a node "active".
- the **scheduler poll**, on the scheduling-driver thread, claims and runs due
  scheduled tasks.

They are deliberately decoupled so a slow handler on the poll thread cannot make
a healthy node look dead. The flip side is the trap: a node whose **poll thread
is wedged** (see #724) keeps heartbeating, so it still looks active — and stays
the elected rendezvous owner of its single-owner tasks. Every healthy node then
skips those tasks as "not mine", and they starve **fleet-wide** with no error
surfaced (#723). Because one of the starved tasks is document recovery itself,
the failure is self-amplifying.

Three layers now defend against this.

### 1. Scheduler-liveness election (#723)

`cluster_nodes.last_poll_completed_at` is stamped by
`ClusterScheduledTaskScheduler.poll()` at the **end** of each poll cycle, so it
advances only while the poll thread is actually completing work — a signal
distinct from the heartbeat.

Single-owner ownership is computed over
`ClusterNodeRegistry.schedulerActiveNodes()`, which requires a node to be fresh
on **both** `last_seen_at` (within `idle-timeout-ms`) **and**
`last_poll_completed_at` (within `scheduled-tasks.scheduler-idle-timeout-ms`). A
heartbeating-but-wedged node drops out of election, and a healthy node re-owns
its due tasks. The current node always self-includes (its poll thread is running
right now), so a single fresh node still owns its tasks on the first cycle.

The lease remains the correctness boundary — a still-leased task is never taken
over on this path — so this only rescues tasks that were **due but never
claimed**.

### 2. Hard-deadline watchdog

Scheduler-liveness handles a task that a wedged owner never claimed. It does
**not** handle a task the owner claimed and then wedged _mid-dispatch_: handlers
run synchronously on the poll thread, and the `cluster-maintenance` thread keeps
renewing that task's lease forever, so lease-expiry never fires and ownership
re-election can't take over a validly-leased row.

The watchdog closes that gap. A single-owner run whose `last_started_at` is older
than `scheduled-tasks.max-run-duration-ms` (default 15 min) becomes reclaimable
**regardless of its lease** — `ClusterScheduledTaskRegistry.dueCandidates` and
`claimDue` add an `OR last_started_at < :runDeadline` escape. `FOR UPDATE SKIP
LOCKED` keeps takeover to one node, and a late `complete()` from the original
runner is a no-op (it is guarded on lease ownership).

This generalizes to **all** single-owner tasks. The cost is at-least-once
semantics for a task that legitimately exceeds the deadline. **Set
`max-run-duration-ms` well above the longest expected single-owner handler
runtime**: a value _below_ a handler's real runtime does not cause a single
double-run but _accumulating concurrent re-runs_ (reclaimed again each deadline
while prior runs are still executing).

### 3. Document recovery runs on every node

Generation documents stuck `IN_PROGRESS` by a dead/wedged claimer are recovered
by `StaleJobRecovery`, which resets them to `PENDING` for another node to claim.
This is _the_ guarantee that no document is permanently orphaned, so it must not
itself depend on one node staying healthy.

`StaleJobRecovery` therefore runs as **`EACH_CAPABLE_NODE`**, not
`SINGLE_OWNER`. The sweep is idempotent and reclaim uses `FOR UPDATE SKIP
LOCKED`, so running it concurrently on all nodes is harmless — and while any
node is healthy, stale documents are recovered within one interval regardless of
how another node failed. (Layers 1 and 2 protect the recurring scheduled tasks;
this protects the document work directly, without relying on either.)

## Load-test stale-run recovery (#725)

`LoadTestPoller.recoverStaleTests` previously judged a RUNNING run stale by claim
age (`claimed_at`), so "stale" meant _old_, not _abandoned_. Because
`GenerateDocumentBatch` is not idempotent, recovering a healthy long run
re-executed it — doubling the batch and corrupting metrics.

`load_test_runs.last_progress_at` is now stamped on claim and on every ~500ms
executor poll. Recovery keys off `COALESCE(last_progress_at, claimed_at) <
threshold`, so a run whose executor is alive is never recovered regardless of
total duration; only a run with no progress for the timeout (a dead executor) is
reset. A startup guard rejects a stale timeout below the progress cadence.

## Render warmup and the drain gate (#724)

### What it does

The first PDF render on a JVM class-inits iText's font parser
(`FontCache.getEmbeddedFont` → `PdfFontFactory.createFont` → `OpenTypeParser`),
reads the bundled TTFs out of the executable jar's **nested jar**, and
first-loads the `PdfDocument`/writer/renderer graph. A concurrent burst of
generation virtual threads (a sibling node dies; survivors ramp to full
`max-concurrent-jobs`) hitting that first-time class loading at once was
observed to deadlock on the classloader / nested-jar loader monitors and wedge
the node — which is what _armed_ #723.

Two mitigations:

- **Warmup.** `RenderWarmup` (an `ApplicationRunner`) forces the graph to load
  once, single-threaded, at startup: `FontCache.warmUp()` loads the embedded and
  standard font faces, and `DirectPdfRenderer.warmUp()` renders throwaway
  **single-pass and two-pass** documents (the two-pass path uses `renderTwoPass`
  plus a second measurement `PdfDocument`, which the single-pass warmup would
  miss). Both are best-effort and never fail startup.
- **Drain gate.** `RenderWarmupGate` holds the `JobPoller` concurrent drain back
  until warmup completes — closed as the warmup bean is constructed (before the
  scheduled poller starts), reopened in a `finally` when warmup finishes. This
  makes "no concurrent drain before the render graph is warm" a guarantee rather
  than a startup-timing race. The gate defaults **open**, so nodes without a
  warmup provider (module/integration tests, minimal deployments) drain
  immediately, and it reopens even if warmup throws so the poller is never wedged
  shut.

### Honest status of the root cause

**The #724 root cause is diagnosed from the original incident's thread dump but
has not been independently reproduced.** The code path and the concurrency are
verified; the specific deadlock _mechanism_ (a per-class-name load lock ⇄ the
`UrlNestedJarFile` monitor cycle, with an unmounted virtual thread holding the
monitor per JEP 491) is taken from that dump's analysis. Note the nested-jar TTF
read happens on **every** per-document `FontCache` miss, not only first-time, so
if the deadlock does not actually require a first-time class load, the warmup
would not fix it.

Caveats to keep in mind:

- The `FontCacheTest` concurrency test runs from the **exploded classpath**
  (Gradle) — there is no nested jar there, so it cannot reproduce this deadlock;
  it only proves the code does not crash.
- The chaos run executes from the fat jar (the condition _can_ occur) and did
  not deadlock, but with warmup present and against a rare race, so it cannot by
  itself prove the fix.

To verify definitively: run the fat jar with a cold-start burst of virtual
threads each building an embedded `FontCache` **without** warmup, try to hang it,
and capture a thread dump; then enable warmup and confirm it is gone. Also worth
checking known JDK 25 / Spring Boot loader issues for the signature (see the
`TODO(#724)` in `RenderWarmup`).

The warmup and gate are cheap and harmless regardless, and — importantly — the
#723 hardening above means a wedge **from any cause** no longer starves the
fleet, so the resilience guarantee does not hinge on this diagnosis being exact.

## Configuration reference

| Property                                                     | Default | Purpose                                                                                                      |
| ------------------------------------------------------------ | ------- | ------------------------------------------------------------------------------------------------------------ |
| `epistola.cluster.idle-timeout-ms`                           | 10000   | Heartbeat freshness window for "active".                                                                     |
| `epistola.cluster.scheduled-tasks.scheduler-idle-timeout-ms` | 30000   | Poll-completion freshness for single-owner election (#723).                                                  |
| `epistola.cluster.scheduled-tasks.lease-duration-ms`         | 30000   | Lease held on a claimed task; renewed while in-flight.                                                       |
| `epistola.cluster.scheduled-tasks.max-run-duration-ms`       | 900000  | Hard deadline after which a single-owner run is force-reclaimed. Set well above the longest handler runtime. |
| `epistola.generation.polling.stale-timeout-minutes`          | 10      | IN_PROGRESS document age after which `StaleJobRecovery` re-queues it.                                        |
| `epistola.loadtest.polling.stale-timeout-minutes`            | 10      | No-progress window after which a load-test run is recovered (#725).                                          |

New columns (additive, nullable, data-preserving migrations):

- `cluster_nodes.last_poll_completed_at` — scheduler-liveness signal.
- `load_test_runs.last_progress_at` — load-test progress heartbeat.

## Testing

- Unit/integration tests: `ClusterScheduledTaskSchedulerIT` (wedged-owner
  takeover), `ClusterScheduledTaskRegistryIT` (hard-deadline force-reclaim, plus
  the negative "within deadline" case), `ClusterNodeRegistryIT`
  (scheduler-liveness), `StaleJobRecoveryDefinitionTest` (recovery is
  `EACH_CAPABLE_NODE`), `LoadTestPollerRecoveryIT` (fresh vs stale progress),
  `RenderWarmupGateTest`, and the font/render warmup tests.
- End-to-end: `scripts/multi-instance-test.sh chaos` starts a 10k-doc load test,
  SIGKILLs a node holding in-flight jobs, and asserts stale-job recovery
  re-queues the orphans, work distributes across the survivors, exactly-once
  completion, and no deadlock.

## Known limitation: cross-node job distribution latency

Generation jobs spread across nodes only via each node's periodic
`generation.polling.interval-ms` poll (default ~5s): the node that submits a
batch drains it in-process immediately, so a small, fast batch is swept by that
one node before peers poll. This is a latency/utilization issue, not a
correctness one (claiming is `FOR UPDATE SKIP LOCKED`). The real remedy is a
cross-node drain **wakeup** (Postgres `LISTEN/NOTIFY`) so peers pull promptly
regardless of batch size — a deliberate follow-up, not yet implemented.
