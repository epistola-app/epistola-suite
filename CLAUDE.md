
# Claude Code Instructions for Epistola Suite

## Project Overview

Epistola Suite is a document suite application with:
- **Backend**: Spring Boot 4.0.0 + Kotlin 2.3.0 (JDK 25)
- **Frontend**: Server-side rendered using Thymeleaf + HTMX
- **Client Components**: Vite + TypeScript editor module (Node.js 24) for rich editing
- **Architecture**: Multi-module Gradle monorepo

## Production Status

**This project is NOT yet in production.** Breaking changes to database schema, APIs, and architecture are acceptable without migration paths. When the project goes to production, this section will be updated.

YOU DO NOT HAVE TO BUILD ANYTHING BACKWARDS COMPATIBLE AT THIS TIME.

## Project Structure

```
epistola-suite-modules/
├── apps/
│   └── epistola/          # Spring Boot app (UI layer: Thymeleaf + HTMX)
│       ├── handlers/      # UI request handlers
│       ├── config/        # Thymeleaf, Security, UI config
│       ├── htmx/          # HTMX utilities
│       ├── demo/          # DemoLoader
│       └── resources/
│           ├── db/migration/        # Flyway migrations
│           ├── templates/           # Thymeleaf templates
│           └── application.yml
├── modules/
│   ├── epistola-core/     # Business logic (NEW)
│   │   ├── tenants/       # Tenant domain
│   │   ├── themes/        # Theme domain
│   │   ├── templates/     # Template domain
│   │   ├── documents/     # Document generation domain
│   │   ├── environments/  # Environment domain
│   │   ├── mediator/      # CQRS mediator pattern
│   │   ├── common/        # Shared utilities (IDs, UUIDv7)
│   │   ├── validation/    # JSON schema validation
│   │   ├── generation/    # GenerationService (orchestration)
│   │   ├── metadata/      # App metadata service
│   │   ├── config/        # JDBI, Jackson config
│   │   └── api/           # REST API controllers
│   ├── generation/        # Pure PDF rendering
│   ├── template-model/    # Template data structures
│   ├── rest-api/          # OpenAPI specs
│   ├── editor/            # Lit + ProseMirror template editor (web components)
│   └── schema-manager/    # Schema tools (React, bundled)
├── docs/                  # Documentation
├── scripts/               # Setup scripts
└── build.gradle.kts       # Root build configuration
```

### Module Responsibilities

- **apps/epistola**: UI layer only (Thymeleaf, HTMX, routes, handlers)
- **modules/epistola-core**: All business logic (domains, commands, queries, REST API, JDBI config)
- **modules/generation**: Pure PDF rendering (no business logic)
- **modules/template-model**: Data structures for templates
- **modules/rest-api**: OpenAPI specifications
- **modules/editor**: Lit + ProseMirror template editor (web components, no React)
- **modules/schema-manager**: JSON schema editing UI (React, self-contained bundle)

## Frontend Architecture

The frontend uses a **server-side rendering** approach:
- **Thymeleaf**: Template engine for rendering HTML on the server
- **HTMX**: For dynamic interactions without full page reloads
- **Client components**: Embedded modules (like the editor) for features requiring rich client-side interactivity

## Backend Architecture: UI Handlers vs REST API

The backend has **two distinct endpoint layers** that must NEVER be mixed:

### 1. REST API (External Systems Only)
- **Path pattern**: `/api/v1/*` or `/v1/*`
- **Implementation**: `@RestController` in `app.epistola.suite.api.v1` package
- **Returns**: JSON DTOs (`application/vnd.epistola.v1+json`)
- **OpenAPI spec**: `/modules/rest-api/src/main/resources/openapi/`
- **Purpose**: External system integration (stable, versioned API)

### 2. UI Handlers (Internal Use Only)
- **Path pattern**: `/tenants/*`, `/themes/*`, etc. (NO `/api` or `/v1` prefix)
- **Implementation**: `@Component` with functional routing
- **Returns**: Thymeleaf templates, HTMX fragments, or minimal JSON (`application/json`)
- **Purpose**: Server-side rendered UI needs (can change freely)

### CRITICAL RULE
**UI code (Thymeleaf/JavaScript/TypeScript) MUST NEVER call REST API endpoints.**

Always create a UI handler endpoint for UI needs. The REST API is only for external systems.

### Verification
```bash
./gradlew test --tests UiRestApiSeparationTest
```

## Build Commands

**Prerequisite**: [mise](https://mise.jdx.dev/) must be installed. All tool versions (Java, Gradle, Node, pnpm) are managed via `.mise.toml`.

```bash
# Install mise-managed tools (first time or after .mise.toml changes)
mise install

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

# Test profiles
./gradlew unitTest          # Fast unit tests only (no Docker needed)
./gradlew integrationTest   # Spring + DB tests (Docker required)
./gradlew uiTest            # Playwright browser tests (Docker required)

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
- Always run `gradle test` before committing
- All PRs must pass CI checks

### When to Run Which Tests

| Change type | Run |
|-------------|-----|
| Pure logic, algorithms, utilities | `./gradlew unitTest` |
| Business logic, commands, queries, DB | `./gradlew integrationTest` |
| Thymeleaf templates, HTMX handlers | `./gradlew integrationTest` |
| UI interaction, JavaScript behavior | `./gradlew uiTest` |
| Before committing | `./gradlew unitTest integrationTest` (minimum) |
| Before creating a PR | `./gradlew test` (all) |

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

## GitHub Integration (MCP)

This project uses a GitHub MCP server for AI-assisted issue and project management. When the MCP server is configured, you have access to GitHub tools for:

- **Issues**: Create, update, list, and search issues
- **Pull Requests**: Create, list, review, and manage PRs
- **Projects**: Manage GitHub Projects for backlog tracking

### Using GitHub MCP Tools

When working with the backlog or issues:
- Use the GitHub MCP tools to create issues for new features or bugs
- Reference issues in commits when fixing bugs (e.g., "fix: resolve login issue #123")
- Check existing issues before creating duplicates

### Setup

If the GitHub MCP server is not configured, run:
```bash
pnpm run setup:github-mcp
```

This will guide you through creating a fine-grained PAT with minimal permissions and store it securely in your OS credential manager.
