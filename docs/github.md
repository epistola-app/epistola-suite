# GitHub Repository Guide

This document explains how GitHub is configured for the Epistola Suite project, covering CI/CD, issue management, releases, and community features.

## Table of Contents

- [CI/CD Workflows](#cicd-workflows)
  - [Build and Test](#build-and-test)
  - [Docker Publishing](#docker-publishing)
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
- Every push to `main`
- Every pull request targeting `main`

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

**Triggers:**
- Automatically on push to `main` (after build succeeds)
- On PRs with the `publish` label (for testing)

**What it does:**

#### On Push to Main
1. Bumps version using semantic versioning based on conventional commits:
   - `feat:` → minor version bump (0.1.0 → 0.2.0)
   - `fix:` → patch version bump (0.1.0 → 0.1.1)
   - `feat!:` or `BREAKING CHANGE` → major version bump (0.1.0 → 1.0.0)
2. Creates a Git tag for the new version
3. Builds Docker image using `gradle :apps:epistola:bootBuildImage`
4. Pushes to GitHub Container Registry with tags:
   - `ghcr.io/epistola-app/epistola-suite:<version>` (e.g., `1.2.3`)
   - `ghcr.io/epistola-app/epistola-suite:<sha>` (git commit SHA)
   - `ghcr.io/epistola-app/epistola-suite:latest`
5. Signs the image using Cosign (keyless OIDC)
6. Attaches SBOM attestation to the image

#### On PR with `publish` Label
1. Builds Docker image
2. Pushes with tag: `ghcr.io/epistola-app/epistola-suite:<prev-version>-pr-<number>-<run>`
   - Example: `0.1.0-pr-42-5`

**Usage:** To test a Docker image from a PR, add the `publish` label.

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

| Field | Type | Options |
|-------|------|---------|
| Status | Single select | Backlog, Ready, In Progress, Blocked, In Review, Done |
| Priority | Single select | P0 Critical, P1 High, P2 Medium, P3 Low |
| Size | Single select | XS, S, M, L, XL |
| Type | Single select | Feature, Bug, Alert, Tech Debt, Chore |
| Target Date | Date | Expected delivery date |
| Started At | Date | When work began (for cycle time) |
| Blocked Reason | Text | Why the item is blocked |

---

## Versioning and Releases

### Semantic Versioning

The project uses [Semantic Versioning](https://semver.org/) (MAJOR.MINOR.PATCH):

| Change Type | Version Bump | Example |
|-------------|--------------|---------|
| Breaking change | MAJOR | 1.0.0 → 2.0.0 |
| New feature | MINOR | 1.0.0 → 1.1.0 |
| Bug fix | PATCH | 1.0.0 → 1.0.1 |

### Conventional Commits

Version bumps are determined automatically from commit messages:

| Commit Prefix | Version Bump |
|---------------|--------------|
| `feat:` | MINOR |
| `fix:` | PATCH |
| `docs:`, `chore:`, `refactor:`, `test:` | PATCH |
| `feat!:` or `fix!:` | MAJOR |
| Footer contains `BREAKING CHANGE:` | MAJOR |

### Release Process

Releases are automated:
1. Merge PR to `main`
2. CI creates a new Git tag based on commit messages
3. Docker image is built and pushed with the new version
4. The `latest` tag is updated
5. GitHub Release is created with:
   - Auto-generated release notes
   - SBOM file attached (`epistola-{version}-sbom.json`)

To create a major release with breaking changes:
```
feat!: redesign authentication API

BREAKING CHANGE: The /auth endpoint now requires OAuth2 tokens instead of API keys.
```

---

## SBOM (Software Bill of Materials)

CycloneDX SBOMs are generated for both backend and frontend dependencies and attached to each release.

| Release Artifact | Contents |
|------------------|----------|
| `epistola-backend-{version}-sbom.json` | Backend (Kotlin/Java) dependencies |
| `epistola-editor-{version}-sbom.json` | Frontend (TypeScript/npm) dependencies |

For detailed SBOM documentation, see [docs/sbom.md](sbom.md).

---

## Issue Management

### Issue Templates

Located in `.github/ISSUE_TEMPLATE/`:

| Template | File | Purpose |
|----------|------|---------|
| Bug Report | `bug_report.yml` | Report bugs with reproduction steps |
| Feature Request | `feature_request.yml` | Propose new features |
| Documentation | `documentation.yml` | Report doc issues |

**Configuration:** `.github/ISSUE_TEMPLATE/config.yml`
- Blank issues are disabled
- Questions redirect to GitHub Discussions
- Security issues redirect to private vulnerability reporting

### Labels

Labels are defined in `.github/labels.yml` and synced automatically.

#### Issue Type Labels
| Label | Color | Description |
|-------|-------|-------------|
| `bug` | Red | Something isn't working |
| `feature` | Green | New feature request |
| `documentation` | Blue | Documentation improvements |
| `question` | Purple | Further information needed |

#### Priority Labels
| Label | Color | Description |
|-------|-------|-------------|
| `priority: critical` | Dark Red | Must be fixed ASAP |
| `priority: high` | Orange | High priority |
| `priority: low` | Light Yellow | Low priority |

#### Status Labels
| Label | Color | Description |
|-------|-------|-------------|
| `status: blocked` | Red | Blocked by external factor |
| `status: in progress` | Yellow | Currently being worked on |
| `status: needs review` | Blue | Needs code review |
| `status: needs triage` | Gray | Needs initial assessment |

#### Component Labels
| Label | Color | Description |
|-------|-------|-------------|
| `backend` | Orange | Kotlin/Spring Boot related |
| `frontend` | Cyan | TypeScript/Vite editor |
| `infrastructure` | Gray | CI/CD, Docker, deployment |

#### Contributor Labels
| Label | Color | Description |
|-------|-------|-------------|
| `good first issue` | Purple | Good for newcomers |
| `help wanted` | Green | Extra attention needed |

#### Resolution Labels
| Label | Color | Description |
|-------|-------|-------------|
| `duplicate` | Gray | Already exists |
| `invalid` | Yellow | Doesn't seem right |
| `wontfix` | White | Will not be addressed |
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

| Label | Effect |
|-------|--------|
| `publish` | Triggers Docker image build for PR (for testing) |
| `breaking change` | Indicates major version bump needed |

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

   | Secret Name | Value |
   |-------------|-------|
   | `PROJECT_APP_ID` | The App ID from step 2 |
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

| Secret | Purpose |
|--------|---------|
| `GITHUB_TOKEN` | Automatically provided by GitHub Actions |
| `PROJECT_APP_ID` | GitHub App ID for project sync (see [GitHub App](#github-app-for-project-sync)) |
| `PROJECT_APP_PRIVATE_KEY` | GitHub App private key for project sync |

---

## File Reference

| File | Purpose |
|------|---------|
| `.github/workflows/build.yml` | CI/CD: build, test, Docker publish |
| `.github/workflows/labels.yml` | Label synchronization |
| `.github/workflows/project-sync.yml` | GitHub Project synchronization |
| `.github/labels.yml` | Label definitions |
| `.github/project.yml` | GitHub Project configuration |
| `.github/scripts/sync-project.mjs` | Project sync script |
| `.github/ISSUE_TEMPLATE/config.yml` | Issue template configuration |
| `.github/ISSUE_TEMPLATE/bug_report.yml` | Bug report form |
| `.github/ISSUE_TEMPLATE/feature_request.yml` | Feature request form |
| `.github/ISSUE_TEMPLATE/documentation.yml` | Documentation issue form |
| `.github/PULL_REQUEST_TEMPLATE.md` | PR template |
| `.github/SECURITY.md` | Security policy |
| `CODE_OF_CONDUCT.md` | Community guidelines |
| `CONTRIBUTING.md` | Contribution guide |
