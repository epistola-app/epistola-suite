# Epistola Helm Chart Changelog

## [Unreleased]

### Removed

- **BREAKING: Grafana dashboards + alerts moved to the new `epistola-observability` chart.** The grafana-operator custom resources (5 `GrafanaDashboard` + `GrafanaAlertRuleGroup` + `GrafanaFolder`) and the `observability.grafana.*` values are gone from this chart — an application chart should not couple its render to grafana-operator's CRDs. Install `epistola-observability` alongside this chart on clusters running grafana-operator, and set the (renamed, top-level) `grafana.*` values there. `observability.prometheus` (scrape annotations) and `observability.otelAgent` stay here. This also **resolves the 0.9.0 upgrade failure** for grafana users: 0.9.0 tried to change the alert's immutable `spec.folderRef` in place (`Value is immutable`); with the CRs removed here they are pruned, not patched, and recreated cleanly by the new chart — which anchors dashboards and alerts to a **pinned folder UID** (`folderUID`) so the immutable field is stable by construction.

## [0.9.0] - 2026-07-05

### Changed (breaking)

- **BREAKING: renamed value keys for consistency.** `database.cnpgExisting.secretName` → `database.cnpgExisting.existingSecret`; `migration.credentials.passwordKey` → `migration.credentials.secretKey` (one name for "existing Secret" / "key within it" across the `database` and `migration` blocks); `observability.grafana.datasourceName` → `observability.grafana.datasourceUid` (it is the datasource *uid*, not a display name). Update your values accordingly.

### Removed

- **BREAKING: inline secret values removed — secrets must come from a Kubernetes Secret.** `oidc.clientSecret`, `keycloakAdmin.clientSecret`, and `encryption.keys[].material` are gone; use `oidc.existingSecret`, `keycloakAdmin.existingSecret`, and `encryption.keys[].existingSecret` (all now `required` when the feature is enabled). Previously an inline value rendered as a **plaintext env var** in the Deployment (and was stored in the Helm release Secret) — the chart now never places secret material in the pod spec, values.yaml, or the release. CA certs (`extraCaCerts.certs`) are unaffected — they are public, not secret. (App-level `epistola.encryption.keys[].material` still exists for local/dev config; only the chart drops the inline path.)
- **BREAKING: `database.cnpgExisting.username` (+ its `existingSecret` / `passwordKey`) removed.** The knob was meant to run the app as a restricted role on a CNPG cluster (two-role setup), but it was silently broken: `cnpgExisting` builds the datasource URL from CNPG's `-app` secret `jdbc-uri`, which **embeds the owner's `user=`/`password=`**, and pgjdbc lets URL credentials override the explicit `SPRING_DATASOURCE_USERNAME` — so the app connected as the **owner** (full DDL) regardless, defeating the split. **For a two-role setup on CNPG, use `database.type=external`** pointed at the cluster's `-rw` service: `external` builds a credentials-free URL, so the restricted app role actually takes effect (this is the validated path). `cnpgExisting` is now single-role only (app = owner). Setting `migration.credentials` together with `cnpgExisting` now **fails the render** with a pointer to `external` (that combination had the same `jdbc-uri` flaw and no valid use — a single-role app is already the owner). See the rewritten two-role recipe in [`docs/deployment.md`](../../docs/deployment.md).

### Added

- **`values.schema.json`** — validates the datasource/migration wiring at render time: `database.type` (`none`/`external`/`cnpgExisting`) and `migration.mode` (`job`/`initContainer`/`embedded`) enums, and requires `database.external.host` + `existingSecret` when `database.type=external`. Catches typos (e.g. the removed `cnpg` type) with a clear message before templating.

### Changed

- **`serviceAccount.automount` now defaults to `false`.** The app makes no Kubernetes API calls, so its pods no longer auto-mount a ServiceAccount token. Set `true` if you add something that needs API access.
- **`database.type=external` now fails the render when `database.external.host` is empty** (mirroring the existing password guard), instead of emitting a broken `jdbc:postgresql://:5432/…` URL that only failed at pod startup.
- Moved the `datasource.hikari` pool-tuning block adjacent to `database` in `values.yaml` so all database config is contiguous (no key change).
- **`observability.prometheus.port` now derives from `config.profiles`** (default blank): the scrape target follows the actuator — `4040` under the `prod` profile, `4000` without — so the two can no longer silently disagree. Set an explicit port to override.

### Fixed

- **The migration Job's pod now carries the full label set + `podLabels`/`podAnnotations`** (previously only selector labels). This is the pod that opens the database connection, so it needs the same service-mesh / NetworkPolicy / egress hooks the app pods get — otherwise a policy keyed on `podLabels` would silently exclude it and the pre-upgrade migration could fail to reach the DB.
- **Grafana alerts now reconcile into the intended folder.** `GrafanaAlertRuleGroup.folderRef` was fed the folder *title* (`observability.grafana.folder`), but `folderRef` must name a `GrafanaFolder` CR. Added a `GrafanaFolder` template (gated by `observability.grafana.enabled`); the alert group now references it by name, co-located with the dashboards.
- Removed a dead `datasource` template variable from all five dashboards — panels reference the datasource uid directly, so the picker did nothing.

### Internal

- DB/migration credential helpers de-duplicated: a shared `epistola.database.externalUrl` helper, `epistola.database.effectiveType` now centralizes supported-type validation, and the dead `cnpgExisting` branch left by the `cnpgExisting.username` removal is gone. No behavior change.
- Documented the grafana-operator CRD prerequisite for `observability.grafana.enabled`.

## [0.8.0] - 2026-07-04

### Added

- **`database.cnpgExisting.username` — connect the app as a restricted role on a CNPG database.** For the secure two-role setup on CloudNativePG: the cluster **owner** runs migrations (holds DDL) and the **app** connects as a separate DML-only role. Set `database.cnpgExisting.username` (+ `existingSecret` / `passwordKey`) and only the **app pods** use that role; the database URL/host still come from the cluster's `-app` secret, and the **migration step keeps using the owner** by default (no `migration.credentials` needed). Empty (default) → app uses the owner, single-role, unchanged. Provision the app role on the cluster via `spec.managed.roles` with its grants — see the CNPG two-role recipe in `docs/deployment.md`. (Complements `migration.credentials`, which does the same on the migration side for `external` databases.)
- **`migration.credentials` — run the migration step as a separate database role.** Set `migration.credentials.username` (+ `existingSecret` / `passwordKey`) to point the migration Job / init container at the DDL-holding migration role, while the app pods keep using `database.*`. This lets the app's own role run with **no DDL privileges** — the app performs partition maintenance only via SECURITY DEFINER functions (see [#438](https://github.com/epistola-app/epistola-suite/issues/438) and `docs/deployment.md`). The database URL is shared (same host/db); only the username/password differ. Empty (default) → the migration step reuses the app credentials, so existing single-role deployments are unchanged.
- **`logging.level` / `logging.appLevel` — first-class log-level controls.** `logging.level` (default empty) sets the root logger via `LOGGING_LEVEL_ROOT`; `logging.appLevel` (default empty) sets the application's own `app.epistola` packages via `LOGGING_LEVEL_APP_EPISTOLA` (e.g. `DEBUG` to trace app behaviour without making every library verbose). Both blank → the app's built-in defaults apply and no env is injected. Rendered on the **app Deployment only** — the migration step keeps its own minimal logging. For any other logger, set the corresponding `LOGGING_LEVEL_<LOGGER>` env var via `config.env`. Replaces the previous need to hand-write `LOGGING_LEVEL_ROOT` under `config.env`.
- **`datasource.hikari` — connection-pool sizing and app-side socket timeout.** `datasource.hikari.maximumPoolSize` (default `20`) sets the per-replica pool size, rendered as `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` and pinned to `MINIMUM_IDLE` (fixed-size pool). `datasource.hikari.socketTimeoutSeconds` (default `30`) sets the app's pgjdbc `socketTimeout` so a wedged read (post DB-failover / network blip) self-heals instead of pinning the pool. The base liveness settings (`keepalive-time`, `max-lifetime`, leak detection, `tcpKeepAlive`) ship in the app's `application.yaml` and apply by default. **Capacity note:** each replica owns its own pool, so ensure Postgres `max_connections` exceeds `(maxReplicas × maximumPoolSize) + migration job + reserve`. Replaces the previous commented `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` hint under `config.env`.
- **`migration.socketTimeoutSeconds` — migration-JVM socket-timeout exemption.** Default `0` (no socket timeout). The migration container shares the app's `application.yaml` but runs long DDL that can read with no socket traffic past the app's 30s `socketTimeout`; it now overrides `socketTimeout` (and disables leak detection) so long migrations aren't aborted. Migrations stay bounded by `migration.job.activeDeadlineSeconds`.

### Removed

- **BREAKING: `database.type=cnpg` (chart-managed CloudNativePG cluster) removed.** The chart no longer provisions a database — an app chart owning a stateful database couples their lifecycles and risks data loss (the rendered `Cluster` had no retention policy, so `helm uninstall` would delete it). Supported types are now `external`, `cnpgExisting`, and `none`; the chart always **consumes** a database it doesn't own. If you used `database.type=cnpg`, own the CNPG `Cluster` yourself (see the new [`examples/cnpg-cluster.yaml`](examples/cnpg-cluster.yaml)) and switch to `database.type=cnpgExisting`. Setting the removed type now fails the render with an actionable message. This also removes the `database.cnpg.*` values, the CNPG cluster template, and the `job`-mode fresh-install ordering guard (which only existed because of the chart-managed cluster).

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
