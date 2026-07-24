# v0.4 — Coordinated rebalance for `/generation/collect`

## Status

**Design — not yet implemented.** v0.3 ships with documented "eventual
affinity, immediate correctness" behavior. This document captures the
v0.4 design that closes the affinity gap.

## Problem

The v0.3 collect protocol uses a consistent hash ring to assign partitions
to consumer nodes. When a new node joins (or an existing one leaves), the
server recomputes the ring **immediately**. Other nodes only learn about
the new ring on their next poll.

Between the new node's first poll and the existing node's next poll —
bounded by one poll interval (≤ 1s at default `minInterval`) — an existing
node's cached `partitionAssignment.mine` is stale relative to reality.

The user-visible symptom is a possible **loss of submitter affinity during
a rebalance**: a result a node submitted with `routingKeyToMe(...)` may
end up collected by a different node, because the partition the result
landed on changed ownership between submit and collect. At-least-once
delivery means correctness is unaffected — the result still flows
through — but the BPMN-process-on-same-node story briefly breaks.

The misroute window is short and self-healing in v0.3, so v0.3 ships as
is and documents the behavior. v0.4 closes it.

## Design — wall-clock grace + future-routing

The server tells every polling node when an upcoming ring will take
effect; nodes locally schedule the switch. Same family of trick used by
distributed music players ("play track X starting at wall-clock time T").

### How it works

1. **New schema field**: `consumer_node_assignments` gains
   `joined_at TIMESTAMPTZ`, distinct from `last_seen_at`. `joined_at` is
   set on first INSERT for a `(tenant_key, consumer_id, node_id)` row and
   never updated. `last_seen_at` continues to update on every touch.

2. **New config**: `epistola.collect.rebalance-grace-ms` (default 30000).
   The window during which a freshly-joined node is considered "joining"
   rather than part of the stable ring.

3. **`TouchConsumerNode` handler change**:
   On every touch at time T, after upserting the caller's row:
   - `activeSet` = nodes with `last_seen_at > T - idle_timeout` (today's set).
   - `stableSet` = `activeSet` filtered by `joined_at ≤ T - rebalance_grace`.
     **This node is always in `stableSet`** — avoids bootstrap deadlock for
     the first node ever.
   - `currentAssignment` = `ring(thisNode, stableSet)`.
   - If `activeSet ≠ stableSet` (a join is pending):
     - `pendingAssignment` = `ring(thisNode, activeSet)`.
     - `cutoverAt` = max(`joined_at + rebalance_grace`) over `activeSet \ stableSet`.
     - Return all three.
   - Else: return only `currentAssignment`.

4. **Contract wire change** (`PartitionAssignment` schema, contract 0.4):

   ```yaml
   PartitionAssignment:
     properties:
       total: integer
       hash: enum [murmur3]
       mine: array of integer # current assignment, in effect now
       pending: # NEW, optional — present during a join
         type: object
         properties:
           mine: array of integer # the partition list after cutover
           cutoverAt: # wall-clock instant
             type: string
             format: date-time
   ```

5. **Client behavior change** (contract `ResultCollector`):
   - `routingKeyToMe(key)` uses `assignment.pending?.mine ?? assignment.mine`.
     Always picks the **future** assignment when one exists — so submits
     during the grace window land on partitions the node will own after
     cutover.
   - `collectOnce()` uses `assignment.mine` until `now() ≥ cutoverAt`,
     then switches to `assignment.pending.mine` (or relies on the next
     poll to refresh).
   - `partitionFor(key)` is unchanged — partition number is purely
     `murmur3(key) % total`, ring-independent.

### Why this preserves affinity

A submit during the grace window:

- `routingKeyToMe` returns a key whose partition is in this node's
  **pending** `mine`.
- The result is emitted to that partition (`EmitGenerationResultHandler`
  is ring-agnostic, just hashes the routing key).
- Until `cutoverAt`, this node still owns the partition under the
  **current** ring (since `mine` is the superset during a join). Collect
  works.
- After `cutoverAt`, the node still owns the partition (now under the
  formerly-pending ring). Collect continues to work.

There is no window during which the partition is owned by anyone other
than the submitting node.

### Bootstrap and edge cases

- **First node ever**: `stableSet` would be empty. Forcing `thisNode` into
  `stableSet` gives the first node the full ring immediately. No 30-second
  startup delay for the very first member of a consumer group.
- **Solo node steady state**: `activeSet == stableSet == {thisNode}`. No
  `pending` returned.
- **Node leave**: handled by existing `idle_timeout` (60s default). When a
  node disappears, both `activeSet` and `stableSet` shrink on the next
  touch by anyone. **No additional grace on departures** — extending it
  would delay redelivery of orphaned rows for no correctness gain.
- **Rolling deploy**: new node's fresh `joined_at` triggers the grace;
  old node's `idle_timeout` expires it. During overlap both contribute to
  the active set.
- **Old client + new server**: old client ignores `pending`; behavior
  matches v0.3 (brief misroute possible). Forward-compatible.
- **New client + old server**: server doesn't return `pending`; client's
  `routingKeyToMe` falls through to `mine`. Backward-compatible.
- **Clock skew**: NTP-level (sub-second) is fine; the 30 s grace absorbs
  any reasonable skew. A node with badly-skewed clock (minutes off) might
  switch early or late by that amount — document the requirement, don't
  overfit the implementation.

## Critical files (when implementing)

In `epistola-contract`:

- `spec/components/schemas/generation-collect.yaml` — extend
  `PartitionAssignment` with `pending`.
- `client-kotlin-spring-restclient/.../ResultCollector.kt` — extend the
  `PartitionAssignment` data class; update `routingKeyToMe()` and
  `updatePartitionAssignment()`.
- Bump `info.version` from `0.3.x` to `0.4.0`.

In `epistola-suite`:

- `modules/epistola-core/src/main/resources/db/migration/V27__consumer_node_assignments_joined_at.sql`
  — add the `joined_at` column with `DEFAULT NOW()` so existing rows
  backfill.
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/generation/collect/commands/TouchConsumerNode.kt`
  — handler change as described above.
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/generation/collect/domain/PartitionAssignment.kt`
  — add `pending: Pending?` field.
- `apps/epistola/src/main/resources/application.yaml` — add
  `epistola.collect.rebalance-grace-ms: 30000` under `epistola.collect`.
- `modules/epistola-core/src/test/kotlin/app/epistola/suite/generation/collect/scenarios/ScheduledRebalanceIT.kt`
  — new scenario covering the affinity-during-rebalance property.

In `valtimo-epistola-plugin`:

- Bump `epistola-client` to 0.4.0 in `gradle/libs.versions.toml`.
- No application code changes needed if the contract client absorbs the
  switch internally (recommended).

## Verification

`ScheduledRebalanceIT` sketch:

1. Alice alone → owns all 64 partitions.
2. Bob touches → server returns to Alice
   `{current = ALL, pending = ~32, cutoverAt = T + 30s}`.
3. During grace, Alice submits with `routingKeyToMe`. Result lands on a
   partition in Alice's **pending** set.
4. Alice polls (still uses current = ALL), receives the row.
5. Time-advance past `cutoverAt`.
6. Alice polls again, receives any additional rows in her pending set.
7. Bob never receives this row.

Cross-impl test in `epistola-contract`: client + server both running,
drive the rebalance via real HTTP, verify `cutoverAt` is honored
end-to-end.

Performance: confirm the additional `WHERE joined_at <= ?` filter on
`consumer_node_assignments` doesn't regress touch latency under load
(the v0.3 load-test framework — see `docs/collect-performance.md` once
that lands — covers this).

## When to implement

After v0.3 is shipped and we have at least one production rebalance
event under our belt to confirm the misroute window is what we expect
(≤ 1 poll interval). Open the work on a branch named
`feat/coordinated-rebalance` or similar.
