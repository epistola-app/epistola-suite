# Metrics & Observability

How Epistola exposes operational metrics for fleet monitoring, and how to wire
your own OpenTelemetry pipeline.

> **TL;DR** — Every meter is stamped with five identity tags
> (`service`, `instance`, `installation_id`, `environment`, `version`) so metrics
> from many remote instances stay distinguishable in one backend. Metrics are
> **operational telemetry** — they are collected install-wide, not toggled per
> tenant. The only per-tenant dimension is a bounded `tenant` tag on the two
> document-generation business meters, for the operator's own attribution.
> Exports: scrape `/actuator/prometheus`, or attach an OpenTelemetry Java agent
> (bring-your-own — the app's own OTLP export stays off so the agent owns that
> leg with no double export). Forwarding to **epistola-hub** is a separate
> commercial-tier leg configured at runtime by the support module — see
> [Hub leg](#hub-leg).

## Identity tags (on every meter)

A single `MeterFilter` (`MetricsConfig.commonMetricTags`) stamps every meter, on
every registry (Prometheus scrape and OTLP push alike), with:

| Tag               | Source                              | Notes                                             |
| ----------------- | ----------------------------------- | ------------------------------------------------- |
| `service`         | constant `epistola-suite`           | Distinguishes the suite from other services.      |
| `instance`        | [`NodeIdentity`](#node-identity)    | Per-process node id — the per-pod partition key.  |
| `installation_id` | `app_metadata` installation UUID    | Stable for the database lifetime; shared by pods. |
| `environment`     | `epistola.installation.environment` | e.g. `production`, `staging`.                     |
| `version`         | build version (`BuildProperties`)   | Running build; `dev` when unbuilt.                |

These are resolved **once at startup** and are invariant by design — a metrics
backend keys a time series by its tag set, so a stable value is correct (a
genuine identity change is picked up on the next restart).

### Node identity

`NodeIdentity` resolves a per-process id from the first non-blank of:
`epistola.node-id` → `EPISTOLA_NODE_ID` → `HOSTNAME` → `POD_NAME` → local
hostname → `"unknown"`. In Kubernetes the pod name supplies it automatically —
no per-pod configuration needed.

## Metric catalogue

Custom application meters (in addition to the standard Spring Boot / JVM /
`http.server.requests` meters):

### Document generation

| Meter                                     | Type                | Tags                                    |
| ----------------------------------------- | ------------------- | --------------------------------------- |
| `epistola.generation.document.duration`   | Timer (histogram)   | `tenant`, `outcome`, `template`, `path` |
| `epistola.generation.document.size.bytes` | DistributionSummary | `tenant`, `template`                    |
| `epistola.generation.queue.depth`         | Gauge               | —                                       |

`epistola.generation.document.*` are the **only** meters carrying a `tenant`
tag — see [Per-tenant tagging](#per-tenant-tagging).

### Jobs / batch processing

| Meter                           | Type              | Tags      |
| ------------------------------- | ----------------- | --------- |
| `epistola.jobs.duration`        | Timer (histogram) | `outcome` |
| `epistola.jobs.claimed.total`   | Counter           | —         |
| `epistola.jobs.completed.total` | Counter           | —         |
| `epistola.jobs.failed.total`    | Counter           | —         |
| `epistola.jobs.active`          | Gauge             | —         |

### Storage

| Meter                                 | Type                | Tags                              |
| ------------------------------------- | ------------------- | --------------------------------- |
| `epistola.storage.operation.duration` | Timer (histogram)   | `operation`, `outcome`, `backend` |
| `epistola.storage.put.bytes`          | DistributionSummary | `backend`                         |

### Mediator (CQRS)

| Meter                                | Type              | Tags                 |
| ------------------------------------ | ----------------- | -------------------- |
| `epistola.mediator.command.duration` | Timer (histogram) | `command`, `outcome` |
| `epistola.mediator.query.duration`   | Timer (histogram) | `query`, `outcome`   |

### Scheduled tasks & event log

| Meter                                | Type    | Tags              |
| ------------------------------------ | ------- | ----------------- |
| `epistola.scheduled.task.duration`   | Timer   | `task`, `outcome` |
| `epistola.eventlog.persist.duration` | Timer   | `outcome`         |
| `epistola.eventlog.persist.failures` | Counter | —                 |

### API security

| Meter                        | Type    | Tags     |
| ---------------------------- | ------- | -------- |
| `epistola.api.auth.attempts` | Counter | `result` |

### Installation-wide gauges

Published by a single leader replica (advisory-lock elected); non-leaders
withhold the value (NaN, omitted from export). The `instance` tag is **stripped**
(`MetricsConfig.stripInstanceFromInstallationGauges`) so the series stays stable
keyed by `installation_id` as leadership moves.

`epistola.installation.{tenants,templates,themes,catalogs,stencils,fonts,environments}`
— one Gauge each, no per-instance/per-tenant tags (they are install-wide
aggregates).

## Per-tenant tagging

Only `epistola.generation.document.duration` and
`epistola.generation.document.size.bytes` carry a `tenant` tag (the tenant key of
the generation request). This lets an operator attribute generation
load / latency / failures per tenant. Cardinality is bounded by
`tenants × templates`.

Deliberately **not** tagged per tenant: the mediator, storage, jobs and
scheduled-task meters. Tagging those would multiply cardinality
(tenants × command-types × outcome × histogram buckets) for little
fleet-monitoring value — cardinality, not request rate, is the dominant TSDB
cost.

> **Why no per-tenant on/off toggle?** Metrics are operational telemetry the
> operator needs to run the fleet; a per-tenant kill switch would only create
> monitoring blind spots. The original "disable metrics per tenant" idea was
> really about _not forwarding a tenant's data to the external hub_ — a
> data-residency concern that travels with the [hub leg](#hub-leg)
> (and is more naturally per-installation, or solved by stripping the `tenant`
> tag before forwarding). Feedback, by contrast, _is_ per-tenant toggleable
> because it forwards user-submitted content — see
> [feature-toggles.md](feature-toggles.md).

## Exporting metrics

### Prometheus scrape

Expose and scrape `/actuator/prometheus`. In the prod profile the actuator
endpoints serve on a separate management port (`4040`); the Helm chart can add
the scrape annotations:

```yaml
observability:
  prometheus:
    enabled: true
    port: 4040
    path: /actuator/prometheus
```

### Bring-your-own OpenTelemetry agent

The app does **not** push OTLP by default — `management.otlp.metrics.export` is
off unless you set an endpoint. Attach the OpenTelemetry Java agent (e.g. via
`JAVA_TOOL_OPTIONS=-javaagent:/path/opentelemetry-javaagent.jar`) and it owns the
OTLP leg with **no double export**.

Enable the chart's agent-friendliness block so the agent inherits a stable
identity consistent with `NodeIdentity` and the common metric tags:

```yaml
observability:
  otelAgent:
    enabled: true
    serviceName: epistola-suite
    serviceNamespace: acme # optional
    deploymentEnvironment: production # falls back to support.installation.environment
    resourceAttributes: {} # extra key=value attributes
```

This injects:

- `POD_NAME` (downward API `metadata.name`)
- `OTEL_SERVICE_NAME`
- `OTEL_RESOURCE_ATTRIBUTES=service.instance.id=$(POD_NAME),service.namespace=…,deployment.environment=…`

so `service.instance.id` matches the suite's own `instance` tag (pod name).

> **Recent agent required.** Use a current OpenTelemetry Java agent — older
> agents do not instrument virtual threads (JDK 25 / Loom) correctly and can
> pin carriers. Prefer the latest stable release.

To export OTLP **from the app itself** instead (no agent), point it at a
collector — this turns on the autoconfigured Micrometer OTLP registry:

```yaml
observability:
  otlpEndpoint: "http://otel-collector:4318"
```

Do not enable both the agent and `otlpEndpoint` for metrics, or you double-export.

## Hub leg

Forwarding metrics to **epistola-hub** (the commercial support tier) is a
dedicated, isolated OTLP push leg the suite owns end-to-end — distinct from the
bring-your-own agent / `management.otlp.metrics.export` self-export leg above, so the
two never double-export. It lives in `modules/epistola-support-telemetry`:
a second Micrometer `OtlpMeterRegistry` is registered as a Spring bean
(`TelemetryMetricsConfiguration`) so Boot composes it and fans every meter to it, with a
custom `OtlpMetricsSender` (`GrpcOtlpMetricsSender`) that ships the OTLP payload to the hub
**over gRPC** — the same `MetricsService/Export` call the hub serves, on the same gRPC
endpoint the suite already resolves for the hub (no separate port or proxy). The sender
gates per-publish (registered + entitled). Logs are wired separately by `TelemetryLeg` (a
Logback appender).
Per [ADR 0006](adr/0006-shipping-logs-and-metrics-to-hub.md) it is gated globally — off
unless `epistola.support.telemetry.enabled=true` **and** the installation holds an
installation-wide `support-telemetry` entitlement (the capability is
installation-wide; there is no per-tenant metrics toggle). The endpoint is resolved by
the support module from the hub gRPC endpoint (override with
`epistola.support.hub.telemetry-endpoint`). The
per-tenant `tenant` tag is **stripped before forwarding** by default
(`strip-tenant-tag-from-metrics`), keeping the hub feed data-residency-friendly; the
local Prometheus / self-export leg keeps it. The hub authenticates the OTLP gRPC stream
with the installation's `x-ep-api-key` (the same credential as its other gRPC services)
and requires an installation-wide `support-telemetry` entitlement (else `PERMISSION_DENIED`);
real ingestion is still to come (the hub discards the payload for now).
