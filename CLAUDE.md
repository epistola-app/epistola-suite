# Claude Code Instructions for Epistola Suite

## Project Overview

Epistola Suite is a document suite application with:
- **Backend**: Spring Boot 4.0.0 + Kotlin 2.3.0 (JDK 25)
- **Frontend**: Vite + TypeScript editor module (Node.js 24)
- **Architecture**: Multi-module Gradle monorepo

## Project Structure

```
epistola-suite/
├── apps/
│   └── epistola/          # Main Spring Boot application
├── modules/
│   └── editor/            # Vite-based TypeScript editor (embeddable)
├── docs/                  # Documentation
├── scripts/               # Setup scripts
└── build.gradle.kts       # Root build configuration
```

## Build Commands

```bash
# Full build (compiles, tests, checks)
./gradlew build

# Run the application
./gradlew :apps:epistola:bootRun

# Run tests only
./gradlew test

# Check code style
./gradlew ktlintCheck

# Auto-fix code style
./gradlew ktlintFormat

# Build Docker image
./gradlew :apps:epistola:bootBuildImage

# Generate SBOM (Software Bill of Materials)
./gradlew :apps:epistola:generateSbom
```

## Code Style

### Kotlin
- **Linter**: ktlint (enforced in CI)
- Run `./gradlew ktlintFormat` before committing if needed
- EditorConfig is configured for consistent formatting

### TypeScript
- Located in `modules/editor/`
- Follow existing patterns in the codebase

## Commit Conventions

Use [Conventional Commits](https://www.conventionalcommits.org/):

| Prefix | Purpose | Version Bump |
|--------|---------|--------------|
| `feat:` | New feature | MINOR |
| `fix:` | Bug fix | PATCH |
| `docs:` | Documentation | PATCH |
| `chore:` | Maintenance | PATCH |
| `refactor:` | Code restructuring | PATCH |
| `test:` | Test changes | PATCH |
| `ci:` | CI/CD changes | PATCH |

**Breaking changes**: Use `feat!:` or `fix!:` or add `BREAKING CHANGE:` in footer.

**Git hook**: Commit messages are validated by commitlint. Invalid messages will be rejected.

**Commit signing**: SSH commit signing is enabled. Commits will be signed automatically.

**Important**: Never include references to Claude or AI in commit messages.

## Testing

- **Requires Docker** - Tests use Testcontainers
- Backend: JUnit 5 + Testcontainers
- Always run `./gradlew test` before committing
- All PRs must pass CI checks

## Key Files

| File | Purpose |
|------|---------|
| `build.gradle.kts` | Root build configuration |
| `apps/epistola/build.gradle.kts` | Main app build config |
| `modules/editor/package.json` | Editor module config |
| `CONTRIBUTING.md` | Contribution guidelines |
| `docs/github.md` | GitHub workflows documentation |
| `.github/labels.yml` | Issue label definitions |

## When Making Changes

1. **Read existing code first** - Understand patterns before modifying
2. **Run tests** - `./gradlew test` before and after changes
3. **Check style** - `./gradlew ktlintCheck` for Kotlin
4. **Update CHANGELOG.md** - For notable changes under `[Unreleased]`
5. **Small commits** - Commit logical units of work separately

## Don'ts

- Don't skip tests or CI checks
- Don't commit secrets or credentials
- Don't modify `.github/workflows/` without understanding the impact
- Don't change version numbers manually (automated via CI)
