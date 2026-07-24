<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0012: Editor check-run lifecycle — debounce, coalescing, and run-state

- **Status:** Draft — discussion record, not accepted
- **Date:** 2026-07-18
- **Discussants:** Epistola team
- **Tags:** quality, editor, ux, concurrency, cluster, scheduling

## Context

ADR 0011 explores **how a check gets its input**. This ADR explores the orthogonal question: **how
check runs might be scheduled, de-duplicated, and observed** — specifically for the editor, where a
person is actively editing a draft and expects to see whether it is being checked.

Two concrete requirements drive it:

1. **The author wants to know whether their current draft is being checked** — a "Checking…"
   state, and honesty about whether the findings on screen are for the draft as it is _now_ or an
   older revision.
2. **Rapid saves must not pile up check runs.** The editor autosaves often; naively triggering a
   check per save produces a backlog of runs, most of them stale before they finish, and — once
   expensive or remote checks exist — wasted load.

Both interact with facts established elsewhere: the suite is **multi-node** (a user's saves can
land on different nodes, and the background sweep can race an editor-triggered run for the same
draft); findings already carry an `input_fingerprint`, and `GetFindingsForSubject` returns the
draft's `currentInputFingerprint`, so **staleness is already computable**; and ADR 0011's
**event-driven-primary** model (check on change, sweep as backstop) _needs_ a coordination layer to
stop overlapping runs — that layer is what this ADR defines.

## Evaluation drivers

- **UX honesty.** The panel must never imply findings are current when the draft has moved on, and
  must show when work is in progress rather than looking frozen.
- **No redundant work.** At most one running check per draft; a save during a run must not spawn a
  second run, only mark that a re-check is due.
- **Cluster-safety.** Single-flight must hold across nodes, not per-node — an in-memory flag is
  wrong the moment two nodes are involved.
- **Self-healing.** A node that dies mid-run must not leave a draft stuck in "Checking…" forever.
- **Cost-tiering.** Cheap local checks can run on autosave; expensive/remote checks must not — they
  belong to publish / explicit "check now", never the keystroke loop.
- **Reuse.** Staleness (fingerprints) and cluster lease/recovery (the scheduler pattern) already
  exist; this should compose them, not reinvent them.

## The four levers

"Backoff" is imprecise; the anti-pileup answer is four distinct levers, applied in order:

1. **Debounce (client).** The editor waits a short quiet period (~2 s) after the last change before
   requesting a check. Collapses a burst of saves into one request; most rapid saves never reach
   the server as a check at all. Zero server state.
2. **Single-flight + latest-wins coalescing (server).** At most one check _running_ per subject and
   at most one _pending_. A request arriving while a run is active does not queue a second run — it
   records "re-check needed, against fingerprint _F_". When the active run finishes, if the pending
   fingerprint differs from what just ran, the framework runs **once** more against the latest.
   This is a conflated queue, not a growing one.
3. **Trigger-tiering.** Autosave runs only the **cheap local** sources. The **expensive/remote**
   sources (hub checks, PDF renders) are **not** in the autosave loop at all — they run on publish,
   on engine upgrade, or on an explicit "check now". The priciest work is structurally excluded
   from rapid editing.
4. **Throttle (optional).** A minimum interval between runs, added only if the debounced cheap loop
   itself proves too hot. Not needed initially.

## The run ledger

A dedicated, source-and-subject-keyed record — the "run ledger" that ADR-era design deferred, now
justified by being _both_ the coalescing coordinator and the run-state the editor reads. Source
granularity matters because a millisecond local check and a minute-long remote PDF check must not
collapse into one vague "Checking…" state:

```
quality_check_runs
  tenant_key                 -- FK, ON DELETE CASCADE
  source_id                  -- one source, or a reserved local-bundle id for the cheap local tier
  subject_urn                -- the draft/variant (or RENDER) subject
  state                      -- IDLE | QUEUED | RUNNING | FAILED
  active_run_id              -- UUID correlation id for the queued/running attempt
  running_fingerprint        -- input fingerprint the active run is checking
  last_completed_fingerprint -- input fingerprint of the last finished run
  last_completed_at
  last_failed_at
  last_error_code
  recheck_requested_fingerprint  -- set when a change arrives mid-run (coalescing)
  lease_owner                -- node id holding the run
  lease_expires_at           -- for crash recovery
  updated_at
  PRIMARY KEY (tenant_key, source_id, subject_urn)
```

- **Single-flight** is claiming the row's lease: a node starts a run only if `state = IDLE` or the
  lease has expired; it sets `active_run_id`, `RUNNING`, `running_fingerprint`, `lease_owner`,
  `lease_expires_at` in one conditional update. A loser records `recheck_requested_fingerprint`
  instead.
- **Coalescing:** on completion, the owner clears the lease, writes `last_completed_*`, and — if
  `recheck_requested_fingerprint` differs from what it just ran — triggers exactly one more run.
- **Self-healing:** an expired lease is reclaimable; the periodic full sweep (the backstop) also
  clears stuck rows. "Checking…" cannot get stuck forever.
- **It is coordination state, not findings.** Findings stay in `quality_findings`; this row only
  tracks _run_ progress. Deleting it loses no findings.

## The run-state machine (what the editor shows)

Run state (this table) plus staleness (fingerprints, already available) give four honest states:

| State             | Condition                                                        | Panel                                  |
| ----------------- | ---------------------------------------------------------------- | -------------------------------------- |
| **Running**       | any relevant row is `QUEUED` or `RUNNING` for this subject       | "Checking…" for that source/tier       |
| **Up to date**    | idle and `last_completed_fingerprint == currentInputFingerprint` | findings · "checked just now"          |
| **Outdated**      | idle and `last_completed_fingerprint != currentInputFingerprint` | findings · "may be outdated · recheck" |
| **Never checked** | no row (or no completed run)                                     | "not yet checked"                      |
| **Failed**        | last run failed and no newer completed run exists                | findings · "check failed · retry"      |

The editor reads this via a small status endpoint the panel polls / refreshes (HTMX), the same way
findings are read — never by computing on the request path.

## Candidate direction

- Introduce the **`quality_check_runs`** table as above (dedicated, not derived from findings — a
  derived approach cannot express `QUEUED`/`RUNNING`/`FAILED` nor hold a cluster-wide single-flight
  lock, so it cannot deliver either requirement).
- Apply the four levers: **client debounce**, **server single-flight + latest-wins coalescing**
  (via the lease), **trigger-tiering** (autosave = cheap local only), throttle deferred.
- Surface the **run-state machine** in the editor panel; reuse existing fingerprint staleness for
  Up-to-date vs Outdated, and the scheduler's lease/expiry pattern for cluster-safety and recovery.
- This would be the coordination layer ADR 0011's event-driven-primary model relies on; 0011 points
  here.

**Potential scope:** build with the **editor panel (Phase 2)** if this direction is accepted. It is
recorded now because it shapes the panel's contract and because the run ledger is a schema
commitment worth deciding deliberately.

## Consequences

- One small table and a status endpoint; the check runner learns to claim/renew/release the lease
  and to honour a coalesced re-check. The reconciliation upsert is unchanged (still correct under a
  race; the lease just prevents the wasteful double-run and the flap).
- The run ledger also answers "checked N minutes ago" — the thing previously indistinguishable from
  "never checked" — for the report as well as the editor.
- Remote/async checks (hub, PDF) keep their own run rows per subject so their progress is visible
  too ("PDF conformance: checking on the server…"), but they are out of the autosave loop by
  construction (trigger-tiering) — their latency never touches the editing experience. Their result
  path must echo `run_id` and `input_fingerprint`; a timeout/failure never auto-resolves old
  findings.
- **Not built:** the throttle lever; a push/subscribe channel for run-state (polling suffices at
  first).

## Related

- [ADR 0011](0011-quality-check-input-model.md) — how a check gets its input; its event-driven
  model relies on this coordination layer.
- [`docs/cluster-resilience.md`](../cluster-resilience.md) and the cluster scheduler — the
  lease/expiry/recovery pattern reused here.
- [`docs/quality.md`](../quality.md) — the ledger, staleness (`input_fingerprint`), and triggers.
