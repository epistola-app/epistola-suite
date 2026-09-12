# Agent Instructions for Epistola Suite

A document suite: Spring Boot + Kotlin on JDK 25 (exact versions in `gradle/libs.versions.toml`),
server-rendered Thymeleaf + HTMX, a Vite/TypeScript editor module, in a multi-module Gradle monorepo.

This file is canonical for every coding agent. `CLAUDE.md` imports it; Codex reads it directly.
Keep it small: it holds what is true everywhere, plus where to find the rest. Detail belongs in the
area guide (`<area>/AGENTS.md`), the rule card (`.agents/rules/`), or the doc that already covers it.

## Stability contract

1.0.0 went GA on 31 July 2026, and two promises follow from it.

- **Data is never broken.** The database is not reset between versions. Every schema change is a
  forward, data-preserving Flyway migration. Never edit a merged migration, never fold `ALTER`s back
  into a `CREATE`, never write a destructive one. This holds regardless of feature maturity.
- **GA surfaces follow SemVer.** REST APIs, catalog wire formats, configuration and architecture are
  stable: breaking one needs a major release and a deliberate deprecation path. Features explicitly
  labelled alpha or beta are the exception — they may break in a MINOR, said so in the CHANGELOG.

## Repository map

`settings.gradle.kts` is the authoritative list of Gradle projects.

| Project                                                           | Role                                                                                |
| ----------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `apps/epistola`                                                   | The deployable app: UI, security, bootstrap, page chrome                            |
| `apps/epistola-demo`                                              | The app plus demo mode and the demo catalog (`docs/auth.md`)                        |
| `apps/pdfrender`                                                  | Headless render worker draining the shared job queue                                |
| `modules/epistola-core`                                           | Domains, commands/queries, mediator, JDBI, catalog + exchange                       |
| `modules/epistola-web`                                            | Shared web toolkit: HTMX DSL, UI SPIs, shared fragments                             |
| `modules/rest-api`                                                | REST controllers implementing the external `epistola-contract`                      |
| `modules/generation`                                              | Pure PDF rendering, no business logic                                               |
| `modules/editor`                                                  | Lit + ProseMirror editors (TypeScript)                                              |
| `modules/epistola-mcp`                                            | Read-only MCP server at `/api/mcp` (`docs/mcp.md`)                                  |
| `modules/epistola-quality`                                        | Quality-findings ledger — OSS, alpha (`docs/quality.md`)                            |
| `modules/epistola-audit`, `-crypto`, `-version-check`, `loadtest` | Audit log, credential encryption, release check, load tests                         |
| `modules/epistola-support*`                                       | Commercial tier: hub client plus feedback, snapshots, backups, upgrading, telemetry |
| `modules/testing`                                                 | Shared test infrastructure, not production code                                     |

Each module owns its Flyway migrations under `src/main/resources/db/migration/<module>/`, and a
feature module may ship its own handlers and templates. `modules/design-system` is a pnpm package,
not a Gradle project.

## House idioms

These hold everywhere. Each is enforced — the guard is named in the routing table below.

- **Dispatch through the mediator**: `Command.execute()` / `Query.query()`. Every command and query
  implements an `Authorized` marker (`RequiresPermission`, `RequiresPlatformRole`,
  `RequiresAuthentication`, `SystemInternal`).
- **Application time comes from `EpistolaClock`**, never a bare `Instant.now()`. Database `NOW()`
  stays correct for database-owned timestamps, leases and claim comparisons.
- **UI code never calls `/api/**`.** The REST API is for external systems; UI needs get a UI route.
- **No inline `<script>`, no `on*=` handlers, no `hx-on::*`** — a strict CSP forbids them. Behaviour
  lives in a static JS file as a delegated listener on a `data-*` hook.
- **JSON is Jackson 3** (`tools.jackson.*`), not `com.fasterxml.jackson` — its annotations excepted.
- **Tests seed state through commands**, not raw SQL.
- **Feature-toggle reads go through the CQRS queries** `ResolveFeatureToggles` / `GetFeatureToggles`,
  never by injecting the service.

## Where to look

Read the guide for the area you are touching before writing code — those are specific and
maintained. The right-hand column is what fails the build, so run it rather than guessing.

| When you touch                      | Read                                                                                              | Enforced by                                                                                                        |
| ----------------------------------- | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Commands, queries, domains          | [`modules/epistola-core/AGENTS.md`](modules/epistola-core/AGENTS.md)                              | `MediatorWiringTest`, `AuthorizationCoverageTest`, `DomainBoundaryTest`                                            |
| Catalog, exchange, bundled catalogs | [`catalog/AGENTS.md`](modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/AGENTS.md) | `CatalogExchangeIndependenceTest`, the fingerprint tests                                                           |
| UI handlers, routes, page chrome    | [`apps/epistola/AGENTS.md`](apps/epistola/AGENTS.md)                                              | `UiRestApiSeparationTest`, the markup guards                                                                       |
| Thymeleaf templates, static JS      | [`.agents/rules/ui-markup.md`](.agents/rules/ui-markup.md)                                        | `CspTemplateComplianceTest`, `DesignSystemClassTest`, `IconUsageTest`, `PageHeaderUsageTest`, `InputMaxLengthTest` |
| Any test                            | [`.agents/rules/tests.md`](.agents/rules/tests.md)                                                | `UiTestHygieneTest`                                                                                                |
| Flyway migrations                   | [`.agents/rules/migrations.md`](.agents/rules/migrations.md)                                      | `checkMigrationVersions`, `SchemaHygieneAppTest`, `TenantTableTopologyDriftIntegrationTest`                        |
| Application time                    | [`docs/clock.md`](docs/clock.md)                                                                  | `ApplicationClockUsageTest`                                                                                        |
| The editor                          | [`modules/editor/AGENTS.md`](modules/editor/AGENTS.md)                                            | `registry-examples.test.ts`, `check-component-registry.mjs`                                                        |
| REST endpoints, contract bumps      | [`modules/rest-api/AGENTS.md`](modules/rest-api/AGENTS.md)                                        | `checkContractVersionAlignment`, `ApiExceptionMappingsConsistencyTest`                                             |
| Feature toggles                     | [`docs/feature-toggles.md`](docs/feature-toggles.md)                                              | `KnownFeaturesTest`                                                                                                |
| Quality findings                    | [`docs/quality.md`](docs/quality.md)                                                              | the `epistola-quality` tests                                                                                       |
| Support-tier modules                | [`docs/tenant-backup.md`](docs/tenant-backup.md), [`docs/feedback.md`](docs/feedback.md)          | `WireContractAlignmentTest`, `TenantTableTopologyDriftIntegrationTest`                                             |
| MCP tools                           | [`docs/mcp.md`](docs/mcp.md)                                                                      | `ComponentTypesIntegrationTest`                                                                                    |
| Auth, demo mode, API keys           | [`docs/auth.md`](docs/auth.md)                                                                    | review                                                                                                             |
| Helm charts                         | [`charts/AGENTS.md`](charts/AGENTS.md)                                                            | `helm.yml`, the chart render tests                                                                                 |
| CI workflows                        | [`.github/AGENTS.md`](.github/AGENTS.md)                                                          | the workflows themselves                                                                                           |
| Docs                                | [`docs/AGENTS.md`](docs/AGENTS.md)                                                                | review                                                                                                             |
| Vulnerability records               | [`vulnerabilities/AGENTS.md`](vulnerabilities/AGENTS.md)                                          | `pnpm vulnerabilities:check`                                                                                       |

Contract-generated code — REST server interfaces, the template model, the catalog protocol — is not
in the working tree. It arrives from the `epistola-contract` artifacts, so grep will not find it.

## Verify

Scope the command to what you touched; a `--tests` filter that matches nothing in a module is no
longer an error.

| Change                                    | Run                                                                             |
| ----------------------------------------- | ------------------------------------------------------------------------------- |
| Pure logic                                | `./gradlew :module:unitTest`                                                    |
| Commands, queries, DB                     | `./gradlew :modules:epistola-core:integrationTest --tests "*YourTest*"`         |
| Handlers, templates                       | `./gradlew :apps:epistola:integrationTest --tests "*YourHandlerHtmxTest*"`      |
| Browser behaviour                         | `./gradlew :apps:epistola:uiTest --tests "*YourUiTest*"`                        |
| Conventions (CSP, UI/REST, clock, markup) | `./gradlew :apps:epistola:unitTest --tests "app.epistola.suite.architecture.*"` |
| Before a PR                               | `./gradlew test uiTest` — `test` excludes UI and perf                           |

Integration and UI tests need Docker. App-level Gradle tasks need `pnpm build` once first, because
the app packages the editor bundle. CI runs `checkMigrationVersions ktlintCheck
checkContractVersionAlignment testClasses`, then `test`, `uiTest`, and the frontend checks
(`pnpm lint:check`, `lint:css`, `format:check`, `license:check`, `pnpm test`).

To see a change working, run the demo app — it has a tenant, the demo catalog and a known API key:
`./gradlew :apps:epistola-demo:bootRun --args='--spring.profiles.active=demo,local,localauth'`.

## Done means

1. Formatted: `pnpm format`, and `./gradlew ktlintFormat` for Kotlin.
2. Tested at the right tier, with the command you actually ran stated.
3. A CHANGELOG entry under `[Unreleased]` (see below).
4. Docs updated where behaviour they describe changed; a new page indexed in `docs/README.md`.
5. Every user-facing capability demonstrated in the demo catalog — a PR blocker — with the
   catalog's `release.version` bumped and `release.fingerprint` regenerated.
6. All three surfaces considered: web UI, REST, MCP. Scope to fewer only deliberately.

## Commits and changelog

Conventional Commits. `.husky/commitlint.config.js` is the source of truth for the accepted types;
a scope is optional in a commit subject and **required** in a changelog entry. Breaking changes use
`feat!:` / `fix!:` or a `BREAKING CHANGE:` footer — but see the stability contract above for what a
break costs on a GA surface versus an alpha or beta one. Release versions come from the release
process, not from commit types.

AI assistance may be credited: `Co-Authored-By` and session trailers are welcome. The subject and
body still describe the change, not the tooling.

A notable change adds **one file** under `changelog/unreleased/`, never an edit to `CHANGELOG.md`
(which holds released history only, and is assembled at release time):

```bash
changelog/unreleased/$(date -u +%Y%m%d%H%M%S)-<slug>.md
```

Frontmatter is `type` and `scopes` and `title`, optionally `audience` (`user`/`dev`), `breaking`,
`maturity` and `issues`, then one paragraph of body. The format is in
[`changelog/README.md`](changelog/README.md) and `checkChangelogFragments` enforces it — including
that a change to shipped code carries one, unless the pull request is labelled `no-changelog`. Helm
chart changes go in `charts/epistola/CHANGELOG.md` instead.

## Rare workflows

Releases, Helm chart releases, contract bumps and template debugging have skills in
`.agents/skills/`. Vulnerability handling is confidential — read
[`vulnerabilities/AGENTS.md`](vulnerabilities/AGENTS.md) before touching that folder. The manual
cluster and concurrency harnesses are in [`docs/cluster-resilience.md`](docs/cluster-resilience.md).

## Changing these instructions

A new rule gets a guard if it can be mechanised; otherwise it becomes one line marked
`(unenforced)` in the owning guide or card. Put it in the narrowest place that covers it — this file
is only for what applies everywhere, and edits to it state their reason.
