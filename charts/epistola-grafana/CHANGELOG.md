# Epistola Grafana Chart Changelog

## [Unreleased]

## [0.1.1] - 2026-07-05

### Added

- `grafana.allowCrossNamespaceImport` value (default `false`), rendered on the `GrafanaFolder`, all `GrafanaDashboard`s and the `GrafanaAlertRuleGroup`. grafana-operator only binds a CR to a Grafana instance in the **same namespace** unless this is set; enable it when a central Grafana (e.g. in an `observability` namespace) must adopt dashboards defined in an application namespace. Without it the CRs report `NoMatchingInstance` and never reach Grafana.

## [0.1.0] - 2026-07-05

### Added

- Initial release. Grafana dashboards (Overview, Generation, Infrastructure, Mediator, API Security) and alert rules for Epistola Suite, as grafana-operator custom resources, extracted from the `epistola` app chart so the app chart no longer depends on grafana-operator. Dashboards and alerts anchor to a `GrafanaFolder` by a **pinned `folderUID`** (stable by construction), avoiding grafana-operator's immutable-`folderRef` upgrade trap. Configure via the top-level `grafana.*` values (`enabled`, `instanceSelector`, `datasourceUid`, `folder.{title,uid}`). Requires grafana-operator + its CRDs installed in the cluster.
