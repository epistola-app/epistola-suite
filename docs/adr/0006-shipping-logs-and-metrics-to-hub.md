# ADR 0006: Shipping logs and metrics to epistola-hub

- **Status:** Accepted — Option A (unified OTLP)
- **Date:** 2026-06-13
- **Deciders:** Epistola team
- **Tags:** logs, metrics, observability, hub, entitlement, otlp, architecture

## Context

Snapshots already sync from the suite to **epistola-hub** (per-tenant catalog backups:
`TenantSnapshotSyncService` → `HubSnapshotSyncAdapter` → the `CatalogSync` gRPC service,
gated by a per-tenant feature toggle **and** a hub entitlement). We now want the two other
telemetry sources to reach the hub **when enabled** — for fleet aggregation, support, and
managed-services monitoring:

- **Application logs** — the `application_log` table (`modules/epistola-core/.../logs/`,
  migration `core/V20260612053608__core_application_log.sql`). Capture is **non-blocking,
  batched, and fail-open**: the `ApplicationLogAppender` offers records to a bounded queue
  and the `ApplicationLogIngestor` drains batches into the table; on overflow it **drops**
  and counts (`epistola.logs.dropped`), and a failing batch is dropped, never retried in a
  tight loop. Rows are tenant-scoped via a **nullable** `tenant_key` (system/background
  rows are `NULL`). The schema is deliberately forward-friendly (UUIDv7 `id`, `instance_id`,
  `occurred_at`) and `docs/application-logs.md` already sketches a future **`LogSyncPort` +
  `HubLogSyncAdapter`** that drains the table (the feedback/snapshot `SyncPort` pattern).
- **Metrics** — Micrometer (`config/MetricsConfig.kt`, `observability/`). **Install-wide
  operational telemetry, not per-tenant** (only the two `epistola.generation.document.*`
  business meters carry a bounded `tenant` tag). `docs/metrics.md` already states the
  per-tenant-toggle rejection and, under **"Deferred: hub leg" (issue #506)**, already
  frames metrics→hub as _"a dedicated, isolated OTLP push leg the suite owns end-to-end,"_
  configured at runtime by `epistola-support`, kept **separate** from any bring-your-own
  OpenTelemetry agent leg so customers' own observability does not double-export.

**Decisive prior fact: the suite is already on OpenTelemetry.**
`apps/epistola/build.gradle.kts` pulls `spring-boot-starter-opentelemetry` and
`micrometer-registry-otlp`, and `application.yaml` (≈ lines 269–279) already carries all
three OTLP export knobs, merely disabled:

```yaml
management:
  otlp:
    metrics: { export: { enabled: false } }
    tracing: { export: { enabled: false } }
  logging:
    export:
      otlp: { enabled: false } # OTLP log export is already a built-in framework knob
```

So OTLP is the transport the suite has already adopted for observability; shipping over it
is mostly **enable + endpoint + credentials + gate**, not a new pipeline.

Two questions were raised:

1. **Scope** — should hub shipment be a **per-tenant** feature (like feedback/backups) or a
   **global** one? — _decided: global._
2. **Transport** — **how** should logs and metrics physically reach the hub? — _decided:
   unified OTLP (the options + tradeoffs that led there are recorded below)._

Note the two deferred sketches **disagree on shape**: metrics→hub is already OTLP (#506),
but logs→hub is sketched as a table-draining gRPC `LogSyncPort`. Reconciling that is the
substance of Decision 2. This ADR supersedes both deferred notes.

### Decision drivers

- **Use the standard / least code.** OTLP (with batching + compression + a collector
  ecosystem) is already in the build; metrics→hub is already OTLP by design.
- **Performance — batching and high compression.** Logs are high-frequency (the ingestor
  caps capture at ~2000 events/s and a 10k queue). A hub feed must batch and compress;
  per-event shipping is a non-starter.
- **Metric fidelity.** Metrics are continuous time series; sampling them point-in-time
  loses most of their value.
- **Log durability vs fail-open.** Logs are **already lossy locally** (drop-on-overflow).
  Does the hub feed need outbox-grade, replayable, gap-free delivery, or is the same
  fail-open posture acceptable?
- **Entitlement enforcement point.** Where is "is this installation entitled?" enforced —
  the existing per-call `CatalogSync` gRPC check, or a hub-side OTLP collector that
  authenticates the installation?
- **Egress / firewall.** Installations are customer-deployed behind restricted egress
  (this is _why_ snapshots are pushed, not scraped); everything here must be outbound push.
- **PII / data residency.** Logs can carry PII (messages, MDC, stack traces); the gate must
  allow withholding.
- **Pattern coherence.** Two coherent worlds pull on logs: _observability_ (one OTLP
  pipeline for metrics + logs + traces) vs _hub-sync_ (one `SyncPort`/gRPC pattern for
  feedback + snapshots + logs).

## Decision 1 — Gating scope: **global** (decided)

Hub shipment of logs and metrics is gated **globally (per installation)**, not per tenant.

**Why per-tenant is the wrong shape here** (not merely heavier):

- **Metrics have no usable per-tenant dimension** — they are install-wide aggregates and
  timers. `docs/metrics.md` already rejects a per-tenant toggle: it would only create
  monitoring blind spots, and the residency concern it was really about "is more naturally
  per-installation."
- **Logs include system rows** (`tenant_key IS NULL`, e.g. partition maintenance, log
  retention) that belong to no tenant — a per-tenant switch has no answer for them.

Logs and metrics are **operational telemetry about the installation's health**, unlike
feedback/snapshots which are per-tenant business content. So gate globally, reusing the
two mechanisms that are **already inherently installation-wide** rather than inventing
global-toggle infrastructure (all `feature_toggles` rows are keyed by tenant today):

1. **Local on/off = a config property** under `epistola.support.*`, mirroring the existing
   `epistola.support.backups.scheduled.enabled` scheduler gate. Install-wide by nature.
2. **Hub gate = an installation-wide entitlement** (`tenant = null` ALLOW) — already fully
   supported on both sides (`EntitlementService.effectiveEffect`, suite
   `SupportEntitlementService`). Add a new hub `Feature` (e.g. `TELEMETRY_SYNC`) with its
   own wire key in `EntitlementFeatures`.

**Free per-tenant opt-out.** Because a tenant-scoped `DENY` overrides an installation-wide
`ALLOW` (`effectiveEffect`), a per-tenant _exclusion_ (for a log-PII / residency edge case)
is expressible without any per-tenant toggle infrastructure. Alternatively, the `tenant`
tag can be stripped before forwarding (as `docs/metrics.md` notes). Global-on by default,
exclude-one-tenant if ever required.

## Decision 2 — Transport: unified OTLP (decided)

Because the suite is already on OTLP and metrics→hub is already OTLP by design, the live
question is narrow: **does anything justify _not_ using the standard for logs too?** The
three options below are framed around that.

### Option A — Unified OTLP for all signals (recommended)

Ship logs **and** metrics (and optionally traces) over a **dedicated, isolated OTLP push
leg the suite owns** — distinct from any bring-your-own OpenTelemetry agent leg so customer
observability never double-exports (the isolation `docs/metrics.md` #506 already calls
for) — pointed at a hub-operated OTLP collector, with gzip + batch tuning, all behind the
global gate. Logs ride the standard OpenTelemetry Logback appender →
`BatchLogRecordProcessor`; metrics keep full fidelity via the Micrometer OTLP registry.

This is **not** simply flipping `management.otlp.metrics.export.enabled` /
`management.logging.export.otlp.enabled`: those knobs are the customer's own/self-export
leg. The hub leg is a _second, dedicated_ exporter the support module configures at runtime
with the hub endpoint + `ek_*` credentials, so it can be gated and credentialed
independently.

**Control without leaving the standard.** "Use OTLP" does **not** mean a fire-and-forget
black box — OpenTelemetry is explicitly extensible at well-defined seams, so the suite
keeps full governance of what is shipped. Control lives at three layers, all standard:

- **Global gate (whether):** the dedicated hub leg is wired only when the
  `epistola.support.*` property is on **and** the installation is entitled (proactive
  `EntitlementStore` read) — it decides whether the leg runs at all.
- **Producer-side (what / how much leaves the process):** a custom **`LogRecordProcessor`**
  for logs and the existing **`MeterFilter`** (`MetricsConfig.commonMetricTags`) for metrics
  are the standard hooks to **filter, redact, drop, rate-limit, or strip the `tenant`
  tag** before anything is exported. The per-tenant `DENY` opt-out and any PII redaction are
  enforced here; standard exporter knobs (batch size, queue, gzip, timeout) give
  backpressure control. This leg is **isolated** from any bring-your-own agent leg, so the
  suite owns its pipeline end-to-end.
- **Collector-side (what is accepted / how it is routed):** the hub-operated OTel Collector
  is the OTLP control plane — `filter` / `transform` (OTTL) / `redaction` / `tail_sampling`
  / routing / rate-limiting processors, plus an auth extension that authenticates the
  installation and enforces entitlement (the analog of the gRPC `PERMISSION_DENIED`).

So the standard buys the wire format, batching, compression, and collector ecosystem
_while_ leaving the suite a producer-side processor/`MeterFilter` and the hub a collector
pipeline as first-class control points — the governance a bespoke `SyncPort` would give,
without the bespoke transport.

**Pros**

- **Standard + least bespoke code.** Logs and metrics share one push leg; no
  `LogSyncPort`/`HubLogSyncAdapter`/scheduler/cursor, no hub gRPC log-ingest service.
- **Batching + compression for free.** `BatchLogRecordProcessor` (max batch / queue /
  schedule delay) + gzip on the exporter — directly satisfies the performance driver.
- **One observability plane at the hub.** Logs, metrics, and traces land via the same OTLP
  collector and can be correlated by trace/span id (the suite already promotes
  `trace_id`/`span_id`).
- **Consistent with the already-decided metrics leg** (#506) — extends it to logs rather
  than running a second, different mechanism.

**Cons**

- **Logs ship fire-and-forget** (drop-on-overflow), no replay — though this matches the
  suite's _existing_ local posture (see crux).
- **New entitlement enforcement point.** OTLP can't reuse the per-call `CatalogSync`
  `PERMISSION_DENIED`. Enforcement becomes: the suite enables the leg only when the config
  property is on **and** the installation is entitled (proactive `EntitlementStore` read),
  **and** the hub OTLP collector authenticates the installation (API key / header) and
  authorizes by entitlement. This collector auth/entitlement gate is the main genuinely-new
  piece — but it must be built for the metrics leg regardless, so logs add little marginal
  cost.
- **Supersedes the sketched `LogSyncPort`** in `docs/application-logs.md` (logs no longer
  follow the feedback/snapshot pattern).

### Option B — Hybrid: OTLP for metrics, durable table-drain gRPC for logs

Keep metrics on the OTLP leg (Option A), but ship **logs** via the
already-sketched `LogSyncPort` + `HubLogSyncAdapter`: drain `application_log` over a
`CatalogSync`-style stream, deduped/replayable via an `app_metadata` last-sync cursor,
reusing the existing gRPC auth and per-call entitlement check.

**Pros**

- **Durable, replayable, gap-free logs.** The table is an outbox; rows survive hub outages
  up to retention and ship exactly-once via the cursor.
- **Reuses the hub-sync pattern + entitlement check** (feedback, snapshots) with no new
  collector auth path for logs.
- **Hub receives the suite's exact structured rows** (tenant_key, instance_id, trace/span,
  attributes JSONB) without depending on OTLP log-record field mapping.

**Cons**

- **Re-implements** batching, compression, retry, and backpressure that the OTLP appender
  provides for free.
- **Two mechanisms to build, run, and debug** (OTLP leg _and_ a log-sync engine + hub
  gRPC log-ingest service) — the standing-component cost ADR 0005 calls out, applied to
  logs.
- **Stronger guarantee than we keep locally** (see crux) — likely over-engineering for
  diagnostic logs.

### Option C — Unified bespoke gRPC for both

One `CatalogSync`-style channel carries table-drained logs **and** periodically _sampled_
metric snapshots.

**Pros**

- Single authenticated egress channel; one entitlement check; the OTLP collector is not
  needed.

**Cons**

- **Sampling destroys metric fidelity** — continuous time series become coarse point
  samples.
- **Ignores the OTLP stack already in the build** and contradicts the decided metrics leg.
- Recorded mainly to be explicitly rejected.

### The crux question

> **Do hub-shipped logs need durable / replayable (outbox) delivery, or is fail-open
> drop-on-overflow acceptable?**

The local ingestor **already drops on overflow** (fail-open) by design. Requiring
exactly-once durable shipment to the hub would hold the hub feed to a _stronger_ guarantee
than the suite keeps for its own table — hard to justify for diagnostic telemetry.

**Resolved:** the team confirmed **replay / durable delivery is not required** for this hub
feed — fail-open (matching the local posture) is acceptable. This eliminates the only driver
for Option B and **selects Option A**.

### Tradeoff summary

| Axis                       | A — Unified OTLP        | B — OTLP metrics + gRPC logs               | C — Unified gRPC    |
| -------------------------- | ----------------------- | ------------------------------------------ | ------------------- |
| Standard vs bespoke / code | Config + collector      | OTLP **and** a log-sync engine             | New gRPC + sampling |
| Batching + compression     | Native (free)           | Native (metrics); hand-rolled (logs)       | Hand-rolled         |
| Metric fidelity            | Full                    | Full                                       | **Lost (sampled)**  |
| Log durability / replay    | Fail-open (= local)     | **Outbox (durable)**                       | Outbox (durable)    |
| Entitlement enforcement    | Hub OTLP collector auth | Collector (metrics) + per-call gRPC (logs) | Per-call gRPC       |
| Egress targets             | Hub OTLP collector      | Collector + hub gRPC                       | Hub gRPC only       |
| Pattern coherence          | Observability pipeline  | Mixed                                      | Hub-sync pattern    |

## Decision

**Accepted: Option A — unified OTLP for all signals.**

The suite is already on OTLP; metrics→hub is already an OTLP leg by design (#506); OTLP
gives batching, high compression, and a standard collector for every signal; and the suite
**already accepts fail-open logs locally**. With the team having confirmed that **replay /
durable delivery is not required** for this feed (see the resolved crux), the sole reason to
build a bespoke durable log push is gone. Treating logs as one more signal on the same
dedicated hub OTLP leg yields the least code, one observability plane, and trace-correlated
logs+metrics — at the cost of solving installation auth + entitlement at the collector,
which the metrics leg requires anyway. Process control is retained without leaving the
standard via the producer-side `LogRecordProcessor` / `MeterFilter` and the collector
pipeline (see "Control without leaving the standard").

**Option B** would be reopened only if a future hard requirement emerges for the hub to hold
a **durable, replayable, gap-free** log record (e.g. a compliance-grade central log archive);
A→B remains reachable then (see forward-compatibility). **Option C** is rejected: metric
sampling is a poor trade and it abandons the OTLP investment.

## Consequences

### Always (the global gate — both decisions)

- A global `epistola.support.*` config property (suggested
  `epistola.support.telemetry.enabled`) coordinates the hub leg; it is install-wide, with
  no per-tenant DB override.
- A new hub `Feature` (e.g. `TELEMETRY_SYNC`) and wire key in `EntitlementFeatures`; the
  suite ships only when the property is on **and** the installation holds an effective
  install-wide `ALLOW`. A tenant-scoped `DENY` (or stripping the `tenant` tag) provides the
  residency opt-out.

### Implementation (Option A — accepted)

- **Transport: OTLP over gRPC.** Both signals speak OTLP on the **hub's existing gRPC
  endpoint** — the same endpoint/port the suite already resolves for the hub — so there is
  **no separate HTTP server, port, or proxy**, and authentication reuses the hub's existing
  `x-ep-api-key` gRPC interceptor instead of a bespoke collector-auth path. (HTTP/protobuf
  was the alternative; it was dropped because it forced a second server + port and Micrometer
  metrics could not share the gRPC transport without a custom sender — which, once written,
  let gRPC carry both signals cleanly.)
- A dedicated, isolated leg in `epistola-support-telemetry` (`TelemetryLeg`), gated on
  `epistola.support.enabled` + `epistola.support.telemetry.enabled` + an installation-wide
  `support-telemetry` entitlement, isolated from any bring-your-own agent/self-export leg.
  The endpoint is resolved by the support module from the hub endpoint
  (`HubTelemetryEndpointResolver`, override `epistola.support.hub.telemetry-endpoint`).
- Logs: a `TelemetryLogAppender` → `OtlpGrpcLogRecordExporter`. Metrics: a dedicated
  Micrometer `OtlpMeterRegistry` with a custom `GrpcOtlpMetricsSender` (Micrometer ships only
  an HTTP sender), `tenant` tag stripped before forwarding.
- Hub side: `OtlpSink` registers the standard OTLP collector gRPC services and **discards**
  the payload for now. It authenticates the installation via the existing interceptor
  (UNAUTHENTICATED otherwise) **and** requires an installation-wide `support-telemetry`
  entitlement (`EntitlementService.isInstallationEntitled`, PERMISSION_DENIED otherwise).
  Real ingestion is a follow-up.
- Close out `docs/metrics.md` #506; **supersede** the `LogSyncPort` sketch in
  `docs/application-logs.md` (logs now ship via OTLP-over-gRPC, not a table drain). Update
  both docs.

### If Option B is ever reopened (durability requirement emerges)

- Build `LogSyncPort` + `HubLogSyncAdapter` + a `SINGLE_OWNER` log-sync scheduler in
  `epistola-support`, an `app_metadata` last-sync cursor, and a hub gRPC log-ingest service
  reusing the existing entitlement check; metrics still ship via the OTLP leg (#506).
- A standing sync component to operate and test (the ADR-0005 local-store-+-sync cost,
  applied to logs).

### Forward-compatibility

A→B is reachable later (add the durable log path) if an outbox/compliance need emerges, so
starting from A keeps the suite on the standard with the least code while preserving the
option. The hub's observability-ingestion contract (an OTLP collector) is independent of
this choice for metrics either way. This is the same **standard/simple vs
durable/replayable** tension as ADR 0005 — here resolved toward the standard because logs
are _already_ lossy locally.

### Related

- [`docs/application-logs.md`](../application-logs.md) — capture, retention, and the
  deferred `LogSyncPort` "Hub forwarding" sketch this ADR supersedes.
- [`docs/metrics.md`](../metrics.md) — identity tags, per-tenant-toggle rejection, and the
  "Deferred: hub leg" (issue #506) OTLP design this ADR builds on.
- [`docs/feature-toggles.md`](../feature-toggles.md) — toggle vs entitlement composition.
- ADR 0005 (feedback storage — local-copy+sync vs remote) — the parallel
  standard-vs-durable decision.
- `modules/epistola-support-snapshots/` — the `SnapshotSyncPort` / `HubSnapshotSyncAdapter`
  gRPC-push pattern Option B would mirror for logs.
- epistola-hub `entitlement/EntitlementService.kt` — `Feature` / wire-key shape and
  install-wide vs tenant-scoped resolution.
