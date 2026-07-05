---
name: release-helm-chart
description: Release a Helm chart (epistola or epistola-observability) to a new version. Use when the user wants to release, publish, or cut a new Helm chart version.
---

Release a new version of a Helm chart. Releases are **version-driven**: the
`version` in `charts/<chart>/Chart.yaml` is the source of truth. You bump it and
finalize the changelog in a PR; on merge, CI (`.github/workflows/helm.yml`)
publishes any chart whose version is not yet in the registry — packaging it, pushing
to `oci://ghcr.io/epistola-app/charts/<chart>`, and cutting a `<chart>-<version>`
GitHub Release whose **notes are that chart's CHANGELOG section**. There are no
manual release tags and no `gh release create`.

## Charts

- `charts/epistola` — the application chart.
- `charts/epistola-observability` — Grafana dashboards/alerts (grafana-operator CRs).

Each is versioned and released **independently**. Only bump the chart(s) you changed.

## Prerequisites

- Chart changes are on a branch (or ready to be), targeting `main`.

## Steps

### 1. Determine the next version (per changed chart)

Compare against the currently-published version and the chart-scoped commits since:

```bash
CHART=epistola   # or epistola-observability
helm show chart "oci://ghcr.io/epistola-app/charts/${CHART}" 2>/dev/null | grep '^version:'
git log --oneline -- "charts/${CHART}/"
```

Apply semver from the conventional commits that touch `charts/${CHART}/` (pre-1.0,
a breaking change is a MINOR bump; post-1.0 it is MAJOR):

- `feat!:` / `BREAKING CHANGE:` → MAJOR (post-1.0) or MINOR (pre-1.0)
- `feat:` → MINOR
- `fix:`/`docs:`/`chore:`/`refactor:`/`test:` → PATCH

### 2. Bump `Chart.yaml` and finalize the CHANGELOG

- Set `version:` in `charts/${CHART}/Chart.yaml` to the new version.
- In `charts/${CHART}/CHANGELOG.md`, move the `[Unreleased]` section to a dated
  heading and add a fresh empty `[Unreleased]` above it:

  ```
  ## [Unreleased]

  ## [X.Y.Z] - YYYY-MM-DD
  ```

  **This section is the GitHub Release body** — CI extracts everything between
  `## [X.Y.Z]` and the next `## [` heading. If the section is missing or empty, the
  release job **fails**. So write real, user-facing notes here. Never skip it.

`appVersion` is synced to the latest app tag by CI at publish time — leave it.

### 3. Open the PR and confirm

Show the user the chart(s), the version(s), and the CHANGELOG entries. Ask for
permission, then push the branch and open the PR (do not push to `main` directly).

### 4. CI publishes on merge — verify

On merge to `main`, the release job runs per chart and **skips any chart whose
version already exists** (idempotent). For a bumped chart it packages, pushes to
OCI, and creates the `<chart>-<version>` GitHub Release from the CHANGELOG. Tell the
user to watch it:

```bash
gh run list --workflow=helm.yml --limit 1
gh release view <chart>-X.Y.Z   # confirm notes = the CHANGELOG section
```

## Important

- **Version-driven, not tag-driven** — never run `gh release create chart-*`; CI
  owns release creation. Releasing = a merged PR that bumps `Chart.yaml` version.
- The release notes come **only** from the chart's `CHANGELOG.md` `[X.Y.Z]` section.
- Version each chart independently; a bump to one does not release the other.
- Consumers (epistola-infra, clients) pull from the OCI registry and auto-bump via
  Flux ImagePolicy — you don't touch them here.
