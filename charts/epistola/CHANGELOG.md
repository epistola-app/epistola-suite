# Epistola Helm Chart Changelog

## [Unreleased]

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
