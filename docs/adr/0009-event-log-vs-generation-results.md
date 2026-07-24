<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0009: `event_log` vs. `generation_results` — overlap, redundancy, and convergence

- **Status:** Accepted — **Option A** (keep `event_log`, exclude the generation path)
- **Date:** 2026-06-25
- **Deciders:** Epistola team
- **Tags:** eventing, generation, data-retention, pii, architecture

## Context

The suite has **four** append-only "log" tables, each written for a different reason.
This ADR is only about the two that **overlap on document generation** — `event_log`
and `generation_results` — but the other two are named to keep the boundaries clear:

| Table                    | Written when                                                                              | Read by                                                                                                                             | Partitioned                      | Retention                                         | PII                               |
| ------------------------ | ----------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- | -------------------------------- | ------------------------------------------------- | --------------------------------- |
| **`event_log`**          | every successful command (`CommandCompleted`, AFTER_COMMIT) — 1 row, full command payload | **nobody** (no query/UI/replay path today)                                                                                          | monthly (RANGE on `occurred_at`) | `event-log-retention-months` (default 6)          | **yes** — full command payload    |
| **`generation_results`** | each finished document (`EmitGenerationResult`)                                           | **external consumers** via the `/generation/collect` protocol (routing partitions 0–63, monotonic `sequence`, per-consumer cursors) | monthly                          | `generation-results-retention-months` (default 1) | minimal (metadata, no input data) |
| `audit_log`              | every command/audited query                                                               | the audit UI                                                                                                                        | monthly                          | forever                                           | **no** (deliberately PII-free)    |
| `application_log`        | Logback events                                                                            | the logs UI                                                                                                                         | no                               | ~1 week                                           | possible (log text)               |

The partitioning/retention/UUIDv7 columns above reflect the state **after** the
RC1 work (see [`docs/eventing.md`](../eventing.md) and the `event_log` migration) —
both tables are now bounded. This ADR does **not** revisit those mechanics; it asks a
narrower question that RC1 deliberately left open.

### The overlap

Document generation writes to **both** tables, by two unrelated paths:

- The per-document `EmitGenerationResult` command and the request-submit command both
  flow through the mediator, so each publishes `CommandCompleted` → `EventLogSubscriber`
  writes an `event_log` row carrying the **full command payload, including the input
  `data`**.
- `EmitGenerationResult`'s handler **also** writes the terminal result to
  `generation_results`, which is the row external consumers actually read.

So a single generated document produces **2+ fat `event_log` rows (with the input data)
plus 1 `generation_results` row**. The `generation_results` row is consumed; the
`event_log` rows are not consumed by anything. `event_log`'s stated reason to exist is
"foundation for future replay/recovery", but that foundation currently has **no
consumer**, while it is the single largest source of `event_log` volume and PII.

`generation_results`, by contrast, has a **specific, load-bearing protocol** that
`event_log` does not: routing-partition assignment, a globally-monotonic `sequence`, and
per-(consumer, partition) acknowledgement cursors, designed so an external system (e.g.
the Valtimo plugin) can tail results exactly-once across restarts and DB resets.

### Why this is a decision and not just a cleanup

The two tables answer different questions — "what command happened" (a generic, replayable
audit of _all_ commands) vs. "what document results are waiting for this consumer" (a
purpose-built delivery feed). They are **not** trivially interchangeable: `generation_results`
could not absorb `event_log`'s general-command role without inventing payload/typing it
doesn't have, and `event_log` cannot serve the collect protocol without the
routing/sequence/cursor machinery it lacks. The real question is whether `event_log` should
keep logging the generation path at all, and more broadly whether `event_log` earns its
keep now that `audit_log` exists.

## Decision drivers

- **Redundancy / write amplification.** Generation already persists its authoritative state
  in `documents` + `generation_results`; the extra `event_log` rows duplicate the input data.
- **Distinct consumers.** `generation_results` has a real external consumer and a correctness
  contract; `event_log` has none.
- **PII surface.** `event_log` stores full command payloads (input data) — the reason it is
  now retention-bounded. Less standing PII is better.
- **Replay intent.** Is "replay/recover any command from `event_log`" a real near-term goal,
  or aspirational? If real, `event_log` needs a consumer and a typed payload; if not, it is
  dead weight overlapping `audit_log`.
- **Simplicity / number of stores.** Fewer overlapping append-only stores is easier to reason
  about, back up, and explain.
- **Reversibility.** Adding logging back is cheap; deleting a store/table is a one-way door.

## Considered options

### Option A — Keep both; stop logging the generation path into `event_log`

Introduce a `NotEventLogged` marker (mirroring the audit `NotAudited` marker) and apply it to
`EmitGenerationResult` and the request-submit command, so generation churn no longer lands in
`event_log`. `event_log` remains a general command-replay/forensics store for everything else;
`generation_results` remains the generation feed.

**Pros:** removes the bulk of `event_log` volume and PII at the source; keeps the general
replay foundation for non-generation commands; small, reversible change.
**Cons:** `event_log` still has no reader (its value stays theoretical); two markers
(`NotAudited`, `NotEventLogged`) for adjacent concerns; the overlap is reduced but the
"why does `event_log` exist" question is only deferred.

### Option B — Keep both, log everything (status quo)

Leave the write paths as they are. Both tables are now partitioned and retention-bounded, so
the unbounded-growth risk is already gone; accept the duplication as the cost of a uniform
"every command is logged" rule.

**Pros:** zero code change; one simple invariant (all commands logged); no special-casing.
**Cons:** keeps 2+ fat PII rows per generated document forever-on-a-6-month-window; pure
write amplification with no reader; the redundancy with `generation_results` (and the audit
overlap with `audit_log`) is permanent.

### Option C — Drop `event_log` entirely; `audit_log` + `generation_results` cover the field

Remove `event_log`, `EventLogSubscriber`, and the `CommandCompleted`-persistence path.
`audit_log` already provides the PII-free who/did/what/when trail; `generation_results`
provides the generation feed. Replay-from-full-payload is abandoned (it was never built).

**Pros:** one fewer store; eliminates the PII heap and all the generation write amplification;
no marker needed; the clearest data model.
**Cons:** gives up the (currently unused) "replay any command from its full payload" capability
— if that is ever wanted, it must be rebuilt (likely as a proper typed event stream, cf.
[`docs/minimal-eventing.md`](../minimal-eventing.md)); `audit_log` records _that_ a command
ran but not its full inputs, so some forensic detail is lost.

### Option D — Promote one to a real event stream (out of scope, noted)

A larger redesign: make `event_log` (or a successor) a first-class, tailable event stream with
typed payloads and consumers, and treat `generation_results` as one projection of it. This is
the direction sketched in [`docs/minimal-eventing.md`](../minimal-eventing.md). Recorded here
only to bound this ADR: it is a separate, larger initiative and not a precondition for deciding
A/B/C.

## Decision

**Accepted: Option A — keep `event_log`, exclude the generation path.**

`event_log` is **retained with a defined purpose**, which resolves the "does it earn its keep"
question the scaffold left open: it is the log of **successfully executed commands** — the event
is "this command succeeded" — and is intended to become the **basis for reacting to activity**
(event-driven behaviour) and, later, to carry **other event kinds that are not commands**. So it
is not merely a forensic/replay store; it is the system's nascent event stream. That future
consumer is the reader the scaffold noted was missing, so escalation to Option C (drop it) is
**off the table**.

Two design commitments follow from treating it as an event stream rather than a best-effort log:

1. **The event is part of the command's commit.** Recording "command X succeeded" must be atomic
   with X's state change — a transactional outbox — so a reactor can never observe an effect
   without its event, nor an event without its effect. This **changes the current contract**: today
   the write is `AFTER_COMMIT` in a separate connection with failures swallowed (events can be
   silently lost), which is acceptable for an unread audit aid but **not** for a stream other
   behaviour keys off. See Consequences for why this is a separate, mediator-level change.
2. **Generation is excluded for now.** The per-document generation path is high-volume, PII-dense
   (input `data`), and not relevant to react to at this stage. It is already excluded from
   `audit_log` (via `SystemInternal` / `NotAudited`); `event_log` now excludes it too, via a
   dedicated `NotEventLogged` marker. `generation_results` remains its system of record.

Option B (log everything, incl. generation) is rejected: generation churn is exactly what we do
not want in the stream. Option C (drop `event_log`) is rejected because the store now has a named
future purpose.

## Consequences

**Done in this change (generation exclusion):**

- A `NotEventLogged` marker (in `app.epistola.suite.common`, mirroring `NotAudited`) is added and
  applied to the generation commands (`GenerateDocument`, `GenerateDocumentBatch`,
  `EmitGenerationResult`); `EventLogSubscriber` skips any command carrying it. This establishes the
  **two-marker model**: `NotAudited` opts out of the PII-free `audit_log`, `NotEventLogged` opts out
  of the `event_log` stream — kept independent on purpose, because a command can be audit-worthy but
  not stream-worthy (or vice versa) as `event_log` grows into a general event stream.
- `generation_results` is unaffected and remains the generation system of record.

**Deferred to a focused follow-up (transactional outbox):**

- Making the event write **part of the command commit** is a **mediator-level transaction change**,
  not a subscriber tweak: today there is no uniform command transaction boundary (handlers mix
  `@Transactional`, `jdbi.inTransaction`, and bare `withHandle`), and the write runs post-commit on a
  separate connection. Wrapping dispatch in one transaction so the event and the domain writes commit
  atomically risks double-managing the JDBI/Spring connection for handlers that open their own
  transaction, so it needs its own PR with broad command-path testing. Until then, the
  `AFTER_COMMIT`-with-swallowed-failures contract stands, and **no behaviour should yet depend on
  `event_log` being gap-free.**
- When non-command event kinds are introduced, `event_log`'s shape (currently `event_type` = command
  class name, `payload` = serialized command) generalises to carry them; that schema/typing work is
  part of building the first reactor, not this change.

**Standing:**

- The RC1 partitioning/retention/UUIDv7 work on `event_log` is the right foundation for an event
  stream (bounded growth, time-ordered ids) and is unaffected.

## Related

- [`docs/eventing.md`](../eventing.md) — current `event_log` shape and the by-id caveat.
- [`docs/minimal-eventing.md`](../minimal-eventing.md) — the speculative tailable-event-stream
  direction (Option D).
- [`docs/audit-log.md`](../audit-log.md) — the PII-free audit trail that overlaps `event_log`'s
  audit role.
- `EmitGenerationResult` / `generation_results` collect protocol — the generation feed and its
  external-consumer contract.
