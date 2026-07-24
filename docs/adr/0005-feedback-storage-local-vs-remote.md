# ADR 0005: Feedback storage — local copy + sync vs. remote-only

- **Status:** Proposed
- **Date:** 2026-06-06
- **Deciders:** Epistola team
- **Tags:** feedback, architecture, hub, data-residency, availability

## Context

The in-app **feedback** feature (users submit feedback from within the suite; it is
triaged centrally and replies/status changes flow back) can store its data two ways:

- **Local store + sync** — the suite holds feedback in its own database and synchronises
  with epistola-hub.
- **Remote-only** — the hub is the sole store; the suite reads and writes feedback through
  the hub on every operation.

Feedback is a **paid-tier feature in both options** (it depends on the hub / support tier).
So this is _not_ an OSS-vs-commercial question, and "is it already built" is not a factor —
this ADR weighs the two designs on their merits.

The decisive context is **how and where the suite runs**:

- The suite is **customer-deployed** in the customer's own infrastructure (on-prem, private
  cloud, managed), frequently with **restricted outbound egress** and its own availability
  envelope.
- The hub is a **central service** the installation phones home to. "Paid users have the
  hub" does **not** imply the hub is always _reachable_ from inside the customer network:
  it is subject to downtime, maintenance, network partitions, egress firewalls, and
  API/version skew relative to the suite.
- The suite UI is **server-rendered (Thymeleaf + HTMX)**. A read that backs a page render
  adds its latency directly to the user-perceived page/fragment latency.
- Feedback payloads can contain **PII**: free-text, source URLs, console logs, and
  screenshots.

So the decision turns on **availability/resilience, latency, data residency, and
operational complexity** — not on feature gating.

### Decision drivers

- **Availability of an interactive surface.** Submitting and viewing feedback is an in-app
  action; how gracefully does it behave when the hub is unreachable?
- **Latency.** Server-rendered reads put any per-operation network round-trip on the render
  path.
- **Data residency / privacy.** Feedback carries PII; does the customer's data stay in the
  customer's database, or only centrally?
- **Coupling.** Does the suite's runtime (a core in-app surface) depend on the hub's
  availability and API version?
- **Consistency / single source of truth.** How much divergence between suite and hub is
  acceptable, and who reconciles it?
- **Operational complexity & failure modes.** How much sync machinery must be built, run,
  and debugged?

## Considered options

### Option A — Local store + sync (hub is an aggregation copy)

The suite's database is the system of record for that installation. Feedback is written and
read locally; a sync layer mirrors it to the hub and pulls hub-side changes back. Concretely
this entails an outbound push with retry, an inbound poll with a persisted cursor, dedup by
external id, and loop-avoidance so polled-back changes aren't re-emitted (the shape of the
existing `FeedbackSyncPort` design — described here as _what Option A costs_, not as a reason
to choose it).

#### A — Pros

- **Resilient in-app behaviour.** Submitting and viewing feedback keep working when the hub
  is down, partitioned, or blocked by egress rules; the sync catches up later. No user-facing
  action is lost to a transient hub outage.
- **Low, predictable latency.** Reads and writes hit the local DB; the HTMX render path never
  waits on a cross-service call.
- **Data residency by default.** The customer's feedback (including PII/screenshots) lives in
  their own database; the hub copy is an addition, not the only home.
- **Runtime decoupling.** A core suite surface does not depend on hub availability or on
  matching the hub's API/contract version at request time.

#### A — Cons

- **Two stores ⇒ a sync engine.** Push, retry, inbound poll cursor, dedup, and
  status-change loop-avoidance are real code with real failure modes to run and debug.
- **Eventual consistency.** There are divergence windows; conflicting edits (e.g. a status
  changed on both sides) need a resolution rule.
- **Duplication.** Feedback is stored (and backed up) in both places.
- **Schema modelled twice.** The feedback model exists as suite tables, hub tables, and the
  gRPC contract; changes ripple across all three.

### Option B — Remote-only (suite is a thin client)

The hub is the single system of record. The suite stores no feedback locally; every submit,
list, and detail view is a call to the hub.

#### B — Pros

- **One source of truth.** No sync engine at all — no cursors, dedup, poll-back, or
  divergence — and far less code to build and operate.
- **Always consistent.** Operator triage and the in-suite view see identical data
  instantly; no reconciliation.
- **Modelled once.** Feedback lives in the hub schema + the contract only.
- **Native aggregation.** Cross-installation views are inherent; nothing to merge.

#### B — Cons

- **Hub on the critical path for every operation.** If the hub is down, unreachable, or
  egress-blocked, feedback is **fully unavailable — including viewing existing history**, not
  just submitting.
- **Latency on the render path.** Each list/detail/submit is a cross-service gRPC call
  (network RTT + hub load) added directly to server-rendered page/fragment latency.
- **Runtime coupling.** A core suite surface now degrades with hub outages and must stay
  compatible with the hub's API/version at request time.
- **Data residency.** Customer feedback (PII/screenshots) lives only centrally, which some
  customers' compliance/residency requirements may not allow.
- **Transient-loss at submit.** If the hub is briefly unavailable exactly when a user
  submits, the item is lost — unless the suite buffers it locally, which reintroduces local
  storage and drifts toward Option A.

### Option C — Outbox-only hybrid (brief)

The suite persists only a local **delivery buffer/outbox** for submissions (so a submit is
durable and survives a hub blip), but **reads** come from the hub (hub = read system of
record). This buys submit-durability without maintaining a full local read mirror, but the
view path is still coupled to hub latency/availability, and it adds a (smaller) amount of
the same delivery machinery. Recorded as the middle ground, not the primary recommendation.

## Decision

**Recommendation: Option A (local store + sync), recorded as _Proposed_ for the team to
ratify.**

For an _interactive, in-app_ surface that ships inside customer infrastructure, the dominant
risk is hard runtime coupling of every read and write to a central service (Option B):
availability of a basic in-app action becomes a function of cross-network hub reachability,
render latency inherits the hub round-trip, and PII leaves the customer's database by
construction. Option B's transient-loss-at-submit problem also pushes designs back toward
local buffering, i.e. toward Option A anyway. Option A's cost — the sync engine and eventual
consistency — is real but **bounded and well-understood**, and it is paid on the
infrastructure/operations side rather than on every user interaction.

Choose **Option B** instead only if central, always-consistent state and minimal moving
parts are valued above in-app resilience, latency, and data residency — for example if
feedback is reframed as an operator-facing tool rather than an in-suite surface, or if all
target customers are known to have reliable, low-latency, unrestricted hub connectivity and
no residency constraints.

### Why not Option C?

The outbox hybrid removes Option A's read mirror but keeps the view path coupled to the hub
(the worst of B for _reads_) while still carrying delivery machinery. It is attractive only
if viewing feedback in-suite is deemed non-critical while submission must be durable — a
narrower bet than either A or B. Keep it on the table as a fallback if read-coupling later
proves acceptable.

## Consequences

### If Option A is ratified (recommended)

- The suite retains feedback tables as the system of record; the hub holds an aggregation
  copy. The sync layer (push + retry + inbound poll cursor + dedup + loop-avoidance) is a
  standing component to operate and test.
- A conflict-resolution rule must be stated for fields editable on both sides (e.g. status):
  define last-writer-wins or a precedence (hub-triage wins) and document it.
- The hub's feedback API stays a _sync_ contract (idempotent submit, fetch-since), not a
  read-serving API for the suite UI.

### If Option B is ratified instead

- The suite's local feedback tables and the entire sync layer are removed; the suite UI
  calls the hub for every feedback operation, and must handle hub-unavailable states
  gracefully in the UI (degraded/empty states, submit buffering or explicit failure).
- The hub becomes latency- and availability-critical for a user-facing surface — this raises
  the bar on hub performance and uptime (see hub scale concerns).
- Data-residency expectations for feedback content must be confirmed acceptable for all
  customers.

### Forward-compatibility

- A→B is a one-way simplification (drop local store + sync) that is straightforward later if
  coupling proves acceptable. B→A is harder (reintroduce a store + sync), so when uncertain,
  starting from A preserves more optionality.
- Either way, feedback's outbound contract to the hub stays the same shape (submit + fetch
  updates), so the hub side and the operator-triage surface are unaffected by this choice.

### Related

- epistola-hub#2 (operator UI placement) — defines where hub-side triage/replies are
  produced, which is what the inbound direction consumes.
- epistola-hub#6 (performance & scale) — Option B would make hub read latency/availability a
  user-facing SLO for the suite, materially raising the scale bar.
