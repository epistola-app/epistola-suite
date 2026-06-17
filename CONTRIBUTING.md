# Contributing to Epistola Suite

Thank you for your interest in contributing to Epistola Suite! This document provides guidelines and information for contributors.

## Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting Started

For development setup instructions, see the [README](README.md). In short:

1. Install [mise](https://mise.jdx.dev/) version manager
2. Run `./scripts/init.sh` to set up tools
3. Build with `gradle build`
4. Run with `gradle :apps:epistola:bootRun`

## How to Contribute

### Reporting Bugs

Found a bug? Please [open an issue](../../issues/new?template=bug_report.yml) with:

- Clear description of the problem
- Steps to reproduce
- Expected vs actual behavior
- Environment details (OS, Java/Node version)

### Suggesting Features

Have an idea? [Open a feature request](../../issues/new?template=feature_request.yml) describing:

- The problem you're trying to solve
- Your proposed solution
- Any alternatives you've considered

### Improving Documentation

Documentation improvements are always welcome! [Open a documentation issue](../../issues/new?template=documentation.yml) or submit a PR directly.

### Submitting Code

1. Fork the repository
2. Create a feature branch (see naming conventions below)
3. Make your changes
4. Ensure tests pass (`gradle test`)
5. Submit a pull request

## Development Workflow

### Branch Naming

Use descriptive branch names with these prefixes:

| Prefix      | Purpose               |
| ----------- | --------------------- |
| `feat/`     | New features          |
| `fix/`      | Bug fixes             |
| `docs/`     | Documentation changes |
| `chore/`    | Maintenance tasks     |
| `refactor/` | Code refactoring      |

**Examples:**

- `feat/add-pdf-export`
- `fix/login-redirect-bug`
- `docs/update-api-reference`

### Commit Conventions

We use [Conventional Commits](https://www.conventionalcommits.org/). This enables automatic semantic versioning.

> **Note:** Commit messages are validated by a Git hook (commitlint). If your commit is rejected, check the error message and adjust your commit message format.

> **Note:** A `pre-commit` hook also runs [gitleaks](https://github.com/gitleaks/gitleaks) over your staged changes to block accidentally committed secrets (run `mise install` once so the pinned gitleaks is available). Real secrets belong in environment variables / Kubernetes Secrets — see [`docs/encryption.md`](docs/encryption.md).

**Format:** `<type>: <description>`

| Type       | Description                             |
| ---------- | --------------------------------------- |
| `feat`     | New feature                             |
| `fix`      | Bug fix                                 |
| `docs`     | Documentation only                      |
| `chore`    | Maintenance, dependencies               |
| `refactor` | Code change that neither fixes nor adds |
| `test`     | Adding or updating tests                |

**Examples:**

```
feat: add PDF export functionality
fix: resolve login redirect loop
docs: update installation instructions
```

**Breaking Changes:**

- Add `!` after type: `feat!: remove deprecated API`
- Or add `BREAKING CHANGE:` in the commit footer

### Commit Signing

Commits should be signed using SSH keys. The `./scripts/init.sh` script configures this automatically.

To verify signing is enabled:

```bash
git config --get commit.gpgsign  # Should return "true"
```

### Changelog Entries

The root `CHANGELOG.md` is read by two audiences: developers in the repo and end users in the product's in-app **Changelog** dialog, which parses and filters entries. New entries under `[Unreleased]` use a **commit-style format** — no `### Added/Changed/Fixed` headers (the type carries that):

```markdown
- **[dev]** fix(logs): **Recursion guard narrowed.** Scoped to the ApplicationLogIngestor logger.
- **[user]** feat(editor): **List nesting via Tab.** You can now author sub-lists.
- chore(build): **Bump the Kotlin daemon heap.**
```

Each entry is `- [**[audience]** ]type(scope): **Title.** …`:

- **`type(scope)` is required.** `type` is a Conventional-Commit type (`feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`) — the same vocabulary as commit messages. `scope` is a required lowercase kebab-case area (e.g. `editor`, `generation`, `cluster`, `locale`, `logs`). A change spanning areas may list comma-separated scopes — `feat(editor,pdf):` — and matches either in the Scope filter.
- **The audience badge is optional and comes first:** `**[user]**` for user-facing changes, `**[dev]**` for developer-internal ones (refactors, test infra, scheduler internals). Omit it for changes relevant to everyone.

The markers are stripped before rendering and surface as chips. The dialog filters by **Audience** (Users / Developers / All — defaults to Users, hiding `**[dev]**`), **Type** (the commit types), and **Scope**, and previews the `[Unreleased]` section at the top as an **Upcoming** entry (excluded from the dashboard's "What's New" badge). Older released sections still use legacy Keep-a-Changelog `### ` headers (grandfathered — type mapped from the section, no scope); don't add `### ` headers to new entries. When a version is cut, the `release` skill adds a short prose **release summary** under the `## [x.y.z]` heading (before the entries), which the dialog shows above that version's entries. These conventions apply only to the root `CHANGELOG.md` — the Helm chart changelog (`charts/epistola/CHANGELOG.md`) is not shown in the UI.

**Important:** Your SSH key must be added to GitHub as a **signing key** (not just authentication):

1. Go to GitHub → Settings → SSH and GPG keys
2. Click "New SSH key"
3. Select **"Signing Key"** as the key type
4. Paste your public key

### Code Style

#### Kotlin (Backend)

- We use [ktlint](https://pinterest.github.io/ktlint/) for code formatting
- Run `gradle ktlintCheck` to check
- Run `gradle ktlintFormat` to auto-fix
- EditorConfig is configured for consistent formatting

#### Thymeleaf + HTMX (Frontend)

- Server-side rendered templates in `apps/epistola/src/main/resources/templates/`
- Use HTMX attributes for dynamic interactions
- Follow existing patterns in the codebase

#### TypeScript (Client Components)

- For rich interactive components like the editor in `modules/editor/`
- EditorConfig ensures consistent indentation

### Testing Requirements

- All PRs must pass CI checks
- New features should include tests
- **Backend:** JUnit 5 with Testcontainers (requires Docker)
- **Frontend:** Add tests for new components/utilities

Run tests locally:

```bash
gradle test
```

## Pull Request Process

1. **Create your PR** with a clear description
2. **Link related issues** using keywords (e.g., "Closes #123")
3. **Ensure CI passes** - all checks must be green
4. **Wait for review** - maintainers will review your PR
5. **Address feedback** - make requested changes
6. **Get merged!** - once approved, your PR will be merged

### What to Expect

- Initial response within a few days
- Constructive feedback focused on code quality
- Possible requests for changes or clarification
- Appreciation for your contribution!

## Issue Labels

Labels are automatically managed via [`.github/labels.yml`](.github/labels.yml). Key labels include:

| Label                         | Description                |
| ----------------------------- | -------------------------- |
| `bug`                         | Something isn't working    |
| `feature`                     | New feature request        |
| `documentation`               | Documentation improvements |
| `good first issue`            | Good for newcomers         |
| `help wanted`                 | Extra attention needed     |
| `backend`                     | Kotlin/Spring Boot related |
| `frontend`                    | Thymeleaf/HTMX/UI related  |
| `priority: critical/high/low` | Issue priority             |
| `status: blocked/in progress` | Current status             |

See the [labels config](.github/labels.yml) for the complete list.

## Questions?

For questions and discussions, please use [GitHub Discussions](../../discussions) rather than opening an issue.

## License

By contributing, you agree that your contributions will be licensed under the same license as the project.

---

Thank you for contributing to Epistola Suite!
