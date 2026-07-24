<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# v0.5 — Push-based collect with self-coordinating server cluster

## Status

**Design / not implemented.** Captured here so the question is
discoverable when scale forces the conversation. Hard constraint
(per the original design discussion): **no external infrastructure
dependency**. No PG `LISTEN`/`NOTIFY`, no Redis, no NATS, no Kafka.
The cross-instance routing required for push happens entirely
within the application — a self-coordinating server cluster.

This is substantially more ambitious than the v0.3 polling model.
Probably 3–4 weeks of focused work plus rollout. Not a v0.4
deliverable; v0.4 is the coordinated-rebalance work in
[`v04-coordinated-rebalance.md`](v04-coordinated-rebalance.md).

## Why this exists

The v0.3 polling protocol works fine up to about the scale we have
performance numbers for (see
[`collect-performance.md`](collect-performance.md) — ~63k msg/sec
sustained at 16 consumers on one Postgres). Two reasons to revisit
that model later:

1. **Polling cost grows linearly with consumer count.** At ~16k
   consumers each polling every ~1 s in active periods, that's
   ~16k polls/sec aggregate. At 100k+ consumers the polling
   metadata overhead starts to dominate Postgres CPU even though
   the actual result rate is small.
2. **Latency is bounded below by the poll interval.** Default
   `minInterval=1s` means up to ~1 s between emit and consumer
   delivery. SSE pushes within milliseconds. Worth it if a product
   reason emerges (e.g. interactive document generation flows in
   BPMN).

The trade: the suite becomes stateful in a new way (each instance
holds open SSE connections), and the protocol grows from
stateless-polling-against-shared-Postgres to a self-coordinating
distributed system.

## Wire protocol — pick SSE

Three reasonable choices. Comparison:

|                         | SSE                             | WebSocket                   | gRPC streaming                   |
| ----------------------- | ------------------------------- | --------------------------- | -------------------------------- |
| Already in stack        | Spring Boot supports natively   | Spring has it, extra config | New dependency in suite + plugin |
| Direction needed        | Server → client (matches us)    | Bidirectional (overkill)    | Bidirectional                    |
| Proxy-friendly          | Yes, plain HTTP                 | Mostly — needs `Upgrade`    | Needs HTTP/2                     |
| Reconnect semantics     | Built-in `Last-Event-ID` resume | Custom                      | Custom                           |
| Fits NDJSON event model | Trivially (one event per line)  | Frame-based                 | Stream message                   |

**SSE wins.** New endpoint:

```
GET /api/tenants/{tid}/generation/collect/stream
Accept: text/event-stream
```

Each event payload is one `GenerationResult` row in the same JSON
shape the polling endpoint already returns. Keep-alive comments
flow on a timer to detect broken connections and to update the
`last_seen_at` heartbeat without doing a real poll. The existing
polling endpoint stays as the fallback.

## Two stacked rings

The existing per-(tenant, consumer) ring stays unchanged — 64
partitions distributed over the consumer's N nodes via the
`ConsistentHashRing` we already have.

What's new is a **second ring at the suite-cluster layer**: M
suite instances, each owning a deterministic subset of all
consumer-node _connections_. The ring's identity is the consumer
node id; the assignment is to a suite instance.

```
result_partition  = Partition.partitionFor(routingKey)         // existing
consumer_node     = consumerRing.ownerFor(result_partition, …)  // existing
suite_instance    = serverRing.ownerFor(consumer_node, …)       // NEW
```

Both rings use the same `ConsistentHashRing` machinery with K=128
virtual nodes per real node — so a 10 → 11 suite scale-up
reshuffles only ~10% of consumer connections. At 16k consumers
that's ~1.6k reconnects on a scale event. Bursty but bounded.

## Server-side bookkeeping — `cluster_nodes` heartbeat table

Exact same shape as `consumer_node_assignments` from v0.3:

```sql
CREATE TABLE cluster_nodes (
    instance_id  TEXT PRIMARY KEY,    -- self-chosen (pod name, hostname)
    base_url     TEXT NOT NULL,       -- where peers can reach this instance
    last_seen_at TIMESTAMPTZ NOT NULL,
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Each suite instance heartbeats every N seconds (e.g. 10 s).
Cluster membership = rows where `last_seen_at > now - idle_timeout`.

**No gossip protocol. No Raft. No etcd or service mesh required.
Just rows.** This mirrors the pattern already proven by
`consumer_node_assignments` and is consistent with the v0.4
coordinated-rebalance design.

## Client-side bookkeeping — what the contract client learns to do

The contract `ResultCollector` grows two new responsibilities:

1. **Discover the cluster.** Hit any suite instance for
   `GET /api/cluster/nodes` → returns the active list of
   `(instance_id, base_url, joined_at)`.
2. **Pick a connection target deterministically.** Compute
   `serverRing.ownerFor(myConsumerNodeId, instances)` and connect
   there. The load balancer no longer picks at random — the client
   picks deterministically.
3. **Refresh periodically** (e.g. every 30 s). If the owner has
   changed (cluster scaled), reconnect to the new owner.
4. **Follow `307 Temporary Redirect` on connect** — if the client's
   topology view is stale, the receiving suite responds with a
   redirect to the correct instance. (See "Topology drift" below.)

This is the part that's actually new on the client side. The
existing v0.3 client doesn't know cluster topology at all.

## The four hard problems

### 1. Topology drift between observers

Two parties may see slightly different cluster lists at any moment
because the registry is eventually consistent. Their hashes will
disagree on ownership.

**Design choice: the receiving suite instance is authoritative.**
When a connection arrives at suite-S, S asks "based on my current
view of `cluster_nodes`, am I the right owner for this consumer
node?" If yes, accept the SSE upgrade. If no, respond with
`307 Temporary Redirect` to the correct `base_url`. The client
follows the redirect.

Same trick HTTP uses everywhere: best-effort routing on the
client, server corrects with a redirect. Eventually consistent,
no consensus needed.

### 2. Topology change during a connection's lifetime

Consumer Y is connected to suite-3. The cluster scales up; the
ring now says suite-7 owns Y.

**Design choice: kick and let reconnect.** suite-3's next
periodic topology refresh notices it's no longer the owner for Y,
closes Y's SSE connection. Y reconnects (handles the close like
any other transient drop), lands on suite-7. ~100 ms blip,
harmless because of the cursor + fallback poll.

The fancier alternatives (graceful drain, mid-flight forwarding)
add bookkeeping for no real benefit at this scale.

### 3. Routing emits across instances

Suite-B finishes a doc generation, commits the result row to
`generation_results`, now needs to deliver. Two layers of routing
chained:

```
result_partition  = Partition.partitionFor(routingKey)
consumer_node     = consumerRing.ownerFor(result_partition, …)
suite_instance    = serverRing.ownerFor(consumer_node, …)
```

If `suite_instance == self`, push down the local SSE connection
(if open). Otherwise `POST /internal/push` to the peer's
`base_url` with the row payload.

The peer's `/internal/push` either:

- pushes to its open SSE connection for that consumer node, or
- returns 404 if no open connection exists (consumer offline /
  between reconnects). The row is still durable in
  `generation_results`; the existing cursor mechanism backfills
  on the consumer's next connect.

Order of magnitude: 16k consumers × 1 result/min/consumer =
~270 cross-instance forwards/sec aggregate. Comfortably within
internal HTTP budget on any sane suite-cluster sizing.

### 4. Split brain — two suites briefly think they own the same consumer

During a topology transition, suite-3 and suite-7 might both have
an open connection for consumer Y for a few hundred ms. A row
gets pushed twice.

**Harmless.** The plugin's `ResultCollector` already deduplicates
by `sequence` via its `lastAcknowledgedSequence` cursor. At-least-once
is the correctness guarantee, not exactly-once. A momentary
duplicate during cluster transitions costs nothing.

## Two simplifying invariants

These are what keep the design from spiraling into a CAP-theorem
nightmare. Worth being explicit about.

1. **Push is best-effort. The cursor is authoritative.** You're not
   building a reliable message bus on top of HTTP — you're building
   a fast happy path on top of a reliable cursor. Routing errors
   during transitions are fine because the row is durable in
   `generation_results` and the consumer will see it via the
   fallback poll regardless. **This is what makes the multi-instance
   problem tractable.**

2. **The data is small.** 16k consumers × tiny routing metadata
   fits in memory on every suite instance (the registry is tens of
   rows; the in-memory `consumer_node → SseEmitter` map is bounded
   by connections held by _this_ instance). The server ring
   computation runs in microseconds. No sharding needed at any
   layer.

## Comparison — server-ring vs the rejected PG NOTIFY option

The lower-effort alternative would have been Postgres `LISTEN` /
`NOTIFY` for cross-instance signaling. Explicitly ruled out by
the "no external dep" constraint, but worth being honest about
what we give up:

|                        | PG NOTIFY         | Server-ring (this design)         |
| ---------------------- | ----------------- | --------------------------------- |
| Suite is stateless     | ✅                | ❌ (holds connections)            |
| Client knows cluster   | ❌                | ✅ (must learn topology)          |
| LB picks server        | ✅ randomly       | ❌ client picks deterministically |
| Cross-instance signal  | via PG            | direct internal HTTP              |
| Scale ceiling          | ~10k notifies/sec | bounded by internal HTTP, ~100k+  |
| Survives PG sharding   | ❌                | ✅                                |
| Operational complexity | low               | substantial                       |
| Time to build          | ~1 week           | ~3–4 weeks                        |

The server-ring is strictly more capable. It also avoids tying
the suite's scaling story to PG's `NOTIFY` ceiling, which matters
if the suite ever grows beyond a single PG cluster.

## Implementation surface (when this work happens later)

### Suite

- New table `cluster_nodes` + heartbeat `@Scheduled` (mirror of
  the existing `consumer_node_assignments` pattern in
  `TouchConsumerNode`).
- New `ServerRing` class — a thin wrapper around the existing
  `ConsistentHashRing` with the consumer-node-id → suite-instance
  mapping.
- New query `GetActiveClusterNodes` returning the cluster view from
  `cluster_nodes`.
- New public endpoint `GET /api/cluster/nodes` returning that view
  (anonymous read OK — no secrets in the response).
- New internal endpoint `POST /internal/push` for peer-to-peer
  forwarding. Requires a shared cluster secret or mTLS so it
  isn't reachable externally.
- New SSE endpoint
  `GET /api/tenants/{tid}/generation/collect/stream` returning
  `text/event-stream`.
- New `SseConnectionRegistry` per instance — in-memory
  `consumer_node_id → SseEmitter` map. No shared state for the
  mapping itself; everyone agrees on the topology, the mapping is
  derivable.
- `EmitGenerationResultHandler` extension: after committing the
  row, compute the suite-instance owner and either push locally
  or forward.
- `307` redirect logic at the SSE accept point if the receiving
  instance isn't the owner per its topology view.
- Heartbeat for `consumer_node_assignments.last_seen_at` shifts
  from per-poll to a periodic timer driven by the SSE keepalive
  (e.g. every 30 s) — ~60x reduction in heartbeat write rate vs
  the v0.3 polling baseline.

### Contract

- Add the SSE endpoint to the OpenAPI spec.
- Add `GET /cluster/nodes` to the spec.
- Probably bump to v0.5.

### Plugin

- Bumps to the new contract version.
- The contract client encapsulates the SSE-vs-poll choice
  (negotiate by `Accept` header capability + server response).
  Plugin application code does not change.

## Open questions to resolve at implementation time

Not now. Captured so they're not forgotten:

1. **How clients discover the bootstrap base_url.** DNS SRV record
   is clean but adds an ops dependency. Plain config is fragile
   during scale events. Could also bootstrap from a small static
   list and let the cluster expand on first connect.
2. **Authentication of `/internal/push` between peers.** Shared
   secret? mTLS? Must not be reachable externally — easy with
   K8s NetworkPolicies, harder in non-K8s deployments.
3. **Connection limits per suite instance during burst-reconnect
   storms.** A topology change kicks ~10% of clients; they all
   reconnect within a second; one suite instance briefly handles
   2× its normal connection rate. Need to ensure thread-pool /
   accept-queue limits don't trigger.
4. **Registry-table query load at large suite-cluster sizes.**
   Every suite instance polls `cluster_nodes` every N seconds; every
   client polls `/api/cluster/nodes` every 30 s. At 100 suite
   instances + 16k clients that's nontrivial query load even
   though the table is tiny. Cacheable with a short TTL, but
   worth noting.
5. **Behavior under suite-instance crash mid-emit.** If suite-B
   commits the row and crashes before forwarding to the SSE-owning
   peer, the consumer sees the row only via fallback poll (~30 s
   delay). Acceptable per the at-least-once contract, but worth
   measuring at implementation time so we have an honest worst-case
   SLA.

## When to revisit

When at least one of the following is true:

- Production polling rate exceeds ~50% of the measured Postgres
  ceiling on the production hardware (forces a rethink even if
  consumer count isn't yet ~100k).
- A product requirement emerges for sub-second result delivery
  (e.g. interactive BPMN flows where the user is waiting for the
  document).
- We're considering moving Postgres to a sharded / multi-cluster
  topology that breaks `NOTIFY` semantics anyway. (The point at
  which "the lower-effort PG NOTIFY alternative" stops being an
  alternative.)
