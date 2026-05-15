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
│   ├── epistola-catalog/  # Catalog exchange (import/export templates)
│   ├── generation/        # Pure PDF rendering
│   ├── rest-api/          # OpenAPI specs
│   ├── editor/            # Lit + ProseMirror editors (template, theme, data contract)
│   ├── epistola-mcp/      # MCP server for AI assistants (read-only tools at /api/mcp)
│   └── testing/           # Shared test infrastructure (IntegrationTestBase, fixtures, Testcontainers)
├── docs/                  # Documentation
├── scripts/               # Setup scripts
└── build.gradle.kts       # Root build configuration
```

### Module Responsibilities

- **apps/epistola**: UI layer only (Thymeleaf, HTMX, routes, handlers)
- **modules/epistola-core**: All business logic (domains, commands, queries, REST API, JDBI config)
- **modules/epistola-catalog**: Catalog exchange — importing templates from remote catalogs (independent of core)
- **modules/generation**: Pure PDF rendering (no business logic)
- **modules/rest-api**: OpenAPI specifications
- **modules/editor**: Lit + ProseMirror editors — template editor, theme editor, data contract editor (web components, no React)
- **modules/epistola-mcp**: Model Context Protocol server for AI assistants. Mounts a Streamable HTTP endpoint at `/api/mcp` (under the existing `/api/**` security chain — per-tenant `X-API-Key` auth). Tools dispatch through the existing `SpringMediator` to existing queries; the module owns no domain logic. MVP is read-only (template/theme/stencil/contract discovery + document preview). See [`docs/mcp.md`](docs/mcp.md).
- **modules/testing**: Shared test infrastructure — `IntegrationTestBase`, Testcontainers, fixture/scenario DSLs (not production code)

## Frontend Architecture

The frontend uses a **server-side rendering** approach:

- **Thymeleaf**: Template engine for rendering HTML on the server
- **HTMX**: For dynamic interactions without full page reloads
- **Client components**: Embedded modules (like the editor) for features requiring rich client-side interactivity

### Content Security Policy

A strict CSP is enforced on all UI responses (`SecurityConfig.kt`). Key restrictions:

- **`script-src 'self' 'unsafe-inline'`** — inline `<script>` tags are allowed, but `eval()` is NOT (`unsafe-eval` is absent)
- **Do NOT use `hx-on::*` or `hx-on-*` attributes** — these HTMX event handler attributes use `eval()` internally, which the CSP blocks. Use inline `<script>` tags with `addEventListener` instead:

  ```html
  <!-- BAD: blocked by CSP -->
  <form hx-on::after-request="doSomething()">
    <!-- GOOD: use addEventListener -->
    <form id="my-form">
      <script>
        document.getElementById("my-form").addEventListener("htmx:afterRequest", function (e) {
          if (e.detail.successful) doSomething();
        });
      </script>
    </form>
  </form>
  ```

- **Thymeleaf also mangles `hx-on::*`** — the `::` is interpreted as a fragment expression separator and stripped during template processing. This is a second reason to avoid these attributes.

### Editor component registrations

Every editor component registration (`ComponentDefinition` in `modules/editor/src/main/typescript/engine/registry.ts`) must include at least one entry in `examples[]`. Backend tools (the MCP server's `list_component_types` / `get_component_type`) and design docs surface these as canonical usage. Each example is a self-contained `{ rootNodeId, nodes, slots }` `TemplateDocument` fragment showing one realistic way the component is used. Treat missing examples as a PR blocker.

## Backend Architecture: UI Handlers vs REST API

The backend has **two distinct endpoint layers** that must NEVER be mixed:

### 1. REST API (External Systems Only)

- **Path pattern**: `/api/tenants/*`, `/api/tenants/{tenantId}/templates/*`, etc. (all under `/api/` prefix)
- **Authentication**: API key (`X-API-Key` header) or OAuth2 JWT Bearer token. Stateless, no CSRF.
- **Implementation**: `@RestController` with `@RequestMapping("/api")` in `app.epistola.suite.api.v1` package
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

### Formatting (All Files)

- **Formatter**: oxfmt via `pnpm format` (enforced in CI)
- **Always run `pnpm format`** before committing to auto-fix formatting across all file types (JSON, TypeScript, Markdown, CSS, etc.)
- **Always run `pnpm format:check`** after committing to verify — this includes Markdown files, so documentation-only changes need formatting too
- **Run `pnpm format:check`** to verify without modifying files

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

| Prefix      | Purpose            | Version Bump |
| ----------- | ------------------ | ------------ |
| `feat:`     | New feature        | MINOR        |
| `fix:`      | Bug fix            | PATCH        |
| `docs:`     | Documentation      | PATCH        |
| `chore:`    | Maintenance        | PATCH        |
| `refactor:` | Code restructuring | PATCH        |
| `test:`     | Test changes       | PATCH        |
| `ci:`       | CI/CD changes      | PATCH        |

**Breaking changes**: Use `feat!:` or `fix!:` or add `BREAKING CHANGE:` in footer.

**Git hook**: Commit messages are validated by commitlint. Invalid messages will be rejected.

**Commit signing**: SSH commit signing is enabled. Commits will be signed automatically.

**Important**: Never include references to Claude or AI in commit messages.

## JSON Handling

- **Jackson 3** (`tools.jackson.*`) is used for JSON serialization
- JDBI uses `jdbi3-jackson3` plugin for JSONB column mapping
- Import from `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson`

## Testing

- **Requires Docker** - Integration and UI tests use Testcontainers
- Backend: JUnit 5 + Testcontainers
- Always run `./gradlew unitTest integrationTest` before committing
- All PRs must pass CI checks
- See `docs/testing.md` for the full testing guide

### Shared Testing Module

Shared test infrastructure lives in `modules/testing/` (not duplicated across modules):

- **`IntegrationTestBase`** — base class for all integration tests. Provides mediator context, test user, fixture/scenario DSLs, `createTenant()`. Boots `TestApplication` by default.
- **`BaseIntegrationTest`** (in `apps/epistola`) — extends `IntegrationTestBase`, overrides with `EpistolaSuiteApplication` for app-level tests.
- **`BasePlaywrightTest`** (in `apps/epistola`) — extends `BaseIntegrationTest` with Playwright browser lifecycle for UI tests.

To add tests to a new module: `testImplementation(project(":modules:testing"))` and extend `IntegrationTestBase`.

### When to Run Which Tests

| Change type                           | Run                                            |
| ------------------------------------- | ---------------------------------------------- |
| Pure logic, algorithms, utilities     | `./gradlew unitTest`                           |
| Business logic, commands, queries, DB | `./gradlew integrationTest`                    |
| Thymeleaf templates, HTMX handlers    | `./gradlew integrationTest`                    |
| UI interaction, JavaScript behavior   | `./gradlew uiTest`                             |
| Before committing                     | `./gradlew unitTest integrationTest` (minimum) |
| Before creating a PR                  | `./gradlew test` (all)                         |

## Key Files

| File                             | Purpose                        |
| -------------------------------- | ------------------------------ |
| `build.gradle.kts`               | Root build configuration       |
| `apps/epistola/build.gradle.kts` | Main app build config          |
| `modules/editor/package.json`    | Editor module config           |
| `CONTRIBUTING.md`                | Contribution guidelines        |
| `docs/github.md`                 | GitHub workflows documentation |
| `.github/labels.yml`             | Issue label definitions        |

## When Making Changes

1. **Read existing code first** - Understand patterns before modifying
2. **Run tests** - `./gradlew test` before and after changes
3. **Format all files** - `pnpm format` before committing (covers JSON, TypeScript, Markdown, CSS, etc. — includes documentation-only changes)
4. **Format Kotlin** - `./gradlew ktlintFormat` after making Kotlin changes
5. **Check style** - `./gradlew ktlintCheck` before committing (must pass)
6. **Update CHANGELOG.md** - For notable changes under `[Unreleased]`. Helm chart changes go in `charts/epistola/CHANGELOG.md`; all other changes go in the root `CHANGELOG.md`.
7. **Update documentation** - Check if changes require updates to docs in `docs/`, KDoc comments, or CLAUDE.md. Search for references to changed conventions, APIs, or patterns.
8. **Small commits** - Commit logical units of work separately
9. **Bump demo catalog version** - When modifying demo templates/stencils/themes in `modules/epistola-core/src/main/resources/epistola/catalogs/demo/`, bump `release.version` in `catalog.json` and update the resource's `updatedAt`. The demo loader only reimports when the version changes.
10. **Consider catalog impact** - Whenever you add, modify, or remove a resource (template, stencil, theme, data contract, etc.), consider whether the change affects catalog exchange in `modules/epistola-catalog/`. Check if catalog import/export, serialization formats, manifest schemas, or version handling need updating to stay consistent with the resource change.
11. **Consider all surfaces** - When adding, changing, or removing a feature, evaluate the impact on all three surfaces the suite exposes: the **web UI** (Thymeleaf + HTMX handlers in `apps/epistola`), the **REST API** (`modules/epistola-core/api` + OpenAPI spec in `modules/rest-api`), and the **MCP server** (`modules/epistola-mcp`). A capability change usually needs to be reflected in all three (or an explicit decision to scope it to a subset). Don't ship a feature on one surface and silently drift the others.
12. **Keep components, registry, and demo catalog in sync** - When adding, changing, or removing an editor component, update both the component registry (`modules/editor/src/main/typescript/engine/registry.ts` — including `examples[]`) and the demo catalog (`modules/epistola-core/src/main/resources/epistola/catalogs/demo/`). The demo catalog is our kitchen sink: every feature should be exercised there in every reasonable way (variants, options, edge cases). New capability ⇒ new demo usage; changed signature ⇒ updated demo usage; removed component ⇒ removed demo usage (and bumped catalog version per item 9).
13. **Every feature MUST be demonstrated in the demo catalog** - This is a hard requirement and a PR blocker, broader than item 12 (which is component-specific). No feature is complete until it is exercised in the demo catalog (`modules/epistola-core/src/main/resources/epistola/catalogs/demo/`). Any user-facing capability — a rendering feature, a generation option, an editor behavior, a new template/theme/stencil/data-contract capability — must ship with a concrete demo resource (new, or an update to an existing one) that uses it realistically, including reasonable variants and edge cases. If a feature genuinely cannot be represented in the demo catalog, the PR must state explicitly why. Bump the catalog version per item 9 whenever you touch demo resources.

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
