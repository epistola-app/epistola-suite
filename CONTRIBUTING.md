# Contributing to Epistola Suite

Thank you for your interest in contributing to Epistola Suite! This document provides guidelines and information for contributors.

## Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting Started

For development setup instructions, see the [README](README.md). In short:

1. Install [asdf](https://asdf-vm.com/) version manager
2. Run `./scripts/init.sh` to set up tools
3. Build with `./gradlew build`
4. Run with `./gradlew :apps:epistola:bootRun`

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
4. Ensure tests pass (`./gradlew test`)
5. Submit a pull request

## Development Workflow

### Branch Naming

Use descriptive branch names with these prefixes:

| Prefix | Purpose |
|--------|---------|
| `feat/` | New features |
| `fix/` | Bug fixes |
| `docs/` | Documentation changes |
| `chore/` | Maintenance tasks |
| `refactor/` | Code refactoring |

**Examples:**
- `feat/add-pdf-export`
- `fix/login-redirect-bug`
- `docs/update-api-reference`

### Commit Conventions

We use [Conventional Commits](https://www.conventionalcommits.org/). This enables automatic semantic versioning.

> **Note:** Commit messages are validated by a Git hook (commitlint). If your commit is rejected, check the error message and adjust your commit message format.

**Format:** `<type>: <description>`

| Type | Description |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `chore` | Maintenance, dependencies |
| `refactor` | Code change that neither fixes nor adds |
| `test` | Adding or updating tests |

**Examples:**
```
feat: add PDF export functionality
fix: resolve login redirect loop
docs: update installation instructions
```

**Breaking Changes:**
- Add `!` after type: `feat!: remove deprecated API`
- Or add `BREAKING CHANGE:` in the commit footer

### Code Style

#### Kotlin (Backend)
- We use [ktlint](https://pinterest.github.io/ktlint/) for code formatting
- Run `./gradlew ktlintCheck` to check
- Run `./gradlew ktlintFormat` to auto-fix
- EditorConfig is configured for consistent formatting

#### TypeScript (Frontend)
- Follow existing patterns in the `modules/editor` directory
- EditorConfig ensures consistent indentation

### Testing Requirements

- All PRs must pass CI checks
- New features should include tests
- **Backend:** JUnit 5 with Testcontainers (requires Docker)
- **Frontend:** Add tests for new components/utilities

Run tests locally:
```bash
./gradlew test
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

| Label | Description |
|-------|-------------|
| `bug` | Something isn't working |
| `feature` | New feature request |
| `documentation` | Documentation improvements |
| `good first issue` | Good for newcomers |
| `help wanted` | Extra attention needed |
| `backend` | Kotlin/Spring Boot related |
| `frontend` | TypeScript/Vite related |
| `priority: critical/high/low` | Issue priority |
| `status: blocked/in progress` | Current status |

See the [labels config](.github/labels.yml) for the complete list.

## Questions?

For questions and discussions, please use [GitHub Discussions](../../discussions) rather than opening an issue.

## License

By contributing, you agree that your contributions will be licensed under the same license as the project.

---

Thank you for contributing to Epistola Suite!
