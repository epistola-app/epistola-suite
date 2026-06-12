# Timers

## Summary

Epistola uses PostgreSQL-backed cluster timers for delayed and recurring
background work. The implementation is intentionally self-contained: PostgreSQL
is the durable source of truth, every Suite node can poll for work, and row
leases ensure a timer is executed by only one node at a time.

There are two timer shapes today:

- one-shot timers in `cluster_timers`
- recurring scheduled tasks in `cluster_tasks_scheduled`

One-shot timers live in `app.epistola.suite.cluster.timers`. Scheduled tasks
live in `app.epistola.suite.cluster.schedules`. Shared node presence,
capabilities, and cluster configuration stay in `app.epistola.suite.cluster`.

## Scheduling Substrate (Trigger vs Engine)

Scheduling is split into two layers:

- **Engines** own _what_ happens on a tick: `ClusterNodeHeartbeatScheduler`,
  `ClusterTimerScheduler`, and `ClusterScheduledTaskScheduler` perform
  due-selection, ownership, lease claiming, and dispatch. They are plain
  components with directly invokable `heartbeat()` / `poll()` methods and carry
  no `@Scheduled` annotations.
- **A driver** owns _when_ the engines run (`ClusterSchedulingDriver`).
  Selection is property-gated by `epistola.cluster.scheduling-substrate`:
  - `wall-clock` (default): `WallClockClusterSchedulingDriver` ticks the
    engines on the `epistola.cluster.*-interval-ms` fixed delays. This is the
    production substrate.
  - `test`: the deterministic driver from `modules/testing`
    (`DeterministicClusterScheduling`) replaces the loop; tests run due work
    explicitly and synchronously on the test thread. `IntegrationTestBase`
    selects this substrate for all integration tests.

The split exists because a wall-clock loop can never participate in test time:
the test clock is bound via `ScopedValue` on the test thread, so a background
scheduler thread always evaluates `next_due_at <= now` against the system
clock — and its autonomous ticks race concurrently-running test classes on
installation-wide state (timers and scheduled tasks are not tenant-scoped).
See "Time And Tests" below.

## Node Registry

Timers depend on the cluster node registry. Each running Suite process
heartbeats into `cluster_nodes` with:

- `node_id`
- advertised capabilities, currently usually `suite`
- join time and last-seen time
- version and metadata

Timer pollers use this registry to decide which nodes are active and whether
the current node is eligible to run a timer requiring a specific capability.

## One-Shot Timers

A one-shot timer represents delayed work that should run once, unless its
handler explicitly reschedules it.

The mediator-facing API is `ScheduleClusterTimer` and `CancelClusterTimer`.
Scheduling stores:

- `timer_key`: stable idempotency key
- `tenant_key`: optional tenant scope, `NULL` for system timers
- `routing_key`: affinity key used to pick the owning node
- `timer_type`: logical handler dispatch key
- `required_capability`: node capability required to run the timer
- `due_at`: when the timer becomes claimable
- `payload`: JSON payload for the handler

Scheduling is an upsert by `timer_key`. Re-scheduling the same timer key
replaces the due time, routing metadata, payload, and clears any previous lease
or error state.

The mediator-facing read API is `GetClusterTimer` and `ListClusterTimers`.
Application and operations code should use these commands and queries. The
`ClusterTimerRegistry` remains an internal persistence boundary for scheduler
lease transitions and command/query handlers.

## Timer Handlers

A timer handler implements `ClusterTimerHandler`:

```kotlin
interface ClusterTimerHandler {
    val timerType: String

    fun handle(timer: ClusterTimer): ClusterTimerResult
}
```

`timerType` is the dispatch key. It is not derived from the Kotlin class name.
Every timer type that can be scheduled should have exactly one Spring bean
advertising the same `timerType`.

Handlers return one of:

- `Complete`: delete the timer after successful execution
- `Reschedule(nextDueAt, payload)`: keep the timer and make it due again later

Handlers must be idempotent. Execution is at-least-once: a node can crash after
doing side effects but before completing the timer row, and another node may
reclaim it after the lease expires.

## One-Shot Timer Flow

1. Application code dispatches `ScheduleClusterTimer` through the mediator.
2. `ClusterTimerRegistry.schedule` upserts a row in `cluster_timers`.
3. Every node runs `ClusterTimerScheduler.poll` on its local Spring schedule.
4. The poller loads due candidates from PostgreSQL.
5. The poller filters active nodes to those advertising the timer's
   `required_capability`.
6. Rendezvous ownership over `routing_key` decides which active capable node
   should own the timer.
7. Only the owning node attempts to claim the timer.
8. `ClusterTimerRegistry.claimDue` updates the row to `running` with
   `lease_owner_node_id` and `lease_expires_at`, using `FOR UPDATE SKIP LOCKED`.
9. The scheduler looks up the handler by `timer.timerType`.
10. The handler runs inside `MediatorContext.runWithMediator(mediator)`, which
    binds the mediator context and current `EpistolaClock` for the same-thread
    poll cycle.
11. The scheduler completes, reschedules, or retries the timer depending on the
    handler result or exception.

The ownership step is a routing optimization and affinity mechanism. The lease
step is the correctness mechanism.

## Missing Handlers And Failures

If no handler is registered for a claimed timer's `timerType`, the timer is not
dropped. The scheduler records an error and retries it after the configured
retry delay.

If a handler throws, the scheduler also records the error and retries later.
The timer remains durable until it completes, is rescheduled, or is cancelled.

If a node dies while holding a lease, another active capable node can reclaim
the timer after `lease_expires_at`.

## Liveness And Long-Running Handlers

Two mechanisms keep a slow handler from being mistaken for a dead node and
re-run elsewhere:

- **The node heartbeat does not share the Spring `@Scheduled` pool** with
  handler dispatch (`ClusterNodeHeartbeatScheduler`). A handler that blocks a
  poller thread therefore cannot starve the heartbeat, mark a healthy node
  stale, or shift routing-key ownership.
- **The pollers renew the lease while a handler runs** (`ClusterLeaseRenewer` +
  `renewLeases` on each registry). A handler that legitimately runs longer than
  the lease duration keeps its lease fresh, so another node will not reclaim and
  re-run the in-flight occurrence — even across a membership change. Execution is
  still **at-least-once**: handlers should be idempotent for the crash case,
  where renewal stops and the lease lapses.

The heartbeat and both lease renewers run on a single shared maintenance thread
(`ClusterMaintenanceExecutor`). They are all small, bounded database operations
and none of them run handler work, so one thread is enough — a long handler
(which runs on the poller thread) can never block it.

## Recurring Scheduled Tasks

Scheduled tasks are durable recurring timer definitions. They are stored
separately from one-shot timers because they need schedule shape, enabled state,
failure policy, catch-up policy, and rolling execution metadata.

The table is `cluster_tasks_scheduled`. A scheduled task stores:

- `task_key`: stable idempotency key
- `tenant_key`: optional tenant scope, `NULL` for system tasks
- `routing_key`: affinity key used to pick the owning node
- `task_type`: logical handler dispatch key
- `execution_scope`: `single_owner` or `each_capable_node`
- `required_capability`: node capability required to run the task
- `payload`: JSON payload for the handler
- schedule shape: cron, fixed delay, or fixed rate
- failure and catch-up policy
- `enabled`
- `next_due_at`
- lease and execution metadata
- `management_mode` (`code` or `manual`) plus `retired_at` / `retirement_reason`
  for the retirement lifecycle (see "Scheduled Task Lifecycle")

The mediator-facing controls are:

- `UpsertClusterScheduledTask`
- `EnableClusterScheduledTask`
- `DisableClusterScheduledTask`
- `TriggerClusterScheduledTaskNow`
- `ListClusterScheduledTasks`
- `ListClusterScheduledTaskNodeStates`

The current recurring task registrar registers definitions at startup. This
means the database row is durable, while code remains the source of truth for
the task definition.

## Scheduled Task Flow

The scheduled-task flow mirrors one-shot timers:

1. Startup registrars upsert task definitions.
2. Every node runs `ClusterScheduledTaskScheduler.poll`.
3. The poller loads due enabled tasks.
4. `single_owner` tasks calculate ownership from `routing_key` across active
   capable nodes.
5. `each_capable_node` tasks are claimable by every active node that advertises
   the task's required capability.
6. Singleton tasks claim the task row; all-node tasks claim their
   `(task_key, node_id)` runtime row in `cluster_tasks_scheduled_node_state`.
7. The scheduler dispatches to a `ClusterScheduledTaskHandler` by `taskType`.
8. On success, `ClusterScheduledTaskScheduleCalculator` calculates the next due
   occurrence and the lease is released.
9. On failure, failure policy decides whether to retry the same occurrence or
   advance to a future occurrence.

Unlike one-shot timers, a scheduled task with **no registered handler** is not
treated as a failure. The dispatch path folds the orphan check in:

- If **no node carrying the definition has been seen within the grace window**
  (the same liveness gate the reconciler uses), the task is genuinely orphaned —
  a registration means the node has the definition and therefore the handler, so
  "no live node holds it" means nothing can ever run it — and it is **soft-retired
  inline**, the moment it fires, rather than waiting for the periodic reconciler.
- Otherwise a node still holds it (a rolling-deploy in-between state, or a
  non-handler-bearing node that claimed it by capability), so the scheduler logs a
  warning and quietly advances `next_due_at` without recording an error or
  incrementing the failure count, letting a holding node run it.

Either way there is no error-state spam and no tight claim → reclaim loop. The
periodic `core.scheduled-task-reconciliation` task remains the backstop for
orphans that never fire (see "Scheduled Task Lifecycle"). A handler that exists
and throws still follows the failure policy.

Use `single_owner` for installation-wide work that should run once per
occurrence, such as feedback polling, backups, stale reapers, and cleanup. Use
`each_capable_node` for per-process work, such as local health probes and local
queue drain triggers, where every capable node needs its own cadence and crash
recovery state.

Scheduled tasks are not implemented by creating a chain of one-shot timer rows.
The recurring definition is the durable state. This avoids losing the schedule
if a process fails before it can insert the next one-shot timer occurrence.

## Scheduled Task Lifecycle

One-shot timers and recurring scheduled tasks have different removal semantics.
One-shot rows in `cluster_timers` are deleted when they complete, and callers can
delete pending one-shot work with `CancelClusterTimer`. Recurring scheduled task
rows in `cluster_tasks_scheduled` are durable definitions, so code-defined rows
need an explicit lifecycle when the code stops registering them.

The lifecycle for code-defined scheduled tasks is:

1. **Register and vouch.** Startup registration (`ClusterScheduledTaskRegistrar`)
   upserts every `ClusterScheduledTaskDefinition` from code, then records this node
   in `cluster_scheduled_task_registrations` (`task_key`, `node_id`,
   `build_version`, `registered_at`) for exactly the definitions it carries — and
   prunes its registration rows for definitions it no longer carries. That per-node
   set is "which nodes vouch for this schedule." Re-upserting a definition also sets
   `management_mode = 'code'` and clears any prior retirement, so a returning
   definition is reclaimed.
2. **Detect orphans.** Reconciliation looks for `management_mode = 'code'`,
   not-yet-retired rows that **no node carrying the definition has been seen
   running recently** (`ClusterScheduledTaskRegistry.findRetirableCodeTasks`). A
   node "carries" a definition if it has a registration row; whether it counts as
   _seen_ is judged by that node's `cluster_nodes.last_seen_at`, which the
   heartbeat keeps current — not by the startup `registered_at`. Detection is
   registration-based, not build-version-based: it does not need all nodes to share
   a build version.
3. **Wait out the grace period.** A task is retired only once **every** node that
   registered it has been unseen for
   `epistola.cluster.scheduled-tasks.reconciliation-grace-period-ms` (default
   15 min) — i.e. every registering node's `last_seen_at` is older than the window.
   Because liveness is the heartbeat-fresh `last_seen_at`, a node restart shorter
   than the window keeps its schedules protected, and a rolling deploy is safe (old
   nodes keep their schedules alive until they actually leave). `registered_at` is
   retained for audit only, not as the grace basis.
4. **Soft retire.** Orphaned tasks are marked disabled, `retired_at`/
   `retirement_reason` are recorded, and lease metadata is cleared. Per-node
   runtime rows (`cluster_tasks_scheduled_node_state`) are also deleted, since they
   are only meaningful for a live `each_capable_node` definition — so a later
   reclaim starts fresh and `ListClusterScheduledTaskNodeStates` shows no ghost
   rows. (For the same reason, changing a task's scope to `single_owner` via
   `upsert` clears any leftover per-node rows.) Retired tasks stay visible in
   Operations (shown with a "retired" status) so operators can see that the
   definition disappeared from code.
5. **Purge later.** Retired tasks are retained for
   `epistola.cluster.scheduled-tasks.retired-retention-ms` (default 7 days) and then
   hard-deleted. Deleting the definition cascades per-node runtime state
   (`cluster_tasks_scheduled_node_state`) and registrations
   (`cluster_scheduled_task_registrations`).

Startup reconciliation alone is not sufficient. In a rolling deploy, new nodes can
start while old nodes are still active, correctly skip retirement because an old
node still vouches, and then never retry after the old nodes leave. The cleanup
work therefore runs as a native `single_owner` scheduled task,
`core.scheduled-task-reconciliation` (`ClusterScheduledTaskReconciler`, default
hourly), that reruns the same reconciliation after the fleet has stabilized. The
reconciler is itself a code definition, so it always vouches for itself and is
never retired.

Manual scheduled-task rows (`management_mode = 'manual'`) are out of scope for
automatic retirement: a manual or operator-created task is never deleted merely
because it is absent from code. There is no manual-creation surface today — all
current callers register `code` tasks — so the mode future-proofs operator-created
rows.

## Ordering Guarantees

Timers do not provide global ordering.

Within a single timer key, updates are serialized by the row. A claimed timer
cannot be claimed again until it completes, is rescheduled, fails back to
scheduled state, or its lease expires.

Across different timer keys or task keys, ordering is best-effort by due time
and key during candidate selection, but parallel nodes and leases mean handlers
must not depend on cross-timer ordering.

If strict domain ordering is needed, model it in the domain data or use a
single stable key whose handler advances one durable state machine step at a
time.

## Capability And Affinity

Both one-shot timers and scheduled tasks carry `required_capability`.

Today the default capability is `suite`. Future node types, such as a slim PDF
renderer process, can advertise different capabilities. A timer requiring that
capability will only be owned and claimed by nodes that advertise it.

Affinity is based on `routing_key`. The same routing key should usually map to
the same node while the active capable node set is stable. This keeps caches hot
and avoids unnecessary movement. If that node disappears, ownership naturally
moves to another active capable node.

## Time And Tests

Runtime time comes from `EpistolaClock`, which resolves through
`MediatorContext`. Timer and scheduled-task pollers bind same-thread work with
`MediatorContext.runWithMediator(mediator)`, so handlers have mediator context
and the current application clock. Use `MediatorContext.runnable(...)` or
`MediatorContext.callable(...)` when work crosses an executor or callback
boundary.

Integration tests use a per-test `MutableClock` bound through
`EpistolaClockExtension`, and the deterministic scheduling substrate instead of
the wall-clock loop (see "Scheduling Substrate" above). `IntegrationTestBase`
exposes both:

```kotlin
// time passes AND due timers/scheduled tasks run, synchronously, in test time
scheduling.advanceTimeBy(Duration.ofHours(25))

// run what is already due without moving the clock
scheduling.runDue()

// move time without firing anything
testClock.advanceBy(Duration.ofMinutes(5))
```

`runDue()` loops timers and scheduled tasks to quiescence, so a handler that
schedules immediately-due follow-up work also runs before it returns. Engine
tests can still drive a single cycle directly (`scheduler.poll()`). A test that
genuinely needs the production loop can opt back in with
`@TestPropertySource(properties = ["epistola.cluster.scheduling-substrate=wall-clock"])`.

Document generation jobs drain explicitly, not on enqueue. Production relies on
the recurring `core.document-job-poller` scheduled task (FixedDelay) plus
completion-chaining, so a freshly enqueued request starts within the poll
interval — there is intentionally no enqueue-time nudge of the local node's
poller (which would assume the enqueueing node can render). For deterministic or
targeted draining, `JobPoller.drainTenant(tenantKey)` claims and runs only that
tenant's PENDING jobs synchronously on the calling thread; tests drive it via
`IntegrationTestBase.drainGenerationJobs(tenant.id)` and otherwise do not render
documents at all.

## Operational View

The Operations -> Cluster page shows:

- active and stale nodes
- node capabilities
- known timers
- known scheduled tasks, including a status of active, disabled, or retired (a
  retired task's code definition disappeared and the row is awaiting purge)
- per-node scheduled-task state for all-node tasks
- required capabilities for timers and scheduled tasks

This page is the first place to inspect whether work is stuck because no active
node advertises the required capability, a handler is failing, or a lease is
still active.

## TODO

### Safety And Conventions

- Document timer creation conventions for new call sites: stable `timerKey`,
  stable `routingKey`, explicit `timerType` or `taskType`, tenant-scoped unless
  truly system-wide, explicit `requiredCapability`, and idempotent handlers.
- Add payload versioning conventions. Timer payloads are JSON, so long-lived
  timers need validation and a migration story across deployments.

### Operations

- Add operations controls for retry-now, cancel timer, trigger scheduled task
  now, enable scheduled task, and disable scheduled task.
- Surface payload, last error, lease owner, lease expiry, attempt count, and
  required capability clearly in the Operations -> Cluster page.
- Consider an admin-only stale lease action, but keep normal recovery based on
  lease expiry.

### Retry And Failure Policy

- Add configurable retry policy beyond the current fixed retry delay.
- Support max attempts or max consecutive failures.
- Add a dead-letter or paused-failed state so permanently failing work does not
  retry forever.
- Make manual retry from operations clear and auditable.

### Scheduled Task Lifecycle

- Automatic retirement and purge for disappeared code-defined scheduled tasks is
  implemented (see "Scheduled Task Lifecycle" above).
- Keep scheduled tasks as durable recurring definitions, not chains of one-shot
  timer rows.

### Scaling And Fairness

- Review query plans and indexes against production-like timer volumes.
- Add production-like load tests for many due timers/tasks, multiple capable
  nodes, mixed capabilities, and handler failures.
- Add regression coverage for fairness and starvation once bounded parallel
  dispatch or per-type limits exist.
- Add jitter or wakeup mechanisms if polling latency or thundering herds become
  visible. PostgreSQL `LISTEN/NOTIFY` or direct node wakeups should only be
  latency optimizations; PostgreSQL rows remain the durable source of truth.
- Decide whether dispatch should remain sequential per node or use bounded
  parallelism.
- If parallel dispatch is added, preserve per-routing-key serial execution when
  a handler or domain model requires it.
- Add fairness controls if one timer type or capability can starve others.

### Migration Candidates

- Keep Spring `@Scheduled` usage limited to `WallClockClusterSchedulingDriver`,
  the production substrate that ticks the heartbeat, one-shot timer, and
  recurring scheduled-task engines.
- Prefer native scheduled tasks for recurring business work. Choose
  `single_owner` when duplicate execution is noisy, expensive, or incorrect;
  choose `each_capable_node` when every capable process needs local work.
- Keep document generation execution on its existing row-claiming path until the
  PDF renderer split needs capability-aware claiming; the native scheduled task
  only provides the per-node drain wakeup.

### Future Architecture

- Use timers as wakeups for durable processes instead of modeling long-running
  sagas as handlers that repeatedly reschedule themselves.
- Keep cache invalidation fanout separate from timers. Fanout events should use
  an event stream where every node can observe relevant events.
- Define the capability taxonomy before introducing non-suite workers, for
  example `suite`, `pdf-render`, and possibly maintenance-specific
  capabilities.
- Expand clock and mediator context coverage so all timer-created background
  work has consistent time, security, and mediator behavior.

## Current Boundaries

Timers are for delayed and recurring background work. They are not a general
message bus and they do not broadcast to every node.

Current non-goals:

- cross-node cache invalidation fanout
- distributed command routing for normal request-path commands
- durable multi-step sagas/processes
- node-to-node wakeups

Those can build on the same node registry and clock/mediator background context,
but they should remain separate concepts.
