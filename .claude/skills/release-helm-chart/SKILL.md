---
name: release-helm-chart
description: Release the Helm chart to a new version. Use when the user wants to release, publish, or cut a new Helm chart version.
---

Release a new version of the Epistola Helm chart.

## Prerequisites

- You must be on the `main` branch with a clean working tree
- All chart changes must be merged to `main`

## Steps

### 1. Determine the next chart version

Get the latest chart tag and the chart-related commits since it:

```bash
git fetch --tags
LATEST_CHART_TAG=$(git tag -l "chart-*" --sort=-v:refname | head -1)
echo "Latest chart tag: $LATEST_CHART_TAG"
git log "$LATEST_CHART_TAG"..HEAD --oneline -- charts/
```

Apply semantic versioning based on conventional commits (only considering commits that touch `charts/`):

- Any `feat:` commit → bump **MINOR** (e.g., 0.1.0 → 0.2.0)
- Only `fix:`/`docs:`/`chore:`/`refactor:`/`test:` → bump **PATCH** (e.g., 0.1.0 → 0.1.1)
- Any `feat!:` or `BREAKING CHANGE` → bump **MAJOR** (e.g., 0.1.0 → 1.0.0)

### 2. Prepare the chart CHANGELOG

Move the `[Unreleased]` section in `charts/epistola/CHANGELOG.md` to a new version heading:

```
## [X.Y.Z] - YYYY-MM-DD
```

Add a fresh empty `[Unreleased]` section above it. Commit this change:

```
docs: update helm chart changelog for chart-X.Y.Z release
```

### 3. Ask for confirmation

Before creating the release, show the user:

- The version number
- The commits included
- Ask for permission to proceed

### 4. Push and create the release

Build the release body from the `[X.Y.Z]` section of `charts/epistola/CHANGELOG.md` (everything between the version heading and the next `## [` heading). Append install/upgrade instructions:

```markdown
## Installation

\`\`\`bash
helm install epistola oci://ghcr.io/epistola-app/charts/epistola --version X.Y.Z
\`\`\`

## Upgrade

\`\`\`bash
helm upgrade epistola oci://ghcr.io/epistola-app/charts/epistola --version X.Y.Z
\`\`\`
```

Then create the release:

```bash
COMMIT_SHA=$(git rev-parse HEAD)
git push origin main
gh release create chart-X.Y.Z --title "Helm Chart X.Y.Z" --notes "$RELEASE_BODY" --target "$COMMIT_SHA"
```

The `--target` flag pins the release to the changelog commit, preventing issues if another commit lands on main between the push and release creation.

### 5. Verify

After creating the release, tell the user:

- The release URL
- That CI will automatically package and publish the Helm chart to the OCI registry
- They can monitor the workflow at: `gh run list --workflow=helm.yml --limit 1`

## Important

- Chart tags follow the `chart-X.Y.Z` format (e.g., `chart-0.2.0`) — no `v` prefix
- Only consider commits that touch `charts/` when determining the version bump
- Never skip the chart CHANGELOG update (`charts/epistola/CHANGELOG.md`)
- Always ask for confirmation before creating the release
- The CI workflow (`.github/workflows/helm.yml`) triggers on `release: published` for `chart-*` tags to package, push to OCI registry, and upload the chart artifact to the GitHub release.
