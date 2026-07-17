# pdfrender — the slim render worker

`apps/pdfrender` is a second, purpose-built Spring Boot app that does exactly one thing: poll
the shared job queue for pending document-generation requests and render the PDFs. No UI, no
REST/MCP surface, no migrations, no maintenance/cron work. It exists so PDF rendering can scale
horizontally and be isolated — from the control plane's blast radius and behind a least-privilege
database user — independently of the main suite (`apps/epistola`).

## How it stays slim

- **By dependency, not by disabling.** The whole render/job pipeline (`JobPoller`,
  `StaleJobRecovery`, `DocumentGenerationExecutor`, `GenerationService`) and the pure PDF renderer
  live in `modules/epistola-core` (transitively `modules/generation`). pdfrender depends on
  **only** `epistola-core`, so the UI (`epistola-web`, `editor`), REST (`rest-api`), MCP, and all
  commercial-support modules are simply not on its classpath and their beans are never
  component-scanned.
- **By capability routing.** Every cluster scheduled task is gated on a node **capability**,
  enforced in SQL at claim time. The two render tasks require the `pdf-render` capability
  (`ClusterProperties.PDF_RENDER_CAPABILITY`); every other task (partition maintenance, content
  reaper/backfill, installation-stats, log retention, stale-consumer reaper, the scheduled-task
  reconciler, quality, version-check, hub/support sync) requires the default `suite` capability.
  pdfrender advertises `pdf-render` **alone** (`epistola.cluster.capabilities: [pdf-render]`), so it
  can never be routed a `suite` task — including the one that needs DDL
  (`PartitionMaintenanceScheduler`), which its DB user could not run anyway. This is guaranteed by
  construction, not by turning schedulers off one at a time. The capability is deliberately
  `pdf-render` (not a generic `render`) so future renderer types get their own capability and their
  own workers.

The suite renders by default (it advertises `[suite, pdf-render]`). Set
`epistola.generation.pdf-render.enabled=false` on the suite to make it a control plane only and
push all rendering onto pdfrender workers — that is the **same** property the pdfrender app uses as
its own render on/off switch. See the pdf-render-capability seam in
[`ClusterProperties`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/cluster/ClusterConfiguration.kt).

## No migrations, limited DB user

pdfrender never migrates: `spring.flyway.enabled=false` and `epistola.migration.mode=validate`
(read-only schema check, no DDL, no clean). The suite — or a dedicated migration Job — remains the
sole owner of the schema. Because the worker issues no DDL and only reads/writes the render tables,
it can connect as a limited role with **no** `CREATE`/`ALTER`/`DROP` privileges. Broadly it needs:

- `INSERT`/`UPDATE`: `document_generation_requests`, `documents`, `document_content`,
  `generation_results` (+ its sequence), `document_generation_batches`, and the cluster
  coordination tables (`cluster_nodes`, `cluster_tasks_scheduled`, `cluster_timers`).
- `SELECT`: the render read-set — `template_versions`/`template_variants`, `document_templates`,
  `catalogs`, `tenants`, `environments`/`environment_activations`, `contract_versions`, `assets`/
  `asset_content` (+ legacy `content_store`), `fonts`/`font_variants`, plus `flyway_schema_history`
  for the validate check.

(A hardened `GRANT` script and Helm wiring are tracked as follow-ups.)

## Required shared configuration

pdfrender must point at the **same database and secrets** as the suite:

- `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` — the
  suite's database (as the limited role).
- `EPISTOLA_ENCRYPTION_*` — the **same** encryption keyset as the suite. A render read path may
  decrypt stored credentials; the ephemeral dev key cannot decrypt suite-encrypted data.
- `epistola.storage.backend` (and, for `s3`, its credentials) — must match the suite.
- `EPISTOLA_NODE_ID` — set a **distinct** id per process when co-locating with the suite on one
  host (node identity derives from the hostname otherwise, so two processes on one host collide).

## Build & run

```bash
# Build the fat jar
./gradlew :apps:pdfrender:bootJar

# Run against the suite's database (as a limited role)
SPRING_DATASOURCE_URL=jdbc:postgresql://…/epistola \
SPRING_DATASOURCE_USERNAME=pdfrender \
SPRING_DATASOURCE_PASSWORD=… \
EPISTOLA_ENCRYPTION_PRIMARYKEYID=k1 EPISTOLA_ENCRYPTION_KEYS_0_ID=k1 EPISTOLA_ENCRYPTION_KEYS_0_MATERIAL=… \
EPISTOLA_NODE_ID=pdfrender-1 \
java -jar apps/pdfrender/build/libs/pdfrender-*.jar

# Container image (reuses the suite's fonts run image, epistola-run:noble)
./gradlew :apps:pdfrender:bootBuildImage
```

Health/readiness probes are on the main port (`server.port: 4010` by default) at `/livez` and
`/readyz`, plus `/actuator/health` and `/actuator/prometheus`.

See also [`docs/cluster-resilience.md`](cluster-resilience.md) for how render nodes participate in
the cluster (heartbeats, capability routing, stale-job recovery).
