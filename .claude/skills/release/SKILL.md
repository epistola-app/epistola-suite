---
name: release
description: Create a new release. Use when the user wants to release a new version, cut a release, or publish a version.
---

Create a new GitHub release for epistola-suite.

## Prerequisites

- You must be on the `main` branch with a clean working tree
- All changes must be merged to `main`

## Steps

### 1. Determine the next version

Get the latest tag and the commits since it:

```bash
git fetch --tags
LATEST_TAG=$(git tag --sort=-v:refname | head -1)
echo "Latest: $LATEST_TAG"
git log "$LATEST_TAG"..HEAD --oneline
```

Apply semantic versioning based on conventional commits:

- Any `feat:` commit → bump **MINOR** (e.g., 0.6.1 → 0.7.0)
- Only `fix:`/`docs:`/`chore:`/`refactor:`/`test:` → bump **PATCH** (e.g., 0.6.1 → 0.6.2)
- Any `feat!:` or `BREAKING CHANGE` → bump **MAJOR** (e.g., 0.6.1 → 1.0.0)

### 2. Prepare the CHANGELOG

Move the `[Unreleased]` section in `CHANGELOG.md` to a new version heading:

```
## [X.Y.Z] - YYYY-MM-DD
```

Add a fresh empty `[Unreleased]` section above it. Commit this change:

```
docs: update changelog for vX.Y.Z release
```

### 3. Ask for confirmation

Before creating the release, show the user:

- The version number
- The commits included
- Ask for permission to proceed

### 4. Push and create the release

```bash
COMMIT_SHA=$(git rev-parse HEAD)
git push origin main
gh release create vX.Y.Z --title "vX.Y.Z" --generate-notes --target "$COMMIT_SHA"
```

The `--generate-notes` flag auto-generates release notes from PRs and commits. The `--target` flag pins the release to the changelog commit, preventing issues if another commit (e.g., coverage badge) lands on main between the push and release creation.

### 5. Verify

After creating the release, tell the user:

- The release URL
- That CI will automatically build and publish the Docker image
- They can monitor the workflow at: `gh run list --workflow=build.yml --limit 1`

## Important

- Tags must follow the `vX.Y.Z` format (e.g., `v0.7.0`)
- Never skip the CHANGELOG update
- Always ask for confirmation before creating the release
- The CI workflow (`.github/workflows/build.yml`) triggers on `release: published` to build, tag, sign, and publish the Docker image. Docker images are NOT built on push-to-main to avoid race conditions between push and release events.
