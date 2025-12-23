<!-- OPENSPEC:START -->
# OpenSpec Instructions

These instructions are for AI assistants working in this project.

Always open `@/openspec/AGENTS.md` when the request:
- Mentions planning or proposals (words like proposal, spec, change, plan)
- Introduces new capabilities, breaking changes, architecture shifts, or big performance/security work
- Sounds ambiguous and you need the authoritative spec before coding

Use `@/openspec/AGENTS.md` to learn:
- How to create and apply change proposals
- Spec format and conventions
- Project structure and guidelines

Keep this managed block so 'openspec update' can refresh the instructions.

<!-- OPENSPEC:END -->

# Claude Code Instructions for Epistola Suite

## Project Overview

Epistola Suite is a document suite application with:
- **Backend**: Spring Boot 4.0.0 + Kotlin 2.3.0 (JDK 25)
- **Frontend**: Server-side rendered using Thymeleaf + HTMX
- **Client Components**: Vite + TypeScript editor module (Node.js 24) for rich editing
- **Architecture**: Multi-module Gradle monorepo

## Production Status

**This project is NOT yet in production.** Breaking changes to database schema, APIs, and architecture are acceptable without migration paths. When the project goes to production, this section will be updated.

## Project Structure

```
epistola-suite/
├── apps/
│   └── epistola/          # Main Spring Boot application (Thymeleaf + HTMX views)
├── modules/
│   └── editor/            # Rich text editor component (Vite + TypeScript)
├── docs/                  # Documentation
├── scripts/               # Setup scripts
└── build.gradle.kts       # Root build configuration
```

## Frontend Architecture

The frontend uses a **server-side rendering** approach:
- **Thymeleaf**: Template engine for rendering HTML on the server
- **HTMX**: For dynamic interactions without full page reloads
- **Client components**: Embedded modules (like the editor) for features requiring rich client-side interactivity

## Build Commands

The build is split into two phases: **frontend (pnpm)** and **backend (Gradle)**.

```bash
# Frontend build (must run first)
pnpm install
pnpm build

# Backend build (requires frontend built first)
./gradlew build

# Combined build
pnpm install && pnpm build && ./gradlew build

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
pnpm --filter @epistola/editor sbom
```

### Development Workflow

For live frontend development with hot reload:

```bash
# Terminal 1: Spring Boot with local profile (serves from filesystem)
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local'

# Terminal 2: Watch mode (rebuilds dist/ on file changes)
pnpm --filter @epistola/editor watch
```

## Code Style

### Kotlin
- **Linter**: ktlint (enforced in CI)
- **Always run `./gradlew ktlintFormat`** after making Kotlin changes to auto-fix formatting
- **Always run `./gradlew ktlintCheck`** before committing to verify code style
- EditorConfig is configured for consistent formatting

### TypeScript (Client Components)
- Located in `modules/editor/` and other client-side modules
- Used only for rich interactive components that require client-side logic
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

## JSON Handling

- **Jackson 3** (`tools.jackson.*`) is used for JSON serialization
- JDBI uses `jdbi3-jackson3` plugin for JSONB column mapping
- Import from `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson`

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
3. **Format code** - `./gradlew ktlintFormat` after making Kotlin changes
4. **Check style** - `./gradlew ktlintCheck` before committing (must pass)
5. **Update CHANGELOG.md** - For notable changes under `[Unreleased]`
6. **Small commits** - Commit logical units of work separately

## Don'ts

- Don't skip tests or CI checks
- Don't commit secrets or credentials
- Don't modify `.github/workflows/` without understanding the impact
- Don't change version numbers manually (automated via CI)
