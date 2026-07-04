# Epistola Helm Chart Changelog

## [Unreleased]

### Added

- **`migration.credentials` — run the migration step as a separate database role.** Set `migration.credentials.username` (+ `existingSecret` / `passwordKey`) to point the migration Job / init container at the DDL-holding migration role, while the app pods keep using `database.*`. This lets the app's own role run with **no DDL privileges** — the app performs partition maintenance only via SECURITY DEFINER functions (see [#438](https://github.com/epistola-app/epistola-suite/issues/438) and `docs/deployment.md`). The database URL is shared (same host/db); only the username/password differ. Empty (default) → the migration step reuses the app credentials, so existing single-role deployments are unchanged.
- **`logging.level` / `logging.appLevel` — first-class log-level controls.** `logging.level` (default empty) sets the root logger via `LOGGING_LEVEL_ROOT`; `logging.appLevel` (default empty) sets the application's own `app.epistola` packages via `LOGGING_LEVEL_APP_EPISTOLA` (e.g. `DEBUG` to trace app behaviour without making every library verbose). Both blank → the app's built-in defaults apply and no env is injected. Rendered on the **app Deployment only** — the migration step keeps its own minimal logging. For any other logger, set the corresponding `LOGGING_LEVEL_<LOGGER>` env var via `config.env`. Replaces the previous need to hand-write `LOGGING_LEVEL_ROOT` under `config.env`.
- **`datasource.hikari` — connection-pool sizing and app-side socket timeout.** `datasource.hikari.maximumPoolSize` (default `20`) sets the per-replica pool size, rendered as `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` and pinned to `MINIMUM_IDLE` (fixed-size pool). `datasource.hikari.socketTimeoutSeconds` (default `30`) sets the app's pgjdbc `socketTimeout` so a wedged read (post DB-failover / network blip) self-heals instead of pinning the pool. The base liveness settings (`keepalive-time`, `max-lifetime`, leak detection, `tcpKeepAlive`) ship in the app's `application.yaml` and apply by default. **Capacity note:** each replica owns its own pool, so ensure Postgres `max_connections` exceeds `(maxReplicas × maximumPoolSize) + migration job + reserve`. Replaces the previous commented `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` hint under `config.env`.
- **`migration.socketTimeoutSeconds` — migration-JVM socket-timeout exemption.** Default `0` (no socket timeout). The migration container shares the app's `application.yaml` but runs long DDL that can read with no socket traffic past the app's 30s `socketTimeout`; it now overrides `socketTimeout` (and disables leak detection) so long migrations aren't aborted. Migrations stay bounded by `migration.job.activeDeadlineSeconds`.

## [0.7.0] - 2026-06-16

### Added

- **`support.telemetry.enabled` — forward logs + metrics to epistola-hub.** **On by default** when `support.enabled=true` (set `false` to opt out); rendered as `EPISTOLA_SUPPORT_TELEMETRY_ENABLED` on the app Deployment. The app ships application logs and metrics to the hub over OTLP-over-gRPC, but the installation's `support-telemetry` entitlement from the hub is the real gate — nothing is shipped until the installation is entitled. The OTLP endpoint is the hub's own gRPC endpoint, derived at runtime by the support module — nothing to configure. See [`docs/adr/0006-shipping-logs-and-metrics-to-hub.md`](../../docs/adr/0006-shipping-logs-and-metrics-to-hub.md).

- **`encryption` — credential encryption-at-rest keyset.** Wires the app's AES-256-GCM credential encryption (catalog/code-list auth, hub credentials). `encryption.primaryKeyId` selects the key used for new encryptions; `encryption.keys` is the keyset (each entry an `id` plus a 32-byte base64 key, sourced from a Kubernetes Secret via `existingSecret`/`secretKey`, or inline `material` for dev). Rendered as indexed `EPISTOLA_ENCRYPTION_*` env on the **app Deployment only** (the migration Job never touches ciphertext). **Required in production:** the app's `prod` profile fails to start if `encryption.enabled=true` (the default) but no key is configured; generate one with `openssl rand -base64 32` and store it in a Secret. Multiple keys enable no-downtime rotation. See [`docs/encryption.md`](../../docs/encryption.md). Note: this configures the **encryption keyset**, not application-generated API keys — those are created at runtime per tenant and stored hashed, so there is nothing for the chart to inject.

- **`observability.otelAgent` — bring-your-own OpenTelemetry agent friendliness.** Off by default. When enabled, the chart injects `POD_NAME` (downward API `metadata.name`), `OTEL_SERVICE_NAME` (default `epistola-suite`), and `OTEL_RESOURCE_ATTRIBUTES` (`service.instance.id=$(POD_NAME)`, optional `serviceNamespace`, and `deploymentEnvironment` falling back to `support.installation.environment`, plus any extra `resourceAttributes`). An operator-attached OTel Java agent then inherits an identity consistent with the app's own `NodeIdentity` (pod name) and common metric tags. The app's own Micrometer OTLP export stays off unless `observability.otlpEndpoint` is set, so the agent owns the OTLP leg with no double export. The epistola-hub leg (commercial support tier) is configured at runtime by the support module, not here. See [`docs/metrics.md`](../../docs/metrics.md#bring-your-own-opentelemetry-agent).

### Changed

- **Health probes hit `/livez` / `/readyz` on the main port; Prometheus scrape targets the management port (4040).** In production the actuator endpoints move to a separate, cluster-internal management port (`management.server.port=4040`), so the chart no longer probes `/actuator/health/*`. Startup/liveness use `/livez`, readiness uses `/readyz` on the main `http` port (these work in every profile). A `management` containerPort (4040) is declared for in-cluster scraping but is intentionally **not** added to the Service, so it is never exposed via the Ingress. `observability.prometheus.port` now defaults to `4040` (set it to `4000` only if you run without the `prod` profile).

### Removed

- **`support.hub.nodeId` value and node-id env wiring.** Node identity must differ per pod, but a chart value lives on the Deployment and is identical for every replica — so it could never provide per-pod identity. The app now resolves node identity at runtime (`NodeIdentity`: hostname → pod name, which Kubernetes sets to the pod name), giving each replica a distinct id automatically and a meaningful `instance` metric tag out of the box. The downward-API `EPISTOLA_NODE_ID` injection and the `support.hub.nodeId` value are both gone. (The app property `epistola.support.hub.node-id` remains for rare single-instance overrides outside Kubernetes.)

## [0.6.0] - 2026-06-01

### Added

- **`extraCaCerts` — trust client-provided root CA certificates for outbound TLS.** Set `extraCaCerts.enabled=true` and supply PEM cert(s) either inline (`extraCaCerts.certs`, rendered into a Secret by the chart) or via a pre-existing Secret (`extraCaCerts.existingSecret`, keys = PEM files). The chart delivers them as a Paketo [`ca-certificates`](https://github.com/paketo-buildpacks/ca-certificates) service binding — a projected volume (a chart-managed `type` ConfigMap + the cert Secret) mounted under `SERVICE_BINDING_ROOT` — and the buildpack's launch helper merges the certs into the JVM truststore at startup, alongside the public CA bundle (certs are added, not substituted, so public-internet HTTPS keeps working). No init container, no `keytool`, no `JAVA_TOOL_OPTIONS` override; compatible with the hardened pod (`readOnlyRootFilesystem`, non-root). Use this when the suite must reach an internal Keycloak, a private catalog server, the hub, or a TLS-inspecting egress proxy signed by a private CA. See [`docs/deployment.md`](../../docs/deployment.md#trusting-a-client-root-ca-extracacerts).

## [0.5.0] - 2026-05-22

### Added

- **`migration.job.annotations`** — extra annotations merged onto the migration Job's metadata (`job` mode), with keys overriding the chart's `helm.sh/hook` defaults. Plain `helm` and Flux's helm-controller run Helm directly, so the hooks fire natively and need nothing here. When the chart is rendered to manifests and applied by a tool that ignores Helm hooks — Argo CD, or Flux's kustomize-controller — set this to that tool's hook annotation (e.g. `argocd.argoproj.io/hook: PreSync` plus `argocd.argoproj.io/hook-delete-policy: BeforeHookCreation`) so migrations still gate the app rollout.

- **`oidc.flatRolesClaimName`** — sets `EPISTOLA_AUTH_FLATROLES_CLAIMNAME`, overriding the JWT claim the app reads flat role labels (`epg_*` / `ept_*_*` / `eps_*`) from. Default empty → the app uses its own default (`roles`). Set it when integrating with a non-Keycloak IdP that emits the flat-role list under a different claim name. The same value re-points the auto-provisioned Keycloak `epistola-realm-roles` protocol mapper when `keycloakAdmin.ensureGroups=true`, so chart and Keycloak stay in sync.

## [0.4.0] - 2026-05-20

### Added

- **`migration` values block — DB migrations as a separate, explicit step.** New `migration.mode` selects how schema migrations run: `job` (default — a `pre-install`/`pre-upgrade` hook Job migrates once per release before app pods start; a failed migration fails the release with its own logs/exit code), `initContainer` (every app pod migrates via an init container before the app container starts), or `embedded` (the app runs Flyway at boot, single-process convenience). In `job`/`initContainer` modes the chart injects `EPISTOLA_MIGRATION_MODE=validate` so app pods only validate the schema and fail fast if it is behind — they never migrate at boot. The migration workload reuses the same image with `EPISTOLA_MIGRATION_MODE=migrate` (one knob: `embedded`/`migrate`/`validate`) and the same datasource credentials as the app (now emitted by a shared `epistola.databaseEnv` helper). A `wait-for-db` init container (`migration.waitImage`, default `busybox:1.36`) blocks on the database TCP port to tolerate CNPG's asynchronous provisioning; skipped for `database.type=none`. Tunables: `migration.resources`, `migration.job.backoffLimit`, `migration.job.activeDeadlineSeconds`, `migration.job.ttlSecondsAfterFinished`.

  > Behavior change: with the default `migration.mode=job`, migrations now gate the deploy. A fresh install with `database.type=cnpg` is rejected with an actionable `helm` error (the CNPG Cluster is created after Helm hooks, so the pre-install Job cannot reach it) — use `initContainer` or `embedded` for the first install; existing/`cnpgExisting`/`external` databases work with `job`.

## [0.3.0] - 2026-05-17

### Added

- **`support` / `support.installation` / `support.hub` values for the commercial-tier hub integration.** When `support.enabled=true`, the chart sets `EPISTOLA_SUPPORT_ENABLED=true`, `EPISTOLA_INSTALLATION_COMPANYNAME`, `EPISTOLA_INSTALLATION_ADMINEMAIL`, and `EPISTOLA_INSTALLATION_ENVIRONMENT` (the latter three are `required` and fail `helm install` with a clear message when missing). Optional overrides: `support.installation.name`, `support.installation.description`, `support.hub.discoveryUrl` (point at a self-hosted `.well-known` document — leave blank to use the SaaS default), `support.hub.nodeId`. Kubernetes deployments always resolve the hub via `.well-known` discovery; the direct host/port mode supported by the application (for local development and debugging) is intentionally not exposed in the chart. When `support.hub.nodeId` is blank, `EPISTOLA_NODE_ID` is sourced from the pod's `metadata.name` via the downward API, giving the hub stable per-replica identifiers. OSS deployments leave `support.enabled=false` — the chart emits nothing extra, and the application keeps the support module dormant.

## [0.2.1] - 2026-04-13

### Fixed

- Explicitly disable OTLP export when no endpoint is configured, preventing startup warnings

## [0.2.0] - 2026-04-13

### Added

- **`keycloak.backchannelBaseUrl`**: New value for split-horizon deployments where the server-side Keycloak URL differs from the browser-facing one.

### Changed

- **BREAKING: Split Keycloak config into `oidc` and `keycloakAdmin`**: The single `keycloak` block is now two separate sections — `oidc` for browser login and `keycloakAdmin` for admin/bootstrap wiring — so each can be configured independently.
- **BREAKING: Orthogonal Spring profile restructuring**: Profiles are now single-concern and composable. See app changelog `v0.14.0` for migration table.

## [0.1.0] - 2026-03-03

### Added

- Initial Helm chart for Kubernetes deployment
- Published to OCI registry at `oci://ghcr.io/epistola-app/charts/epistola`
- Separate versioning from the application using `chart-X.Y.Z` tags
- CloudNativePG (CNPG) support for PostgreSQL database management
- Prometheus metrics and Grafana dashboards (5 dashboards deployed as Grafana Operator CRDs)
- Startup probes and observability configuration
- Pod and container security contexts (readOnlyRootFilesystem, seccompProfile, capability drops)
