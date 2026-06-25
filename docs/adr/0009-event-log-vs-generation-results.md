# ADR 0009: `event_log` vs. `generation_results` — overlap, redundancy, and convergence

- **Status:** Proposed
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

**Open — recorded as _Proposed_ for the team to ratify.** This ADR is a scaffold capturing the
question RC1 deliberately deferred; no option is ratified yet.

Leaning (not binding): **Option A** as the immediate step — it removes the largest, reader-less,
PII-heavy chunk of `event_log` writes at the source with a small, reversible change, without
prejudging the bigger "does `event_log` survive `audit_log`" question. If, after Option A,
`event_log` still has no consumer by the time replay is concretely scoped, escalate to **Option
C** (drop it) rather than carrying a permanently-unread store. Choose **Option B** only if a
uniform "every command is logged, no exceptions" rule is valued above the duplication.

## Consequences

- **If A:** add a `NotEventLogged` marker and apply it to the generation commands; document the
  two-marker model (`NotAudited` for `audit_log`, `NotEventLogged` for `event_log`).
  `generation_results` is unaffected.
- **If B:** no change; this ADR is closed as "intentionally keep the duplication", and the
  redundancy is documented so it is not re-litigated.
- **If C:** remove `event_log` + `EventLogSubscriber` + the persistence path and the migration;
  confirm nothing has grown a dependency on it; `audit_log`/`generation_results` remain.
- The RC1 partitioning/retention/UUIDv7 work on `event_log` stands regardless — it bounds the
  table under any option and is not wasted even if C is chosen later.

## Related

- [`docs/eventing.md`](../eventing.md) — current `event_log` shape and the by-id caveat.
- [`docs/minimal-eventing.md`](../minimal-eventing.md) — the speculative tailable-event-stream
  direction (Option D).
- [`docs/audit-log.md`](../audit-log.md) — the PII-free audit trail that overlaps `event_log`'s
  audit role.
- `EmitGenerationResult` / `generation_results` collect protocol — the generation feed and its
  external-consumer contract.
