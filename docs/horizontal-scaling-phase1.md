# Horizontal Scaling Phase 1

## Summary

Phase 1 adds the minimum cluster runtime Epistola needs for horizontal
scaling without introducing a general distributed command bus.

The immediate implementation starts with the node registry only. Later
increments can build clustered timer events, durable processes, cache
invalidation, and capability-aware job claiming on top of that registry.

## Why This Is Narrow

The high-traffic path in Epistola is document generation, and it already uses
PostgreSQL-backed claiming with `FOR UPDATE SKIP LOCKED`. Template, theme,
stencil, catalog, and settings mutations are not expected to be high-volume
endpoints. For those operations, normal database transactions plus cross-node
cache invalidation are enough for now.

The first horizontal-scaling runtime should therefore solve the actual missing
coordination problems:

- know which Suite nodes are alive
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

Each running Suite process registers itself in `suite_nodes` using the existing
`NodeIdentity.nodeId`. The row records capabilities, version, join time, last
heartbeat, and small JSON metadata.

Initial capability:

- `suite`

Future capability:

- `pdf-render`

### Timers And Scheduled Tasks

Scheduled tasks are timer events.

Today, Spring `@Scheduled` methods are local tick sources. In a cluster, the
tick should only mean "check whether this timer event is due." The cluster
runtime should then claim the due timer event through PostgreSQL and execute it
once.

Future task rows should carry:

- a stable `task_key`
- an `affinity_key`
- a sticky preferred owner
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

Add `suite_nodes`, a heartbeat service, active-node queries, and configuration.
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

Per-node health checks should stay per-node.

### Phase 1C: Cache Invalidation Event Stream

Add append-only internal events and per-node cursors. Use it to fan out cache
invalidation. Direct peer wakeups can be added later if polling latency is too
high.

### Phase 1D: Durable Process Runtime

Add durable process tables, handler registry, runner, start API, and history.
Convert one contained process first, likely feedback outbound sync/retry.

### Phase 1E: Capability-Aware Background Work

Extend existing job claiming so future slim renderer nodes can advertise and
claim `pdf-render` work. Do not split the renderer app in this phase.

## Phase 1A Implementation Details

### Schema: `suite_nodes`

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

- `epistola.cluster.enabled`
- `epistola.cluster.heartbeat-interval-ms`
- `epistola.cluster.idle-timeout-ms`
- `epistola.cluster.capabilities`

`SuiteNodeRegistry`:

- `heartbeat()`
- `activeNodes()`
- `currentNode()`

`SuiteNodeHeartbeatScheduler`:

- fixed delay from `epistola.cluster.heartbeat-interval-ms`
- active when `epistola.cluster.enabled=true`

Default configuration:

```yaml
epistola:
  cluster:
    enabled: true
    heartbeat-interval-ms: 10000
    idle-timeout-ms: 30000
    capabilities:
      - suite
```

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

- `feat: add suite node registry`
- `feat: add cluster timer task runner`
- `feat: add cluster cache invalidation events`
- `feat: add durable process runtime`
