# Claude Code Instructions for Epistola Suite

## Project Overview

Epistola Suite is a document suite application with:

- **Backend**: Spring Boot 4.0.0 + Kotlin 2.3.0 (JDK 25)
- **Frontend**: Server-side rendered using Thymeleaf + HTMX
- **Client Components**: Vite + TypeScript editor module (Node.js 24) for rich editing
- **Architecture**: Multi-module Gradle monorepo

## Release Status

**Epistola Suite 1.0.0-RC1 is released — the first stable release (25 June 2026). 1.0.0-GA is planned for July 2026.**

**The database is now stable and is NO LONGER reset between versions.** Every schema change MUST ship as a forward Flyway migration that preserves existing data. Do **not** write destructive migrations that drop or reset user data, and do **not** rewrite or "fold back" a released migration — add a new timestamped one (see [`docs/migrations.md`](docs/migrations.md)). The RC1 consolidation was the **last** history-rewriting clear; there are no more.

While in the release-candidate phase, the REST APIs, catalog wire formats, configuration, and internal architecture MAY still change before 1.0.0-GA — but such changes must be **deliberate and explicitly flagged as breaking** (`feat!:` / `fix!:` / `BREAKING CHANGE:`), never casual. After 1.0.0-GA these become stable too. Data stability is not negotiable at any point from RC1 onward.

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
│   │   ├── quality/       # Quality-checks findings ledger + source SPI
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
- **modules/epistola-support**: Optional commercial-tier infrastructure that talks to the separate **epistola-hub** server. Owns the hub client wiring (registration loop, credentials persistence) and the `epistola.support.*` properties. Off by default (`epistola.support.enabled=false`) — OSS deployments ship the JAR but never construct any beans. Required-when-enabled installation identity properties live under `epistola.installation.*`. Commercial features (feedback sync, monitoring, quality checks, version compatibility) arrive as **per-feature modules** that depend on this one (`epistola-support-feedback`, `epistola-support-quality`, …).
- **modules/epistola-support-feedback**: The complete feedback feature — domain (model + commands/queries + migrations + static JS), the sync engine (`FeedbackSyncPort` + drivers + no-op fallback), the UI (handlers + `templates/feedback/**`), and the `HubFeedbackSyncAdapter`. The feature is freely usable; only the hub **sync** (the paid server component) is gated on `epistola.support.enabled` (no-op adapter keeps feedback local otherwise). UI visibility is gated by the `support-feedback` feature toggle.
- **modules/epistola-support-snapshots**: The **catalog-export snapshot-sync layer**, now used by the **Upgrading** feature (the Backups feature moved to faithful local backups — see below). Building/restoring a tenant snapshot is an `epistola-core` primitive (`catalog/snapshot/` — `BuildTenantSnapshot` / `RestoreTenantSnapshot`); this module owns moving it to/from the hub: `SnapshotSyncPort` + `HubSnapshotSyncAdapter` (client-streaming upload / server-streaming download over the hub `CatalogSyncService`) + a no-op fallback, the `TenantSnapshotSyncService` (build → fingerprint-dedup → upload, plus the `app_metadata` **last-sync timestamp** that lets the two features coordinate), and the background `snapshotSystemPrincipal`. The hub calls are gated on `epistola.support.enabled`; the snapshot build/restore are not. Backups and Upgrading depend on this module, not on each other.
- **modules/epistola-support-backups**: The **Backups feature** — daily faithful, full-fidelity tenant backups (the module's own `app.epistola.suite.tenantbackup` primitive: full version history, exact version numbers, merge-not-cascade restore, gated to the same schema version), stored **locally** in the `tenant_backups` table via the `TenantBackupStore` port and orchestrated by `TenantBackupService` (build → fingerprint-dedup → retain N). The daily `BackupScheduler` (per tenant with `support-backups` available; a native `single_owner` scheduled task active when `epistola.support.backups.scheduled.enabled=true`) and the Backups UI (list / back up now / restore-with-confirmation, `templates/backups/**`). It no longer rides `TenantSnapshotSyncService` (that stays with Upgrading), so the two features toggle independently. UI visibility is gated by the `support-backups` feature toggle. See [`docs/tenant-backup.md`](docs/tenant-backup.md).
- **modules/epistola-support-upgrading**: The **Upgrading (compatibility) feature** — a read-only `CompatibilitySyncPort` + `HubCompatibilitySyncAdapter` that fetch the company-side compatibility-check results live, the Upgrading UI (`templates/upgrading/**`), and its **own** native `single_owner` `UpgradingSnapshotScheduler` that tops up snapshot freshness: it makes a snapshot only when none was synced (by Backups _or_ itself) within `epistola.support.upgrading.snapshot.max-age` (default 24h), reading the shared last-sync timestamp. UI visibility is gated by the `support-compatibility-check` feature toggle (the module/UI is still named "upgrading" pending a follow-up rename).
- **modules/epistola-web**: Shared web/UI toolkit — the HTMX functional-web DSL (`app.epistola.suite.htmx`) used by the host app and any feature module that contributes UI.
- **modules/testing**: Shared test infrastructure — `IntegrationTestBase`, Testcontainers, fixture/scenario DSLs (not production code)

### Database migrations are module-owned

Flyway migrations live in the module that owns the tables, under
`<module>/src/main/resources/db/migration/<module>/`, named
`VYYYYMMDDHHMMSS__<module>_<desc>.sql` (timestamp versions, generate with
`date -u +V%Y%m%d%H%M%S`). All module migrations merge onto **one global Flyway
namespace** at app runtime, so versions must be globally unique and ordered. A
non-core migration that FKs to (or uses a `DOMAIN` from) a core table must
timestamp **after** the core migration it depends on. Never edit a merged
migration — add a new timestamped file. Folding `ALTER`s back into the original
`CREATE` is a deliberate consolidation, verified byte-identical with
`pg_dump --schema-only` before merge. See [`docs/migrations.md`](docs/migrations.md).

### Commercial-tier architecture (forward direction)

- **`epistola-support`** owns commercial-tier infrastructure (hub client, registration, credentials). Per-feature modules layer on top.
- **`epistola-web`** is the shared web/UI toolkit (the HTMX functional-web DSL — `htmx{}`, `page()`, `form{}`, request extensions — in package `app.epistola.suite.htmx`). Both `apps/epistola` and any per-feature UI module depend on it, so feature modules can host handlers/templates without depending on the app. The host app still owns the page chrome (`layout/shell`, `layout/nav`, `fragments/*`).
- Per-feature modules MAY ship **UI** (Thymeleaf templates + `@Component` handlers in their own `src/main/resources/templates/...`). Spring/Thymeleaf merges classpath templates from every JAR; CSP and security wiring apply automatically. `apps/epistola` keeps being the host that composes UI from contributing modules — relax the "UI only in `apps/epistola`" rule when a feature module needs to contribute UI. **First example:** `epistola-support-feedback` ships the whole feedback feature including its UI (handlers + `templates/feedback/**`); the feature is freely usable and only the hub _sync_ (the paid server component) is gated on `epistola.support.enabled`.
- **Extension points** use small SPIs, introduced one-at-a-time as features need them; the host collects all `@Component` contributions and composes them. Two exist, both in `epistola-web` and both handed the shared per-request `UiRequestContext` (`app.epistola.suite.htmx`: `tenantKey` + a `hasPermission` predicate). A contributor that needs feature state reads it through the `ResolveFeatureToggles(tenantKey)` query (see feature-toggle reads below), not by injecting a service:
  - **Navigation** — `NavContributor` (package `app.epistola.suite.htmx.nav`). A module declares `NavGroup`s and emits `NavItem`s for a request (filtering on permission and/or toggles); `NavMenuAggregator` merges all contributors, drops empty groups, and derives the active section from the request path, and `layout/nav` just iterates `navGroups`. The host's own menu is a `CoreNavContributor` in `apps/epistola`; `epistola-support` owns the Support group + Overview; each `epistola-support-*` feature module ships a contributor for its item.
  - **Footer chrome** — `FooterContributor` (package `app.epistola.suite.htmx.footer`) returns Thymeleaf `FooterFragment`s (`template :: fragment`); `FooterFragmentResolver` collects them and `fragments/footer` `th:replace`s each. Example: `epistola-support-feedback`'s `FeedbackFooterContributor` injects the feedback FAB.

  Follow this shape for the next extension point (e.g. a `TemplateDetailTab` SPI: feature module ships a `@Component` + a UI handler returning an HTMX fragment; host page renders a tab strip with `hx-get` lazy-load) — add it when the first contributing feature needs it.

- **Quality checks are a ledger, not a check engine** (`epistola-core/quality/`, feature key `quality`, **alpha**, off by default). The suite does **not** run quality checks. Sources **submit** findings and the ledger owns them: an in-process source implements the `QualityFindingSource` SPI (a pure function from document to findings — the framework runs it on the daily sweep, after publish, and on the editor's "Check now"); a remote checker implements nothing and pushes over REST; a reviewer raises one by hand. All converge on `SubmitQualityFindings`. A submission is a source's **full current set** for a subject, so anything absent auto-resolves — that is where resolution-on-fix comes from, and why an empty submission is meaningful rather than a no-op. Checks only ever see a template's **example data**, never user data. Two invariants are easy to break and guarded by tests: reconciliation is scoped by `source_id` (or sources resolve each other's findings), and `IGNORED` is derived from a live ignore row rather than stored (which is what keeps an unchanged fingerprint's ignore true by construction). Before touching fingerprints, ignore scope, or staleness, read [`docs/quality.md`](docs/quality.md) — the `fingerprint` contract is subtle and everything rests on it. The key is `quality`, deliberately **not** in `SUPPORT_TIER`/`HUB_ONLY`: the hub contract has no `QUALITY` feature to grant, so a key there would be permanently unavailable wherever the support tier is on.

- **Feature-toggle reads go through CQRS queries, not the service.** `GetFeatureToggles` is the permission-gated (`TENANT_SETTINGS`) read backing the admin Features page; `ResolveFeatureToggles` is its `SystemInternal` (auth-bypassing) sibling for internal use — UI rendering (nav/footer contributors, shown to any signed-in user) and background schedulers. Both delegate to `FeatureToggleService.resolveAll`, which memoizes per request via a `ScopedValue` cache (bound by `FeatureToggleCacheFilter`, same idiom as `SecurityContext`/`MediatorContext`), so a whole page render issues one toggle query per tenant. Add new toggle reads as a query; only the resolution service touches JDBI.

### Application time and mediator context

Application time is owned by `MediatorContext`, not by Spring injection. The
active mediator context carries the `Mediator`, the current `Clock`, and
optionally the current `EpistolaPrincipal`. `MediatorContext` binds that state
with `ScopedValue`, and `EpistolaClock` resolves time from the bound context.

Rules:

- Use `EpistolaClock.instant()`, `offsetDateTime()`, `localDate()`, or
  `yearMonth()` for application time.
- Do not add direct application calls to `Instant.now()`,
  `OffsetDateTime.now()`, `LocalDate.now()`, `ZonedDateTime.now()`, or
  `YearMonth.now()`.
- Do not inject Spring `Clock` for application time. The Spring `Clock` bean is
  only a compatibility bridge for legacy/transitional code.
- Regular commands and queries get a mediator context scope from
  `SpringMediator`; handlers should not create their own scope.
- Entry points that start outside an existing mediator scope and execute
  immediately must bind a mediator context explicitly:

  ```kotlin
  MediatorContext.runWithMediator(mediator) {
      // scheduler or startup work
  }
  ```

- Work submitted to another thread must capture the context before submission
  and bind it inside the runnable/callable. Virtual threads are still separate
  threads; `ScopedValue` bindings do not automatically cross executor or
  callback boundaries.

  ```kotlin
  executor.submit(MediatorContext.runnable(mediator, principal) {
      // async work
  })
  ```

- Prefer `MediatorContext.current()` / `.send()` / `.query()` inside the bound
  scope instead of passing Spring services deeper into application operations.
- Database `NOW()`/`now()` is still correct for database-owned timestamps,
  triggers, row leases, claim/update comparisons, and other operations that must
  align with the database clock.
- The pure `modules/generation` renderer may accept an explicit `Clock` because
  it intentionally does not depend on `epistola-core`; callers from core should
  pass `EpistolaClock.current()`.
- Tests should use `EpistolaClockExtension`/`testClock` or
  `EpistolaClock.withClock`/`withInstant`, not wall-clock sleeps or direct
  JVM `now()` calls.

See [`docs/clock.md`](docs/clock.md) for the full architecture.

## Frontend Architecture

The frontend uses a **server-side rendering** approach:

- **Thymeleaf**: Template engine for rendering HTML on the server
- **HTMX**: For dynamic interactions without full page reloads
- **Client components**: Embedded modules (like the editor) for features requiring rich client-side interactivity

### Content Security Policy

A strict CSP is enforced on all UI responses (`SecurityConfig.kt`): **`script-src 'self'`** — no `'unsafe-inline'`, no `'unsafe-eval'`, no external origins. See [ADR 0010](docs/adr/0010-strict-script-src-csp.md). Consequences for template work (enforced by `CspTemplateComplianceTest`, fails the build):

- **No executable inline `<script>` anywhere.** All behavior lives in static JS files (`apps/epistola/src/main/resources/static/js/`, or a feature module's own `static/` dir). Note the shell uses `hx-boost="true"`, so even page navigation is an HTMX body swap — per-page `<head>` scripts never (re)load; app page behaviors are registered globally in `fragments/htmx.html`.
- **No inline `on*=` handler attributes** (`onclick=`, `onchange=`, …) and no `hx-on::*`/`hx-on-*` (these `eval()`). Instead: declare a `data-*` hook on the element and add a **delegated listener** in a static JS file — listeners are installed once on `document` (HTMX events bubble), so they work for content present at load and content swapped in later:

  ```html
  <!-- template: markup only -->
  <form hx-post="/tenants" data-reset-on-success>…</form>
  ```

  ```js
  // static JS: registered once, works for every such form, incl. swapped-in ones
  document.addEventListener("htmx:afterRequest", (e) => {
    const form = e.target.closest("form[data-reset-on-success]");
    if (form && e.detail.successful) form.reset();
  });
  ```

  Generic hooks already exist in `static/js/behaviors.js` (`data-open-dialog`, `data-close-dialog`, `data-confirm-url`, `data-confirm-submit`, `data-reset-on-success`, `data-copy-source`, …) — reuse before inventing new ones.

- **Server data reaches JS via inert JSON islands**, not executable code: `<script type="application/json" id="…" th:inline="javascript">` parsed from static JS (initialization is driven by `htmx:load`, which fires for the initial page and every swap; guard with a `data-…-mounted` attribute). Small values can ride `data-*` attributes instead.
- **`style-src` still allows `'unsafe-inline'`** (deliberate, see ADR 0010 Option E) — inline `style=` attributes are fine.
- UI tests fail on any CSP violation reported in the browser console (`BasePlaywrightTest`).

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

### Seed test state through commands, not raw SQL

When a test needs domain data to exist, create it by dispatching the **command**
(`CreateApiKey`, `CreateTenant`, …) through the mediator — the same way production
code does — rather than `INSERT`ing rows directly with JDBI. Command-seeded fixtures
track schema and validation changes automatically (a raw `INSERT` silently rots when a
column is added or a constraint tightens, and then fails the next unrelated change), and
they exercise the real write path.

Compose commands to reach the state you need instead of reaching for SQL:

- Need the entity's id to link other rows? Use the id the command **returns** — don't
  pre-generate one and force it in.
- Need a non-default lifecycle state (disabled, revoked, used)? Apply the command that
  produces it (e.g. `CreateApiKey` then `RevokeApiKey` for a disabled key;
  `RecordApiKeyUsage` to mark one used).

Raw SQL in a fixture is the **exception**, justified only when no command can produce the
needed state — e.g. tables with no command (`consumer_nodes`, `consumer_partition_cursors`),
or planting a **specific historical timestamp** the read path asserts against (commands
write `NOW()`). When you do drop to SQL, add a one-line comment saying why so it doesn't
read as the default.

### UI test rules (enforced — issue #418)

UI tests must use the `PlaywrightHtmxSupport` helpers: navigate via
`gotoAndReady`, await HTMX swaps via `htmxSettle()`, open dialogs via
`openDialogByTrigger`, assert web-first. `waitForTimeout`, the `:visible`
pseudo, blind `waitForSelector("…[open]")`, bare `page.navigate`, and forensic
`System.err.println` dumps are **banned and fail the build** via
`UiTestHygieneTest` (runs in `unitTest`). Prefer a handler-level
`*HandlerHtmxTest` over a browser test for server-contract assertions
(deterministic-only philosophy — no test-retry masking). Full rules:
[`docs/testing.md`](docs/testing.md#ui-tests-playwright).

### When to Run Which Tests

| Change type                           | Run                                            |
| ------------------------------------- | ---------------------------------------------- |
| Pure logic, algorithms, utilities     | `./gradlew unitTest`                           |
| Business logic, commands, queries, DB | `./gradlew integrationTest`                    |
| Thymeleaf templates, HTMX handlers    | `./gradlew integrationTest`                    |
| UI interaction, JavaScript behavior   | `./gradlew uiTest`                             |
| Before committing                     | `./gradlew unitTest integrationTest` (minimum) |
| Before creating a PR                  | `./gradlew test` (all)                         |

### Multi-instance & concurrency repro scripts (manual — not run by CI)

Two concurrency failure modes can only be reproduced against the **real boot fat
jar**, not the exploded test classpath, so they live as scripts under `scripts/`
rather than as JUnit tests. Run them by hand when touching cluster scheduling,
the `JobPoller`, generation threading, or load-test recovery. Both need Docker
(they start their own disk-backed Postgres) and `psql`; both use the `local`
Spring profile (datasource `127.0.0.1:4001`), so don't run them alongside a local
`bootRun` or each other.

- **`scripts/deadlock-burst-test.sh [attempts] [docs]`** — the #724 regression
  guard. Boots one node from the fat jar and fires a burst of concurrent renders,
  repeated N times; exits non-zero if any attempt **wedges** (a thread dump is
  captured). The iText/nested-jar-loader classloader deadlock needs BOTH the
  Spring Boot fat-jar nested-jar loader AND a concurrent cold first-load burst —
  neither exists under `./gradlew test`, which is why no unit/integration test can
  cover it. Rendering runs on **platform** threads specifically to avoid this
  (JEP 491); a refactor back to virtual threads makes this script wedge and fail.
  ```bash
  ./gradlew :apps:epistola:bootJar          # build the fat jar first
  SKIP_BUILD=1 scripts/deadlock-burst-test.sh 10 2000   # or omit SKIP_BUILD to let it build
  ```
- **`scripts/multi-instance-test.sh {up|verify|loadtest|chaos|staleness|down|all}`**
  — 3-node cluster harness (shared Postgres + round-robin proxy) that verifies the
  cluster seams: heartbeats, shared JDBC sessions, exactly-once generation,
  cross-node job distribution, SIGKILL chaos + stale-job recovery (#723/#725), and
  API-key revocation staleness. `all` runs the full sequence. Found all three of
  #723/#724/#725. See [`docs/cluster-resilience.md`](docs/cluster-resilience.md).

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
6. **Update CHANGELOG.md** - For notable changes under `[Unreleased]`. Helm chart changes go in `charts/epistola/CHANGELOG.md`; all other changes go in the root `CHANGELOG.md`. The in-app Changelog dialog parses and filters these entries, so new entries follow a **commit-style format** (no `### Added/Changed/Fixed` headers — the type carries that):

   ```
   - [**[audience]** ]type(scope): **Title.** Description…
   ```

   - **`type(scope)` is REQUIRED.** `type` is a Conventional-Commit type (`feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`); `scope` is a required lowercase kebab-case area (e.g. `editor`, `generation`, `cluster`, `locale`, `logs`). Same vocabulary as commit messages; a change spanning areas may list comma-separated scopes (`feat(editor,pdf):`).
   - **Audience badge is optional** and comes first: `**[user]**` for user-facing changes, `**[dev]**` for developer-internal ones; omit it for changes relevant to everyone. The dialog defaults to the **Users** view (which hides `**[dev]**`), so keep deep-internal churn (refactors, test infra, scheduler internals) tagged `**[dev]**`.
   - Example: `- **[dev]** fix(logs): **Recursion guard narrowed.** Scoped to the ApplicationLogIngestor logger.`
   - The dialog filters by **Audience** (Users/Developers/All), **Type** (the commit types), and **Scope**. Older released sections still use legacy Keep-a-Changelog `### ` headers (grandfathered — their type is mapped from the section, no scope); do not add `### ` headers to new entries. These conventions apply only to the root `CHANGELOG.md` (the Helm chart changelog is not shown in the UI). See [`CONTRIBUTING.md`](CONTRIBUTING.md#changelog-entries).

7. **Update documentation** - Check if changes require updates to docs in `docs/`, KDoc comments, or CLAUDE.md. Search for references to changed conventions, APIs, or patterns.
8. **Small commits** - Commit logical units of work separately
9. **Cut a demo/system catalog release** - When modifying bundled resources in `modules/epistola-core/src/main/resources/epistola/catalogs/{demo,system}/`, bump `release.version` (SemVer, strictly increasing) **and** regenerate `release.fingerprint` in `catalog.json`: run `./gradlew :modules:epistola-core:unitTest --tests "*BundledCatalogFingerprintTest"`, paste the reported "actual" fingerprint, re-run green. The loaders detect changes by **fingerprint**, not the version string. See [`docs/catalog-versioning.md`](docs/catalog-versioning.md).
10. **Consider catalog impact** - Whenever you add, modify, or remove a resource (template, stencil, theme, data contract, etc.), consider whether the change affects catalog exchange in `modules/epistola-catalog/`. Check if catalog import/export, serialization formats, manifest schemas, or version handling need updating to stay consistent with the resource change.
11. **Consider all surfaces** - When adding, changing, or removing a feature, evaluate the impact on all three surfaces the suite exposes: the **web UI** (Thymeleaf + HTMX handlers in `apps/epistola`), the **REST API** (`modules/epistola-core/api` + OpenAPI spec in `modules/rest-api`), and the **MCP server** (`modules/epistola-mcp`). A capability change usually needs to be reflected in all three (or an explicit decision to scope it to a subset). Don't ship a feature on one surface and silently drift the others.
12. **Keep components, registry, and demo catalog in sync** - When adding, changing, or removing an editor component, update both the component registry (`modules/editor/src/main/typescript/engine/registry.ts` — including `examples[]`) and the demo catalog (`modules/epistola-core/src/main/resources/epistola/catalogs/demo/`). The demo catalog is our kitchen sink: every feature should be exercised there in every reasonable way (variants, options, edge cases). New capability ⇒ new demo usage; changed signature ⇒ updated demo usage; removed component ⇒ removed demo usage (and bumped catalog version per item 9).
13. **Every feature MUST be demonstrated in the demo catalog** - This is a hard requirement and a PR blocker, broader than item 12 (which is component-specific). No feature is complete until it is exercised in the demo catalog (`modules/epistola-core/src/main/resources/epistola/catalogs/demo/`). Any user-facing capability — a rendering feature, a generation option, an editor behavior, a new template/theme/stencil/data-contract capability — must ship with a concrete demo resource (new, or an update to an existing one) that uses it realistically, including reasonable variants and edge cases. If a feature genuinely cannot be represented in the demo catalog, the PR must state explicitly why. Bump the catalog version per item 9 whenever you touch demo resources.
14. **Fonts are a cross-surface catalog capability** - Fonts span the web UI, REST, MCP, catalog exchange and generation (an applied case of item 11); keep them in sync and consult [`docs/fonts.md`](docs/fonts.md) before changing font model/resolution/determinism. Allowed asset/font media types are the seeded `asset_types` lookup table, not a Kotlin enum or CHECK — add a new asset/font type by inserting a row (and registering it where code must handle it), never by widening a constraint. `AssetMediaType` is an open value class; branch on `AssetMediaCategory`, not a closed set.
15. **Locale is resolved once and threaded to both render surfaces** - One BCP-47 locale per render via the `variant attribute → tenant default → app default` chain (`TenantLocaleResolver`); it feeds both the editor preview and the PDF renderer so they agree. Consult [`docs/locale.md`](docs/locale.md) before changing locale resolution, the `$formatDate` / `$formatLocaleNumber` token/picture support, or anything that must keep editor preview and PDF output in parity.

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
