# ADR 0016: Durable process runtime — in-house primitive, not DBOS adoption

- **Status:** Accepted
- **Date:** 2026-08-28
- **Deciders:** Epistola team
- **Tags:** workflow, durable-execution, cluster, background-jobs, architecture

## Context

Six features have each independently built a version of the same shape — durably claim
an item, retry it with backoff, and track its terminal state, safely across cluster
nodes:

- **Exchange catalog-publication outbox** (`exchange/CatalogPublicationStore.kt` +
  `CatalogPublicationWorker.kt`) — the most mature: its own `SKIP LOCKED` claim and
  5-minute lease, exponential backoff capped at one hour, a `FAILED` terminal state after
  `max-attempts`, and admin-triggered requeue with a fresh idempotency key. Documented in
  ADR 0018 and `docs/catalog-exchange-publication.md`.
- **Document generation** (`documents/batch/JobPoller.kt` + `StaleJobRecovery.kt`) — its
  own claim SQL, adaptive batch sizing, a deliberately platform-thread (not virtual-thread)
  executor to avoid a JEP 491 classloader deadlock (#724), and no retry at all: a failure
  goes straight to `FAILED`.
- **Load test runs** (`loadtest/batch/LoadTestPoller.kt`) — a near-duplicate of the above
  against a different table, whose own stale-recovery logic once diverged just enough to
  ship a real bug: staleness judged by claim age rather than progress (#725).
- **Feedback sync** (`epistola-support-feedback/.../FeedbackSyncScheduler.kt`) — a flat
  max-attempts counter with no exponential backoff at all.
- **Backups** (`epistola-support-backups/.../BackupScheduler.kt`) and **upgrading
  snapshots** (`epistola-support-upgrading/.../UpgradingSnapshotScheduler.kt`) — a
  per-tenant sweep loop, not a queue, each with its own hand-coded "abort the whole sweep
  on the first hub-unreachable error" `try/catch { break }` — the same idiom, duplicated
  verbatim.

Two generic, cluster-safety primitives already exist in `epistola-core/.../cluster/`:
`ClusterScheduledTask` (a durable, cluster-safe "elect one owner to run this handler
periodically" primitive — lease-based `SKIP LOCKED` claiming, a hard-deadline watchdog
that force-reclaims a wedged owner, scheduler-liveness election distinct from heartbeat
liveness, and registration-vouching so rolling deploys never orphan a task another node
still carries; ridden by 22 handlers today, hardened by the #723/#724/#725 chaos-test
history) and `ClusterTimer` (a durable one-shot timer, same shape, adopted by zero
features so far). Both answer "who gets to run this," not "how do I durably retry N
individual work items" — that second problem is exactly what keeps getting
reimplemented, and `docs/timers.md` already names it as a deliberately separate,
unbuilt concept ("durable multi-step sagas/processes" is listed as a current non-goal for
timers).

This gap was already anticipated and deferred, not missed: `docs/horizontal-scaling-phase1.md`
sketches a "Phase 1D: Durable Process Runtime" — durable process tables, a handler
registry, a runner, a start API, and history — and even names feedback sync as the
suggested first conversion. It was never built.

The mediator's event mechanism (`mediator/SpringMediator.kt`) is synchronous and
transaction-scoped only (`EventPhase.IMMEDIATE` same-transaction, `AFTER_COMMIT` via
Spring's `TransactionalEventListener`) — not a durable event bus. ADR 0018 already
rejected using it to drive the exchange outbox for exactly this reason: a process crash
between commit and handler firing loses the event. Nothing changed that constraint.

Separately, the team has already evaluated this class of problem once before.
`docs/plans/architecture.md` (2026-03-29) explicitly considered Temporal, Axon Framework,
and Eventuate for the same underlying need and chose incremental, Postgres-native, phased
construction specifically to avoid taking on a new required operational dependency.

The trigger for this ADR was evaluating DBOS 1.0's Kotlin/Spring SDK
(`transact-spring-boot-starter`, offering durable workflows-as-code with automatic
Postgres checkpointing) as a possible alternative to building the deferred primitive
in-house.

## Decision

Build the deferred Phase 1D primitive: a third, narrowly-scoped cluster-coordination
primitive, `durable_processes`, for durably claiming, retrying, and tracking the terminal
state of individual work items — implemented as a `ClusterScheduledTaskHandler`, so it
inherits node liveness, lease correctness, hard-deadline force-reclaim, and
rolling-deploy safety from `ClusterScheduledTask` for free, exactly as
`CatalogPublicationWorker` already does today.

Do not adopt DBOS. Its Kotlin/Spring support is roughly five to six months old against a
codebase that went GA on 2026-07-31 under strict SemVer and data-stability commitments —
too new to take as a foundational, hard-to-reverse dependency for core background-work
infrastructure. More decisively, DBOS's actual value for this problem — automatic
cross-node recovery of orphaned in-flight workflows — is gated behind DBOS Conductor, a
separate proprietary control plane (free tier limited to a single connected executor;
paid tiers from $99/month, priced per organization running its own apps). Epistola ships
as a Helm chart to many self-hosting customers who each operate their own installation;
that licensing shape doesn't fit — every customer would need their own DBOS relationship,
or forfeit the exact capability DBOS would be adopted for. Whether DBOS's own Postgres
"system database" schema can safely cohabit the same database Epistola's Flyway
migrations manage is also unconfirmed from its public documentation, an open risk that
would need a spike before the option is even fairly evaluable.

The new primitive is deliberately narrow in _scope_ — claim → attempt → backoff/retry →
terminal state — but not minimal in _ergonomics_. It deliberately borrows DBOS's single
most valuable feature, per-step checkpointing, through a lightweight `step()` memoization
helper on a `DurableProcessContext` handed to every handler: a multi-step flow is written
as ordinary linear code, and each named step's result is checkpointed to Postgres the
moment it completes, so a crash mid-`attempt()` resumes past already-completed steps
instead of redoing them. This is the same "one database write per step" trade-off DBOS's
own architecture makes, converged on independently rather than copied wholesale: no
generic non-determinism verification, no bytecode instrumentation, just a JSONB map and
trust in the handler author — the same trust model every other idempotency guard in this
codebase already relies on. Queues additionally get a `queue_key` and a code-configured
concurrency cap, giving feature-level flow control close to DBOS's queue semantics
(best-effort FIFO under `SKIP LOCKED`, not a strict-order guarantee).

What it still does not become is a cross-service saga/compensation framework, a
push-based human-in-the-loop signaling system, or a code-version-pinning runtime —
nothing in the codebase today needs any of those, and building them speculatively repeats
the mistake `docs/plans/architecture.md` already steered away from. See
`docs/horizontal-scaling-phase1.md`'s Phase 1D section ("What this deliberately does not
replicate") for the specific list and rationale.

Only two of the six examples are genuine per-item durable-queue cases and are in scope
for the new primitive:

- The **exchange outbox** is the design reference for its shape (backoff formula, terminal
  states, tenant-facing visibility) but is left as-is — it is already correct, tested, and
  documented in ADR 0018. It is not rewritten just for consolidation's sake.
- **Feedback sync** is the first real migration target, per Phase 1D's own original
  suggestion — it is the worst-off duplicate (no lease, no backoff) and the most cleanly
  isolated.

The other four stay off the new primitive, by design, not by oversight:

- **`JobPoller`/`StaleJobRecovery`** and **`LoadTestPoller`** are high-throughput,
  low-latency hot paths with a deliberately different retry profile (zero retry for
  generation; heartbeat-based staleness for load tests) and, for generation, a hard
  platform-thread constraint (JEP 491). A generic per-item JSON-state row would add
  overhead these paths don't need and shouldn't pay.
- **Backups and upgrading snapshots** don't need per-item durable rows at all — they need
  their duplicated "abort the sweep on a circuit-breaking failure" loop idiom extracted
  into one small, shared, schema-free helper. That extraction is independent of
  `durable_processes` and carries no migration risk.

No big-bang rewrite. New features that need per-item durable retry must use the new
primitive going forward — that is the actual point, stopping a seventh reimplementation —
but the existing six migrate opportunistically, only when touched for other reasons or a
real pain point appears.

This decision is revisitable, not permanent. Reconsider DBOS (or a comparable vendor)
if its Kotlin/Spring surface and production track record mature well beyond their current
age, if a self-hosted-Conductor licensing shape compatible with per-customer Helm-chart
distribution becomes available, or if a genuine future requirement emerges for
deterministic multi-step replay, push-based human approval, or cross-service saga
compensation that the narrow in-house primitive cannot reasonably grow into.

## Consequences

- One reviewed, tested claim/lease/backoff/terminal-state implementation instead of an
  open-ended number of bespoke ones; future Exchange-like external integrations get it for
  free instead of becoming a seventh reimplementation.
- Directly forecloses the #725 defect class — one implementation to get right, not many.
- No new required infrastructure, no new operational dependency, no new vendor
  relationship. PostgreSQL remains the sole coordination dependency, consistent with
  `docs/horizontal-scaling-phase1.md`'s own design principle and with Epistola's
  self-hosted-by-many-customers distribution model.
- The new primitive's hardest problems — node liveness, lease correctness, hard-deadline
  force-reclaim, rolling-deploy safety — are already solved and chaos-tested by
  `ClusterScheduledTask`; nothing about them needs to be re-derived.
- Tenant/operator-facing visibility (list, counts by status, oldest-active age —
  currently unique to the exchange outbox) becomes a consistent, built-in capability for
  every future queue-shaped feature instead of a one-off.
- Step memoization means most multi-step handlers can be written as linear code instead
  of a hand-rolled `when (state["step"])` machine, closing most of the ergonomic gap to
  DBOS's headline feature without its Conductor dependency or licensing mismatch.
- Real engineering cost to build and test to the multi-instance chaos-testing bar
  `ClusterScheduledTask` is held to. A vendored library would, in principle, avoid this —
  though DBOS's own gaps (Conductor-gated HA, a licensing mismatch for the distribution
  model) mean it would not actually avoid the hardest part of that work for this product.
- Still forgoes DBOS's genuinely hard-to-replicate capabilities — automatic
  non-determinism detection (this design trusts the handler author instead), code-version
  pinning for in-flight processes across deploys, durable cross-process signaling/child
  workflows, and an externally maintained community and ecosystem. If a real need for
  these emerges later, the in-house primitive needs deliberate extension, or this
  decision needs deliberate revisiting — not a permanently closed door.
- Adds a fourth cluster-coordination concept to the mental model, alongside
  `cluster_nodes`, `cluster_tasks_scheduled`, and `cluster_timers` — mitigated by it being
  additive, and by riding the existing scheduling substrate rather than a parallel one.
- `JobPoller`, `StaleJobRecovery`, and `LoadTestPoller` explicitly do not adopt this
  primitive. This is a stated scope boundary, not a gap; it must stay documented so a
  future contributor does not "helpfully" migrate the highest-throughput path onto a
  heavier generic primitive.

## Alternatives considered

### Adopt DBOS 1.0 now

Rejected. Kotlin/Spring support is roughly five to six months old against a codebase that
just went GA with strict SemVer and data-stability guarantees — too new to take as a
foundational dependency. More decisively, DBOS's free/self-hosted tier cannot provide
cross-node recovery of orphaned in-flight workflows without the proprietary Conductor
product, and Conductor's per-organization pricing does not fit shipping a Helm chart to
many self-hosting customers who each run their own installation — the exact capability
DBOS would be adopted for is gated behind a licensing relationship each customer, not
Epistola, would individually need. Whether its system-database schema can safely cohabit
Epistola's Flyway-managed Postgres is also unconfirmed, a further open risk.

### Adopt Temporal

Rejected. Requires operating a separate Temporal server cluster (or Temporal Cloud) as a
new required piece of infrastructure — a heavier operational and distribution-model
mismatch than even DBOS for a product shipped to self-hosting customers via a single Helm
chart. The team already evaluated and rejected Temporal for this problem space in
`docs/plans/architecture.md`, and none of the six duplicated implementations need true
cross-service durable execution to justify revisiting that.

### Do nothing (keep duplicating per-feature)

Rejected. The cost is already being paid repeatedly and provably: six divergent
implementations of the same shape, one of which shipped a real staleness-detection bug
(#725) precisely because its bespoke recovery logic diverged from the more careful
exchange-publication implementation. Further external integrations are already
anticipated, each a fresh reimplementation risk under this option.

### Build a full generic durable-process/saga engine now

Rejected — distinct from the lightweight step-memoization helper this ADR does adopt.
Nothing in the current codebase needs automatic non-determinism detection/verification,
push-based human-in-the-loop signaling, code-version pinning across deploys, or
cross-service saga compensation. Building those speculatively repeats the mistake
`docs/plans/architecture.md` already steered away from. The exchange outbox already
proves a re-entrant, handler-owned state machine — now with checkpointed steps — is
sufficient for the one case in the codebase that is genuinely multi-step; no generic
replay/verification engine is needed to support it.

## Detailed contract

The implementation-level detail — schema shape, the `DurableProcessHandler` /
`DurableProcessOutcome` interface, the mediator surface, and the migration posture for
each of the six existing implementations — is maintained in
[Horizontal scaling, Phase 1](../horizontal-scaling-phase1.md#phase-1d-durable-process-runtime).
