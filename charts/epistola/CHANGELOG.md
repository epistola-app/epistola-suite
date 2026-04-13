# Epistola Helm Chart Changelog

## [Unreleased]

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
