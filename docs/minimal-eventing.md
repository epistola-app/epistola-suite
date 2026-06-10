# Minimal Eventing

## Summary

Epistola should use PostgreSQL as its first distributed event substrate. The
goal is not to build a full message bus immediately. The first useful version
is a small append-only event stream that every node can tail in a background
thread.

This gives us:

- fast wakeups for poll-based cluster subsystems
- cross-node cache invalidation
- a foundation for durable processes and sagas
- an audit/debug trail that is more explicit than the current command log

The stream is a correctness aid, not the only correctness boundary. Timers,
scheduled tasks, and future durable processes still keep their durable state in
their own tables. Event delivery can be at-least-once and wakeup events can be
missed, because regular polling remains the fallback.

## Current State

The current mediator publishes local Spring `CommandCompleted` events after a
command handler succeeds. `EventLogSubscriber` persists those completed
commands into `event_log` after commit.

That is useful as an audit trail, but it is not yet distributed eventing:

- events are command-shaped rather than explicitly modeled facts
- only the local process sees the Spring event
- `event_log` is not tailed by other nodes
- subscribers cannot cheaply track their own cursor
- runtime wakeups, cache invalidation, and durable processes are not integrated

We are not married to the current `event_log` table shape. Minimal eventing can
replace it or migrate it into a new `events` stream.

## Event Envelope

The event stream should store an envelope that can be filtered before payload
deserialization.

Recommended columns:

- `sequence`: monotonic `BIGSERIAL` stream position
- `event_id`: UUID idempotency key
- `topic`: coarse category, for example `cluster`, `timers`, `templates`
- `event_type`: stable event name
- `event_version`: payload contract version
- `tenant_key`: tenant scope, `NULL` for system/cluster/runtime events
- `routing_key`: affinity key for future stateful handlers
- `entity_type`: optional affected entity type
- `entity_id`: optional affected entity id
- `causation_id`: event or command that caused this event
- `correlation_id`: workflow/request correlation id
- `producer_node_id`: node that appended the event
- `occurred_at`: application time when the event was recorded
- `payload`: JSONB payload
- `metadata`: JSONB metadata for diagnostics and tracing

`tenant_key` should stay nullable for now, matching `event_log`,
`cluster_timers`, and `cluster_tasks_scheduled`:

- `tenant_key IS NOT NULL`: tenant/business event
- `tenant_key IS NULL`: system, cluster, or runtime event

The system-tenant question remains open in
<https://github.com/epistola-app/epistola-suite/issues/528>. If we later add a
first-class system tenant, we should decide whether event rows migrate away
from `NULL` or whether platform/runtime scope remains separate from tenant
identity.

## Event Types

Completed commands are facts, but they are facts about requests completing. They
are not always the best contract for subscribers.

The stream should support both:

- `command.completed`: generic audit/control fact for a successful command
- explicit domain/runtime facts, for example:
  - `cluster.timer.changed`
  - `cluster.scheduled-task.changed`
  - `cluster.membership.changed`
  - `template.published`
  - `catalog.imported`

Phase 1 can lean on command-completed events for simple wakeups where command
shape is enough. Durable business processes should move toward explicit facts
so handlers do not depend on command class shapes or command result payloads.

## Publishing

Commands should append events after their state change commits. Publishing an
event before the associated state commit would let other nodes wake up and read
stale data.

The first implementation can keep the existing mediator flow:

1. command handler mutates domain/runtime tables
2. command returns successfully
3. after commit, an event publisher appends one or more events to `events`
4. every node eventually tails those rows

For command-completed audit facts, the mediator can publish one generic event
automatically. For explicit domain/runtime facts, handlers should publish them
deliberately when a stable fact is worth subscribing to.

Writing events after commit creates a small crash gap: the command may commit
but the event append may not happen if the process dies immediately after
commit. For phase 1 wakeups and cache invalidation this is acceptable because
polling and database reads remain authoritative. If a future business process
requires no gap, that command should use a transactional outbox write inside
the same transaction.

## Consuming

Every node runs a local event tailer. The tailer does not claim events. It
maintains its own cursor and reads events in sequence order.

The hot path should be:

1. read a batch of envelopes where `sequence > last_seen_sequence`
2. inspect only cheap fields: `sequence`, `topic`, `event_type`, `tenant_key`,
   `routing_key`, and ids
3. check local subscription indexes
4. skip irrelevant events without reading or deserializing payload JSON
5. load/deserialize payload only for interested handlers
6. invoke local handlers through `BackgroundExecutionContext`
7. advance the local cursor

This makes all-node fanout viable for control and business events. It should
not be used blindly for very high-volume hot streams where every node would
scan millions of irrelevant rows per second.

## Wakeup Handlers

The first practical subscribers should wake existing pollers early.

Suggested local handlers:

- `cluster.timer.changed` or completed `ScheduleClusterTimer` /
  `CancelClusterTimer`: request an immediate timer poll
- `cluster.scheduled-task.changed` or completed
  `TriggerClusterScheduledTaskNow` / `EnableClusterScheduledTask` /
  `DisableClusterScheduledTask` / `UpsertClusterScheduledTask`: request an
  immediate scheduled-task poll
- `cluster.membership.changed`: request timer and scheduled-task polls so
  ownership is recalculated quickly

These handlers should not execute timer work directly. They should only signal
the poller to run its normal claim path. PostgreSQL leases remain the
correctness boundary.

Node death cannot publish an event, so heartbeat idle-timeout polling remains
required for failover.

## Poller Wakeup Shape

Cluster pollers currently run on fixed Spring schedules. To support event
wakeups without duplicating logic, each poller should expose a small internal
request method, for example:

```kotlin
fun requestPoll(reason: String)
```

The implementation should coalesce wakeups so a burst of events schedules at
most one immediate poll while a poll is already queued or running. The existing
scheduled poll remains enabled as a backstop.

## Ordering And Delivery

Phase 1 guarantees should be intentionally modest:

- global stream order is by `sequence`
- delivery to local handlers is at-least-once
- handlers must be idempotent
- missed wakeup events are tolerated
- no exactly-once guarantee
- no cross-event transactional side effects

For runtime wakeups and cache invalidation, this is enough. For durable
business workflows, the future saga/process layer should store process state,
processed event ids, and leases in its own tables.

## Stateful Processes And Sagas

Minimal eventing is the input stream for durable processes, but it is not the
durable process implementation.

A future saga layer should:

- start or resume process instances from events
- keep process state in a durable table
- use `routing_key` for affinity
- use leases for one-node execution
- store processed `event_id`s for idempotency
- use one-shot timers for delayed wakeups

In that model, a process wakes from either:

- an event it subscribes to
- a timer/scheduled task that becomes due

The event tailer should only route the signal. The saga table decides whether a
process step may run.

## Phase 1 Scope

Build the smallest useful version:

1. Introduce the new event stream table or migrate `event_log` to the new
   envelope.
2. Append `command.completed` events after successful commands.
3. Add explicit cluster runtime events for timer, scheduled-task, and membership
   changes where command-completed facts are not enough.
4. Add a per-node event tailer with local cursors and fast envelope filtering.
5. Add local wakeup handlers for timer and scheduled-task pollers.
6. Keep fixed-interval polling as the correctness fallback.
7. Document that event handlers must be idempotent and fast.

Do not build durable sagas, distributed command routing, direct node-to-node
transport, or a general cache invalidation framework in this first slice.
