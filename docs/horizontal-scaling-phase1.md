# Horizontal Scaling Phase 1

## Summary

Phase 1 adds the minimum cluster runtime Epistola needs for horizontal
scaling without introducing a general distributed command bus.

The immediate implementation starts with the node registry only. Later
increments can build clustered timer events, durable processes, cache
invalidation, and capability-aware job claiming on top of that registry.

> How the cluster survives a **partially-failed / wedged** node (scheduler
> liveness, the hard-deadline watchdog, per-node document recovery, and the
> render warmup) is documented separately in
> [`cluster-resilience.md`](cluster-resilience.md).

## Why This Is Narrow

The high-traffic path in Epistola is document generation, and it already uses
PostgreSQL-backed claiming with `FOR UPDATE SKIP LOCKED`. Template, theme,
stencil, catalog, and settings mutations are not expected to be high-volume
endpoints. For those operations, normal database transactions plus cross-node
cache invalidation are enough for now.

The first horizontal-scaling runtime should therefore solve the actual missing
coordination problems:

- know which Cluster nodes are alive
- execute timer-driven work once across replicas
- keep scheduled and durable work sticky to the same node while healthy
- fan out cache invalidation across nodes
- prepare for future node capabilities such as a slim PDF renderer app

It should not route every command through a cluster owner yet.

## Design Principles

- PostgreSQL remains the only required coordination dependency.
- Correctness comes from PostgreSQL leases, cursors, and transactions.
- Node-to-node communication may be added later as a wakeup or latency
  optimization, not as the durable source of truth.
- Affinity is sticky. A task or process should usually stay on the same node
  while that node is healthy.
- Failover beats perfect balance. Rebalancing should happen on dead owners or
  explicit operator action, not on every topology change.
- Durable process handlers must be idempotent because execution is
  at-least-once.
- Existing document generation stays on the current `FOR UPDATE SKIP LOCKED`
  path until the PDF renderer split needs capability-aware claiming.

## Conceptual Model

### Nodes

Each running Suite process registers itself in `cluster_nodes` using the existing
`NodeIdentity.nodeId`. The row records capabilities, version, join time, last
heartbeat, and small JSON metadata.

Initial capability:

- `suite`

Future capability:

- `pdf-render`

### Timers And Scheduled Tasks

Scheduled tasks are durable recurring definitions in PostgreSQL. Spring
`@Scheduled` remains only inside `WallClockClusterSchedulingDriver` — the
production scheduling substrate that ticks the heartbeat, one-shot timer, and
scheduled-task engines (see `docs/timers.md`, "Scheduling Substrate"); business
jobs register native task definitions instead.

Task rows carry a stable `task_key`, a routing key for sticky ownership, a
required node capability, and an execution scope:

- `single_owner`: one capable node runs each occurrence.
- `each_capable_node`: every active capable node runs each occurrence with its
  own `(task_key, node_id)` runtime state.
- a lease owner and expiry
- last started/completed timestamps
- last error

Examples:

- global task: `affinity_key = "feedback.retry"`
- per-tenant task: `affinity_key = "tenant.backup:acme"`

The preferred owner runs the timer while healthy. If it disappears, another
node claims the task after heartbeat or lease expiry and becomes the preferred
owner.

### Durable Processes

Some work is more than a scheduled function call. Feedback sync, snapshot sync,
future PDF orchestration, and similar flows may need to:

- start from an explicit command or event
- execute one step
- wait until a timer
- retry with backoff
- wait for a correlated external event
- resume on another node after a crash

These should become durable processes, not ad-hoc scheduler state. A future
`durable_processes` table should store:

- process type
- business key
- affinity key
- status
- JSON state
- preferred owner
- lease owner and expiry
- next run time
- attempt count
- last error

Starting a process should be idempotent by `(process_type, business_key)`.

Handler decisions should be explicit:

- completed
- continue now
- continue at a specific instant
- retry at a specific instant
- failed
- cancelled

### Cache Invalidation Events

Cross-node cache invalidation should use a small append-only internal event
stream.

Every node should maintain its own cursor for the cache-invalidation
subscription. That makes the subscription a fanout mode: every active node sees
every event type it handles. Nodes should filter by event metadata first and
deserialize payloads only for handled event types.

Initial cache invalidation candidates:

- templates
- themes
- stencils
- fonts
- catalogs and catalog versions

The event stream should be at-least-once. Handlers must be idempotent.

## Phase Breakdown

### Phase 1A: Node Registry

Implement now.

Add `cluster_nodes`, a heartbeat service, active-node queries, and configuration.
This gives the rest of the cluster runtime a factual view of node presence and
capabilities.

### Phase 1B: Clustered Timer Events

Standardize one-run scheduled work behind a cluster task runner.

The runner should preserve sticky affinity, use a PostgreSQL lease for
correctness, and record metrics for acquired/skipped/completed/failed cycles.

Existing candidates:

- feedback retry
- feedback poll
- backups
- upgrading snapshots
- installation stats publisher
- partition maintenance
- stale reapers where duplicate work is noisy
- per-node health checks and local poller wakeups using `each_capable_node`

### Phase 1C: Cache Invalidation Event Stream

Add append-only internal events and per-node cursors. Use it to fan out cache
invalidation. Direct peer wakeups can be added later if polling latency is too
high.

### Phase 1D: Durable Process Runtime

Decision recorded in [ADR 0016](adr/0016-durable-process-runtime-in-house-vs-dbos.md):
build this in-house rather than adopting DBOS or a similar external
workflow-orchestration product. In-house does not mean minimal — the design
deliberately borrows DBOS's most valuable ergonomic feature, per-step checkpointing, via
a lightweight memoization helper (below), so authors get most of the "write linear code,
get resumability for free" experience. What it does not borrow is DBOS's full
determinism-verification/replay engine, cross-process signaling, or code-version
pinning — see "What this deliberately does not replicate" below for why.

Add a `durable_processes` table plus a handler registry, runner, start API, and history —
generalizing the claim/lease/backoff/terminal-state shape already proven by the exchange
catalog-publication outbox (`CatalogPublicationStore`/`CatalogPublicationWorker`, ADR
0015), which stays as-is and serves as the design reference rather than being rewritten.

Runtime shape:

- **Schema** — one row per work item, keyed by `(process_type, business_key)` for
  idempotent starts. Fields mirror `cluster_tasks_scheduled`'s lease shape:
  `tenant_key`, `routing_key`, `required_capability`, `queue_key`, `status`, a
  handler-owned `state` JSONB blob, `next_run_at`, `lease_owner_node_id` /
  `lease_expires_at`, `attempt_count`, `last_error`, and the usual `last_started_at` /
  `last_completed_at` / `last_failed_at` / `created_at` / `updated_at`. Terminal rows
  (`FAILED` / `CANCELLED` / `COMPLETED`) are kept, not deleted, matching the exchange
  outbox's precedent that tenant/operator-facing history has already proven valuable
  there. `max_attempts` and backoff strategy stay handler/feature-owned code (mirroring
  `ExchangeProperties.maxAttempts` + `CatalogPublicationWorker.backoff()` today), not row
  columns, so each feature tunes its own retry policy independently and the schema stays
  generic. `queue_key` defaults to `process_type` and exists so several process types can
  share one concurrency budget when that's useful (e.g. everything that calls the same
  rate-limited remote API).
- **Handler contract**:

  ```kotlin
  interface DurableProcessHandler {
      val processType: String
      fun attempt(process: DurableProcess, ctx: DurableProcessContext): DurableProcessOutcome
  }

  sealed interface DurableProcessOutcome {
      data class Completed(val state: Map<String, Any?>? = null) : DurableProcessOutcome
      data class ContinueNow(val state: Map<String, Any?>) : DurableProcessOutcome
      data class ContinueAt(val at: OffsetDateTime, val state: Map<String, Any?>) : DurableProcessOutcome
      data class RetryAt(val at: OffsetDateTime, val error: String, val state: Map<String, Any?>? = null) : DurableProcessOutcome
      data class Failed(val error: String, val state: Map<String, Any?>? = null) : DurableProcessOutcome
      data class Cancelled(val reason: String) : DurableProcessOutcome
  }
  ```

  This generalizes the exchange worker's existing `defer` (→ `ContinueAt`, no attempt
  penalty), `applyRemoteState` (→ `ContinueAt`/`Completed`), and `recordFailure` (→
  `RetryAt`/`Failed`, with the handler deciding the terminal transition itself via its own
  `maxAttempts` check, exactly as it does today).

- **Step memoization (the DBOS-inspired part)** — `DurableProcessContext` gives handlers
  a `step(name) { ... }` helper so multi-step flows can be written as ordinary linear
  code instead of a hand-rolled `when (state["step"])` machine:

  ```kotlin
  override fun attempt(process: DurableProcess, ctx: DurableProcessContext): DurableProcessOutcome {
      val release = ctx.step("resolveRelease") { resolveRelease(process) }
      val token = ctx.step("resolveToken") { resolveToken(process) }
      val result = ctx.step("submit") { submit(release, token) }
      return Completed(mapOf("remoteId" to result.id))
  }
  ```

  `attempt()` re-executes from the top on every call (each poll, each retry). `step()`
  checks `state["__steps"][name]` first: if present, it returns the memoized value
  without calling `block()` again; if absent, it calls `block()`, writes the result into
  `state["__steps"][name]` **immediately** (its own small UPDATE, not deferred to the end
  of `attempt()`), and returns it. This is exactly DBOS's own trade-off, "one database
  write per completed step" — we're converging on the same implementation shape, just
  without generic non-determinism detection: the handler author is trusted to call
  `step()` in the same order with the same names across retries, and to keep any code
  _between_ `step()` calls free of side effects, the same discipline DBOS's own docs
  require of a workflow body. If a whole `attempt()` call throws partway through,
  whatever steps already completed stay checkpointed — the next call skips straight past
  them.

- **Queues and concurrency** — the claim query groups by `queue_key` and caps how many
  `running` rows per queue a node will claim, against a `maxConcurrent` a feature
  registers in code alongside its handler (same "feature owns its own policy, schema
  stays generic" split as `max_attempts`/backoff). Claiming orders by `created_at` within
  a queue, which is FIFO-ish under light contention but — like every other
  `FOR UPDATE SKIP LOCKED` claim in this codebase — not a strict ordering guarantee under
  concurrent claimers; document it as best-effort, not exactly-once-in-order.
- **Runner** — a `DurableProcessRunner` implementing `ClusterScheduledTaskHandler`,
  registered as one native scheduled task (`SINGLE_OWNER`, `FixedDelay`). The scheduled
  task decides _when_ to look; the store's row-level `SKIP LOCKED` claim and lease remain
  the actual crash-safety mechanism, as defense-in-depth even under single ownership —
  the same layering `CatalogPublicationWorker` already uses. A `process_type` with no
  registered handler on a given node defers rather than errors, reusing
  `ClusterScheduledTaskScheduler`'s existing missing-handler/registration-vouching
  behavior rather than inventing a second one.
- **Mediator surface** — `StartDurableProcess`, `CancelDurableProcess`,
  `RequeueDurableProcess`, `GetDurableProcess`, `ListDurableProcesses`, mirroring the
  shape already established by `ScheduleClusterTimer` / `CancelClusterTimer` /
  `GetClusterTimer` / `ListClusterTimers`. `ListDurableProcesses`/`GetDurableProcess`
  already carry enough (status counts, oldest-active age, per-process attempt history)
  to back an operator-facing view later — the same data DBOS Conductor's dashboard
  reads, just without a separate paid control plane; the UI itself is not part of this
  phase.

**What this deliberately does not replicate**, and why:

- **Automatic non-determinism detection.** DBOS statically/dynamically verifies a
  workflow replays the same step sequence; this design just trusts the handler author,
  the same trust model every other idempotency guard in this codebase already uses
  (`WHERE status != 'CANCELLED'`, `ON CONFLICT DO NOTHING`, fingerprint dedup). Building
  real determinism verification is a large undertaking with no demonstrated need yet.
- **Code-version pinning across deploys** (DBOS keeps an in-flight workflow on the code
  version it started with). Both current target processes (feedback sync, and the
  exchange outbox if it's ever migrated) are short-lived compared to a deploy cycle, so
  the risk this solves hasn't materialized here — a conscious, revisitable choice, not an
  oversight.
- **Cross-process signaling / child processes** (DBOS's `send`/`recv`, child workflows).
  Waiting on something external means polling it on each `attempt()` — exactly what the
  exchange worker already does against Exchange's remote status today. Building a durable
  push-signal path is natural future work once Phase 1C's cache-invalidation event stream
  exists to carry the wakeup, but nothing today needs it.
- **Cross-service/cross-installation durability.** Postgres stays the sole coordination
  dependency, matching this document's own design principle and the reasoning in ADR
  0016 against a separate control-plane dependency.

This is a third, narrower primitive alongside `ClusterScheduledTask` and `ClusterTimer`,
not a replacement for either.

Migration posture: no big-bang rewrite. New durable-retry features must use this
primitive going forward. Convert one contained process first — feedback outbound
sync/retry (`FeedbackSyncScheduler`), the worst-off of the existing duplicates (no lease,
no backoff) and the most cleanly isolated. `BackupScheduler` and
`UpgradingSnapshotScheduler` don't need this primitive at all; extract their duplicated
"abort the sweep on a circuit-breaking failure" loop idiom into one small, schema-free
helper instead. `JobPoller`, `StaleJobRecovery`, and `LoadTestPoller` stay on their
existing bespoke paths permanently — different throughput profile, a hard platform-thread
constraint (JEP 491) for generation, and a deliberately zero-retry design a generic
backoff primitive would be wrong to impose.

Once this ships, document the actual built behavior in a new `docs/durable-processes.md`
(structured like `docs/timers.md`), rather than describing it here ahead of
implementation.

### Phase 1E: Capability-Aware Background Work

Extend existing job claiming so future slim renderer nodes can advertise and
claim `pdf-render` work. Do not split the renderer app in this phase.

## Phase 1A Implementation Details

### Schema: `cluster_nodes`

Fields:

- `node_id text primary key`
- `capabilities jsonb not null default '[]'::jsonb`
- `version text`
- `joined_at timestamptz not null default now()`
- `last_seen_at timestamptz not null`
- `metadata jsonb not null default '{}'::jsonb`

Indexes:

- `last_seen_at`

`joined_at` is set on insert and preserved on every heartbeat. `last_seen_at`
is updated on every heartbeat.

### Runtime Components

`ClusterProperties`:

- `epistola.cluster.heartbeat-interval-ms`
- `epistola.cluster.idle-timeout-ms`
- `epistola.cluster.capabilities`

There is deliberately no `epistola.cluster.enabled` switch. Epistola always
runs as a cluster runtime; a non-horizontal deployment is simply a one-node
cluster. Keeping the registry active in that case avoids a second execution path
for timer ownership, durable processes, and cache invalidation.

`ClusterNodeRegistry`:

- `heartbeat()`
- `activeNodes()`
- `currentNode()`

`ClusterNodeHeartbeatScheduler`:

- fixed delay from `epistola.cluster.heartbeat-interval-ms`
- always active

Default configuration:

```yaml
epistola:
  cluster:
    heartbeat-interval-ms: 2000
    idle-timeout-ms: 10000
    capabilities:
      - suite
```

The default 2s heartbeat / 10s idle timeout gives quick enough failover for
sticky timer events and durable process ownership while still producing only one
small upsert every two seconds per node.

## Out Of Scope For Phase 1

- Distributed command routing for template edits.
- Global event sourcing.
- General-purpose message bus semantics.
- SSE/push collect.
- Direct node-to-node forwarding.
- Exactly-once messaging.

## Testing

Phase 1A tests:

- heartbeat inserts the current node
- second heartbeat updates `last_seen_at` while preserving `joined_at`
- active-node query excludes stale nodes
- configured capabilities are persisted and read back
- registry uses `NodeIdentity`

Later phase tests:

- timer event executes once across simulated nodes
- timer ownership remains sticky to preferred owner
- timer failover works after owner heartbeat expires
- durable process starts idempotently
- durable process persists state before sleeping
- durable process resumes on another node after stale lease
- cluster events are consumed by every active node subscriber
- event cursors resume after restart
- cache invalidation handlers tolerate duplicate events

## Operational Metrics

Phase 1A should expose enough data for future dashboards, even if a dedicated
UI comes later:

- active node count
- heartbeat failures
- current node capabilities

Later phases should add:

- task lease acquired/skipped/completed/failed counts
- event lag per node
- durable process retries and failures
- process execution duration

## Changelog And Commit Discipline

Every implementation increment should update `CHANGELOG.md` and be committed
with a conventional commit. Suggested commits:

- `feat: add cluster node registry`
- `feat: add cluster timer task runner`
- `feat: add cluster cache invalidation events`
- `feat: add durable process runtime`
