# Helm charts

Two charts live here: `epistola` (the app) and `epistola-grafana` (observability). They release on
their own tags — `<chart>-<version>`, e.g. `epistola-0.10.0` — which deliberately do not match the
app's `v*` glob, so a chart release never triggers the app pipeline and vice versa.

## Rules

- **Chart changes go in `charts/epistola/CHANGELOG.md`**, not the root one. The root changelog is
  the app's and feeds the in-app dialog; the chart changelog does not.
- The version in `Chart.yaml` is the source of truth for a chart release: the publish workflow
  asserts the tag matches it, so a leftover `-SNAPSHOT` fails the release loudly.
- A new value needs a documented default and effect. An operator-visible change — a new knob, a
  changed default, anything affecting upgrade — belongs in the chart changelog and in the operator
  docs, not only in the template.
- PRs touching `charts/**` run lint and the render tests only; publishing happens on the tag.

## Verify

```bash
helm lint charts/epistola
charts/epistola/tests/vpa-render.sh
charts/epistola/tests/hpa-render.sh
charts/epistola/tests/migration-render.sh
scripts/test-helm-chart.sh          # kind smoke test, needs Docker
```

The `release-helm-chart` skill drives an actual release.
