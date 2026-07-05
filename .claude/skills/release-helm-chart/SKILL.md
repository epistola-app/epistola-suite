---
name: release-helm-chart
description: Release a Helm chart (epistola or epistola-grafana) to a new version. Use when the user wants to release, publish, or cut a new Helm chart version.
---

Release a Helm chart. The chart's `Chart.yaml` `version:` is the **source of
truth**; during development it carries the next version with **`-SNAPSHOT`**. A
release strips the suffix, finalizes the CHANGELOG, and pushes a matching tag
`<chart>-<version>`; CI (`.github/workflows/helm.yml`) reacts to the tag, asserts
`tag == Chart.yaml version`, and publishes to `oci://ghcr.io/epistola-app/charts/<chart>`.
Then you re-open the next `-SNAPSHOT`. The app's `v*` tags are a separate stream —
these never cross-fire.

## Charts

- `charts/epistola` — the application chart.
- `charts/epistola-grafana` — Grafana dashboards/alerts.

Each is versioned and released **independently**; only touch the one you're releasing.

## Steps (for one chart `<C>` = charts/<name>)

### 1. Determine the release version

The current `<C>/Chart.yaml` version is `X.Y.Z-SNAPSHOT` — that `X.Y.Z` is the
release version. Sanity-check it against the chart-scoped commits since the last
release and bump if the dev value is behind (pre-1.0: a breaking change is a MINOR
bump; post-1.0 it is MAJOR):

```bash
NAME=epistola   # or epistola-grafana
git tag -l "${NAME}-*" --sort=-v:refname | head -1
git log --oneline -- "charts/${NAME}/"
```

- `feat!:`/`BREAKING CHANGE:` → MAJOR (post-1.0) / MINOR (pre-1.0)
- `feat:` → MINOR · `fix:`/`docs:`/`chore:`/`refactor:`/`test:` → PATCH

### 2. Release commit — strip -SNAPSHOT + finalize CHANGELOG

- Set `<C>/Chart.yaml` `version:` to `X.Y.Z` (no `-SNAPSHOT`).
- In `<C>/CHANGELOG.md`, move `[Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD`
  (keep an empty `[Unreleased]` above it). **This section becomes the GitHub
  Release notes**, so write real, user-facing notes.
- Commit on a branch, open a PR, merge to `main` (never push to `main` directly).

### 3. Confirm, then tag the merge commit

Show the user the chart, version, and CHANGELOG entry; ask for permission. Then,
building `$BODY` from the `[X.Y.Z]` section:

```bash
COMMIT_SHA=$(git rev-parse origin/main)   # the merged release commit
gh release create "${NAME}-X.Y.Z" --title "${NAME} X.Y.Z" --notes "$BODY" --target "$COMMIT_SHA"
```

This creates the tag + GitHub Release. CI validates `tag == Chart.yaml` and
publishes to OCI. (A tag whose commit still says `-SNAPSHOT` **fails** the check —
that's the guard against releasing a snapshot.)

### 4. Re-open the next -SNAPSHOT

In a follow-up PR, set `<C>/Chart.yaml` `version:` to the next dev version
(default `X.Y.(Z+1)-SNAPSHOT`; raise to minor/major when the next cycle's first
`feat`/breaking change lands). Merge.

### 5. Verify

```bash
gh run list --workflow=helm.yml --limit 1
helm show chart oci://ghcr.io/epistola-app/charts/${NAME} --version X.Y.Z
gh release view ${NAME}-X.Y.Z
```

## Important

- **Tag = `<chart-name>-<version>`**, disjoint from the app's `v*`. CI only reacts
  to the tag; it does not create releases or extract notes (this skill does).
- The three must agree: `Chart.yaml version` == CHANGELOG `[X.Y.Z]` == the tag. CI
  enforces `tag == Chart.yaml`.
- Dev branches always carry `-SNAPSHOT`; a real release is the strip → tag →
  re-open cycle. This works per-branch, so a `release/*.x` maintenance branch has
  its own snapshot lineage.
- Consumers (epistola-infra, clients) pull from OCI and auto-bump via Flux
  `ImagePolicy` — you don't touch them here.
