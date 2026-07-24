<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# GitHub Repository Guide

This document explains how GitHub is configured for the Epistola Suite project, covering CI/CD, issue management, releases, and community features.

## Table of Contents

- [CI/CD Workflows](#cicd-workflows)
  - [Build and Test](#build-and-test)
  - [Docker Publishing](#docker-publishing)
  - [Helm Chart Publishing](#helm-chart-publishing)
  - [Label Sync](#label-sync)
  - [Project Sync](#project-sync)
- [Versioning and Releases](#versioning-and-releases)
- [SBOM (Software Bill of Materials)](#sbom-software-bill-of-materials)
- [Issue Management](#issue-management)
  - [Issue Templates](#issue-templates)
  - [Labels](#labels)
- [Pull Requests](#pull-requests)
- [Security](#security)
  - [Docker Image Signing](#docker-image-signing)
- [GitHub Discussions](#github-discussions)
- [GitHub App for Project Sync](#github-app-for-project-sync)
- [Repository Settings](#repository-settings)

---

## CI/CD Workflows

All workflows are defined in `.github/workflows/`.

### Build and Test

**File:** `.github/workflows/build.yml`

**Triggers:**

- Every push to `main` (and `release/**` maintenance branches)
- Every pull request targeting `main` (or a `release/**` branch)
- Every `v*` tag (the app-release build — see [Docker Publishing](#docker-publishing))

**What it does:**

1. Checks out the code
2. Sets up JDK 25 (Temurin) and Node.js 24
3. Runs `gradle build` which:
   - Compiles Kotlin backend
   - Builds TypeScript editor module
   - Runs all tests (requires Docker for Testcontainers)
   - Runs ktlint checks

**Required checks:** The build job must pass before merging PRs.

### Docker Publishing

**File:** `.github/workflows/build.yml` (docker job)

The app image is published **only for app releases** — a `v*` tag — or a PR
labelled `publish` (for testing). A normal push to `main` only compiles + tests;
it does **not** build an image.

#### On a `v*` tag (app release)

`build.yml` triggers on `push: tags: ['v*']` (and `main`/`release/**` branches +
PRs for the test jobs). On the tag it:

1. Extracts the version from the tag (`v1.0.0-RC3` → `1.0.0-RC3`) and **asserts it
   equals `gradle.properties` `version`** — a mismatch (e.g. the file still says
   `-SNAPSHOT`) fails the release loudly instead of shipping a wrong image.
2. Builds the image with `gradle :apps:epistola:bootBuildImage`.
3. Pushes `ghcr.io/epistola-app/epistola-suite` with tags `:<version>`, `:<sha>`, `:latest`.
4. Signs with Cosign (keyless OIDC), attaches the SBOM attestation, and uploads the
   SBOM artifacts to the GitHub Release.

Chart tags (`epistola-*`) deliberately do **not** match `v*`, so a chart release
never triggers the app pipeline.

#### On a PR with the `publish` label

Builds + pushes a snapshot image `…:<prev-version>-pr-<number>-<run>` (e.g.
`1.0.0-RC2-pr-42-5`) for testing. Add the `publish` label to trigger.

### Helm Chart Publishing

**File:** `.github/workflows/helm.yml`

The charts (`epistola`, `epistola-grafana`) publish **only on their own chart
tag** — `<chart>-<version>` (e.g. `epistola-0.10.0`, `epistola-grafana-0.1.0`).
PRs touching `charts/**` run linting only.

#### On a `<chart>-*` tag

`helm.yml` triggers on `push: tags: ['epistola-*']`. It:

1. Parses the chart name + version from the tag (pre-release-safe).
2. **Asserts the version equals that chart's `Chart.yaml` `version:`** — a mismatch
   (e.g. `-SNAPSHOT`) fails.
3. `helm package` + pushes to `oci://ghcr.io/epistola-app/charts/<chart>:<version>`.

The GitHub Release (with notes from the chart's CHANGELOG) is created by the
`release-helm-chart` skill; this workflow only publishes to OCI. The disjoint
`v*` / `epistola-*` tag globs keep the chart and app streams from ever crossing.

### CodeQL Analysis

**File:** `.github/workflows/codeql.yml`

**Triggers:**

- Every push to `main`
- Every pull request targeting `main`
- Weekly schedule (Saturday at 6 AM UTC)

**What it does:**

1. Runs CodeQL static analysis (SAST) on two language groups in parallel:
   - **Java/Kotlin**: Custom build (Gradle `classes` task) then analysis
   - **JavaScript/TypeScript**: Autobuild then analysis
2. Uses the `security-and-quality` query suite for broader coverage
3. Uploads SARIF results to the GitHub **Security** tab → **Code scanning**

**Results:** View findings in the repository's Security tab under Code scanning alerts.

### Label Sync

**File:** `.github/workflows/labels.yml`

**Triggers:**

- On push to `main` when `.github/labels.yml` changes
- Manual trigger via Actions UI

**What it does:**

- Syncs repository labels from `.github/labels.yml`
- Strict mode: removes labels not defined in the config

**To add/modify labels:** Edit `.github/labels.yml` and push to `main`.

### Project Sync

**File:** `.github/workflows/project-sync.yml`

**Triggers:**

- On push to `main` when `.github/project.yml` changes
- Manual trigger via Actions UI

**What it does:**

- Creates or updates the "Epistola Suite Backlog" GitHub Project
- Syncs custom fields (Status, Priority, Size, Type, etc.)
- Links the repository to the project

**Configuration:** `.github/project.yml`

This workflow uses a GitHub App for authentication since the Projects v2 API is not supported by fine-grained PATs. See [GitHub App for Project Sync](#github-app-for-project-sync) for setup.

**To modify project fields:** Edit `.github/project.yml` and push to `main`.

#### Project Fields

| Field          | Type          | Options                                               |
| -------------- | ------------- | ----------------------------------------------------- |
| Status         | Single select | Backlog, Ready, In Progress, Blocked, In Review, Done |
| Priority       | Single select | P0 Critical, P1 High, P2 Medium, P3 Low               |
| Size           | Single select | XS, S, M, L, XL                                       |
| Type           | Single select | Feature, Bug, Alert, Tech Debt, Chore                 |
| Target Date    | Date          | Expected delivery date                                |
| Started At     | Date          | When work began (for cycle time)                      |
| Blocked Reason | Text          | Why the item is blocked                               |

---

## Versioning and Releases

### Semantic Versioning

The project uses [Semantic Versioning](https://semver.org/) (MAJOR.MINOR.PATCH):

| Change Type     | Version Bump | Example       |
| --------------- | ------------ | ------------- |
| Breaking change | MAJOR        | 1.0.0 → 2.0.0 |
| New feature     | MINOR        | 1.0.0 → 1.1.0 |
| Bug fix         | PATCH        | 1.0.0 → 1.0.1 |

### Conventional Commits

Version bumps are determined automatically from commit messages:

| Commit Prefix                           | Version Bump |
| --------------------------------------- | ------------ |
| `feat:`                                 | MINOR        |
| `fix:`                                  | PATCH        |
| `docs:`, `chore:`, `refactor:`, `test:` | PATCH        |
| `feat!:` or `fix!:`                     | MAJOR        |
| Footer contains `BREAKING CHANGE:`      | MAJOR        |

### Release Process

The repo ships **three independently-versioned artifacts**, each released on its
own tag. Versions are **file-truth**: every artifact's version lives in a file,
and during development carries the next version with **`-SNAPSHOT`** — so a branch
never claims a released version it isn't.

| Artifact  | Version file (source of truth)                  | Dev value            | Release tag              |
| --------- | ----------------------------------------------- | -------------------- | ------------------------ |
| App image | `gradle.properties` `version=`                  | `1.0.0-RC3-SNAPSHOT` | `v1.0.0-RC3`             |
| App chart | `charts/epistola/Chart.yaml` `version:`         | `0.10.0-SNAPSHOT`    | `epistola-0.10.0`        |
| Obs chart | `charts/epistola-grafana/Chart.yaml` `version:` | `0.1.0-SNAPSHOT`     | `epistola-grafana-0.1.0` |

**To release an artifact** (use the [`release`](../.claude/skills/release) skill
for the app, [`release-helm-chart`](../.claude/skills/release-helm-chart) for a
chart — same three-step shape):

1. In a PR: **strip `-SNAPSHOT`** in the version file → the release version;
   **finalize that artifact's CHANGELOG** `## [X.Y.Z]` (this section becomes the
   GitHub Release notes); merge to `main`.
2. On the merge commit: `gh release create <tag> --notes "<changelog section>" --target <sha>`.
   CI reacts to the tag, **asserts `tag == file version`**, and builds/publishes.
3. **Re-open the next `-SNAPSHOT`** in the version file.

Key properties:

- **Independent streams.** The tag globs are disjoint (`v*` vs `epistola-*`), so a
  chart release never triggers the app pipeline and vice-versa — no shared
  `release:` event, no cross-firing.
- **`tag == file` validation** doubles as a guard: a tag whose commit still holds a
  `-SNAPSHOT` fails, so you cannot accidentally release a snapshot.
- **Backports:** keep a `release/<major>.x` branch with its own `-SNAPSHOT`
  lineage + CHANGELOG; tag there to release from it. Consumers resolve by SemVer
  range (a `1.x`-pinned client gets `1.0.5`; latest-tracking stays on `main`).
- **Consumers:** charts publish to `oci://ghcr.io/epistola-app/charts/<chart>`;
  epistola-infra auto-bumps via Flux `ImagePolicy`; clients pull by version.

**Important:** app tags are `vX.Y.Z`; chart tags are `<chart-name>-X.Y.Z`.

---

## SBOM (Software Bill of Materials)

CycloneDX SBOMs are generated for both backend and frontend dependencies and attached to each release.

| Release Artifact                       | Contents                               |
| -------------------------------------- | -------------------------------------- |
| `epistola-backend-{version}-sbom.json` | Backend (Kotlin/Java) dependencies     |
| `epistola-editor-{version}-sbom.json`  | Frontend (TypeScript/npm) dependencies |

For detailed SBOM documentation, see [docs/sbom.md](sbom.md).

---

## Issue Management

### Issue Templates

Located in `.github/ISSUE_TEMPLATE/`:

| Template        | File                  | Purpose                             |
| --------------- | --------------------- | ----------------------------------- |
| Bug Report      | `bug_report.yml`      | Report bugs with reproduction steps |
| Feature Request | `feature_request.yml` | Propose new features                |
| Documentation   | `documentation.yml`   | Report doc issues                   |

**Configuration:** `.github/ISSUE_TEMPLATE/config.yml`

- Blank issues are disabled
- Questions redirect to GitHub Discussions
- Security issues redirect to private vulnerability reporting

### Labels

Labels are defined in `.github/labels.yml` and synced automatically.

#### Issue Type Labels

| Label           | Color  | Description                |
| --------------- | ------ | -------------------------- |
| `bug`           | Red    | Something isn't working    |
| `feature`       | Green  | New feature request        |
| `documentation` | Blue   | Documentation improvements |
| `question`      | Purple | Further information needed |

#### Priority Labels

| Label                | Color        | Description        |
| -------------------- | ------------ | ------------------ |
| `priority: critical` | Dark Red     | Must be fixed ASAP |
| `priority: high`     | Orange       | High priority      |
| `priority: low`      | Light Yellow | Low priority       |

#### Status Labels

| Label                  | Color  | Description                |
| ---------------------- | ------ | -------------------------- |
| `status: blocked`      | Red    | Blocked by external factor |
| `status: in progress`  | Yellow | Currently being worked on  |
| `status: needs review` | Blue   | Needs code review          |
| `status: needs triage` | Gray   | Needs initial assessment   |

#### Component Labels

| Label            | Color  | Description                |
| ---------------- | ------ | -------------------------- |
| `backend`        | Orange | Kotlin/Spring Boot related |
| `frontend`       | Cyan   | TypeScript/Vite editor     |
| `infrastructure` | Gray   | CI/CD, Docker, deployment  |

#### Contributor Labels

| Label              | Color  | Description            |
| ------------------ | ------ | ---------------------- |
| `good first issue` | Purple | Good for newcomers     |
| `help wanted`      | Green  | Extra attention needed |

#### Resolution Labels

| Label             | Color    | Description                 |
| ----------------- | -------- | --------------------------- |
| `duplicate`       | Gray     | Already exists              |
| `invalid`         | Yellow   | Doesn't seem right          |
| `wontfix`         | White    | Will not be addressed       |
| `breaking change` | Dark Red | Introduces breaking changes |

---

## Pull Requests

### PR Template

Located at `.github/PULL_REQUEST_TEMPLATE.md`.

Every PR should include:

- Description of changes
- Related issue(s) (use `Closes #123`)
- Type of change (bug fix, feature, etc.)
- Checklist completion

### PR Workflow

1. **Create PR** from feature branch
2. **CI runs** build and test jobs
3. **Review** by maintainers
4. **Merge** when approved and CI passes
5. **Auto-release** creates version tag and Docker image

### Special Labels

| Label             | Effect                                           |
| ----------------- | ------------------------------------------------ |
| `publish`         | Triggers Docker image build for PR (for testing) |
| `breaking change` | Indicates major version bump needed              |

---

## Security

### Security Policy

Located at `.github/SECURITY.md`.

**Supported versions:** Currently only the latest version.

**Response SLA:** 48 hours for initial acknowledgment.

### Reporting Vulnerabilities

1. Go to repository **Security** tab
2. Click **Report a vulnerability**
3. Fill out the private form

Do NOT create public issues for security vulnerabilities.

### Private Vulnerability Reporting

GitHub's private vulnerability reporting must be enabled in repository settings:

- Settings → Security → Private vulnerability reporting → Enable

### Docker Image Signing

All published Docker images are cryptographically signed using [Cosign](https://docs.sigstore.dev/cosign/overview/) with keyless OIDC signing via GitHub Actions.

#### Verify Image Signature

```bash
cosign verify ghcr.io/epistola-app/epistola-suite:<tag> \
  --certificate-identity-regexp='.*' \
  --certificate-oidc-issuer='https://token.actions.githubusercontent.com'
```

#### Verify SBOM Attestation

The SBOM (CycloneDX format) is attached as a signed attestation to each image:

```bash
cosign verify-attestation ghcr.io/epistola-app/epistola-suite:<tag> \
  --type cyclonedx \
  --certificate-identity-regexp='.*' \
  --certificate-oidc-issuer='https://token.actions.githubusercontent.com'
```

#### Download SBOM from Image

```bash
cosign download attestation ghcr.io/epistola-app/epistola-suite:<tag> \
  | jq -r '.payload' | base64 -d | jq '.predicate'
```

---

## GitHub Discussions

Use Discussions for:

- Questions and help requests
- Ideas and brainstorming
- General conversation
- Show and tell

**Not for:** Bug reports, feature requests, or security issues (use templates).

**Enable in:** Settings → Features → Discussions

---

## GitHub App for Project Sync

The project uses a GitHub App for CI/CD operations that require Projects v2 API access. GitHub's default `GITHUB_TOKEN` and fine-grained PATs don't support Projects v2 operations, making a GitHub App the recommended approach.

### Why a GitHub App?

- **No user account needed**: App acts as its own identity
- **Auto-rotating tokens**: Installation tokens expire in 1 hour (more secure than PATs)
- **Fine-grained permissions**: Only grant exactly what's needed
- **Better audit trail**: Actions show as `github-app[bot]`
- **No seat cost**: Apps don't consume organization seats

### Setup (One-Time)

1. **Create the GitHub App:**
   - Go to https://github.com/organizations/epistola-app/settings/apps/new
   - Configure:
     - **Name**: `epistola-project-sync` (or similar unique name)
     - **Homepage URL**: `https://github.com/epistola-app/epistola-suite`
     - **Webhook**: Uncheck "Active" (not needed)
     - **Permissions**:
       - Organization permissions → **Projects**: Read and write
       - Repository permissions → **Metadata**: Read (required)
     - **Where can this GitHub App be installed?**: Only on this account

2. **Generate credentials:**
   - After creation, note the **App ID** (shown on the app settings page)
   - Scroll to "Private keys" and click **Generate a private key**
   - Download the `.pem` file

3. **Install the app:**
   - Go to the app settings → **Install App**
   - Install on `epistola-app` organization
   - Grant access to `epistola-suite` repository

4. **Add secrets to repository:**
   - Go to repository Settings → Secrets and variables → Actions
   - Add two secrets:

   | Secret Name               | Value                            |
   | ------------------------- | -------------------------------- |
   | `PROJECT_APP_ID`          | The App ID from step 2           |
   | `PROJECT_APP_PRIVATE_KEY` | Full contents of the `.pem` file |

### How It Works

The `project-sync.yml` workflow uses `actions/create-github-app-token` to generate a short-lived installation token:

```yaml
- name: Generate GitHub App token
  uses: actions/create-github-app-token@v1
  with:
    app-id: ${{ secrets.PROJECT_APP_ID }}
    private-key: ${{ secrets.PROJECT_APP_PRIVATE_KEY }}
    owner: epistola-app
```

The token is automatically scoped to the permissions configured in the app and expires after 1 hour.

### Manual Repository Linking

Linking repositories to a GitHub Project requires admin permissions that we intentionally don't grant to the sync workflow. If a repository listed in `.github/project.yml` is not linked, the workflow will fail with instructions.

**To link a repository (requires org admin):**

1. Go to the project settings → **Manage access**
2. Under "Link a repository", search for and add the repository

Or use the GitHub CLI:

```bash
gh project link <PROJECT_NUMBER> --owner epistola-app --repo epistola-suite
```

---

## Repository Settings

### Required Settings

After cloning/forking, ensure these are configured:

#### Features

- [x] Issues
- [x] Discussions
- [x] Projects (optional)

#### Security

- [x] Private vulnerability reporting enabled

#### Branch Protection (main)

Recommended settings:

- [x] Require pull request before merging
- [x] Require status checks to pass (select "Build and Test")
- [x] Require conversation resolution
- [ ] Require signed commits (optional)

#### Actions

- [x] Allow GitHub Actions
- [x] Allow actions created by GitHub

### Secrets

| Secret                    | Purpose                                                                         |
| ------------------------- | ------------------------------------------------------------------------------- |
| `GITHUB_TOKEN`            | Automatically provided by GitHub Actions                                        |
| `PROJECT_APP_ID`          | GitHub App ID for project sync (see [GitHub App](#github-app-for-project-sync)) |
| `PROJECT_APP_PRIVATE_KEY` | GitHub App private key for project sync                                         |

---

## File Reference

| File                                         | Purpose                            |
| -------------------------------------------- | ---------------------------------- |
| `.github/workflows/build.yml`                | CI/CD: build, test, Docker publish |
| `.github/workflows/codeql.yml`               | CodeQL static analysis (SAST)      |
| `.github/workflows/labels.yml`               | Label synchronization              |
| `.github/workflows/project-sync.yml`         | GitHub Project synchronization     |
| `.github/labels.yml`                         | Label definitions                  |
| `.github/project.yml`                        | GitHub Project configuration       |
| `.github/scripts/sync-project.mjs`           | Project sync script                |
| `.github/ISSUE_TEMPLATE/config.yml`          | Issue template configuration       |
| `.github/ISSUE_TEMPLATE/bug_report.yml`      | Bug report form                    |
| `.github/ISSUE_TEMPLATE/feature_request.yml` | Feature request form               |
| `.github/ISSUE_TEMPLATE/documentation.yml`   | Documentation issue form           |
| `.github/PULL_REQUEST_TEMPLATE.md`           | PR template                        |
| `.github/SECURITY.md`                        | Security policy                    |
| `CODE_OF_CONDUCT.md`                         | Community guidelines               |
| `CONTRIBUTING.md`                            | Contribution guide                 |
