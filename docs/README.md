# Epistola Suite documentation

This is the **technical** documentation — how the suite is built, deployed and
extended. Everything under `docs/` is indexed here in one place; start with
[Project overview](epistola.md) if you are new, otherwise jump to the topic you
need below.

Looking for **user** documentation — guides and tutorials for authoring
templates and running documents? That is published at
<https://epistola.app/en/learn>.

Repository-level documents live at the root: [`CONTRIBUTING.md`](../CONTRIBUTING.md),
[`CHANGELOG.md`](../CHANGELOG.md), [`SECURITY.md`](../SECURITY.md),
[`SUPPORT_POLICY.md`](../SUPPORT_POLICY.md), [`VULNERABILITIES.md`](../VULNERABILITIES.md),
[`DISCLAIMER.md`](../DISCLAIMER.md), [`CODE_OF_CONDUCT.md`](../CODE_OF_CONDUCT.md) and
[`CLAUDE.md`](../CLAUDE.md) (conventions for AI-assisted contributions).

## How to read the Status column

Not every page describes shipped behavior. Each entry is labelled:

| Label                | Meaning                                                                                                                     |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| **Current**          | Describes behavior as it ships today. Safe to rely on.                                                                      |
| **Alpha** / **Beta** | Ships, but experimental. Breaking changes may land in a MINOR release (see [`CLAUDE.md`](../CLAUDE.md#commit-conventions)). |
| **Proposed**         | A design or plan. Some of it may be built; file paths in it are proposals, not a map of the code.                           |
| **Record**           | A point-in-time artefact — a review, a measurement, sample output. True when written, not maintained.                       |

A page that is not **Current** says so in a `> **Status:** …` blockquote directly
under its title. That banner, not this table, is authoritative.

## Start here

| Doc                                           | Status   | What it covers                                                        |
| --------------------------------------------- | -------- | --------------------------------------------------------------------- |
| [Project overview](epistola.md)               | Current  | What Epistola is, its two audiences, and the core domain model.       |
| [Roadmap](roadmap.md)                         | Proposed | Vision, target audiences, and the phased feature outlook.             |
| [Architecture review](architecture-review.md) | Record   | Module boundaries, integration seams and their security implications. |

## Authoring: templates and the document model

| Doc                                                | Status  | What it covers                                                                     |
| -------------------------------------------------- | ------- | ---------------------------------------------------------------------------------- |
| [Editor features](editor-features.md)              | Current | Components, styling and rendering features available in the template editor.       |
| [Component registry](component-registry.md)        | Current | How the TypeScript component vocabulary reaches the backend as a JSON snapshot.    |
| [Stencils](stencils.md)                            | Current | Versioned, reusable components shared across templates.                            |
| [Stencil placeholders](stencil-placeholders.md)    | Current | The `placeholder` node: default slot, fill slot, and recursion limits.             |
| [Stencil parameters](stencil-parameters.md)        | Current | Parameter schemas, binding validation, and render-time parameter scope.            |
| [Variant attributes](attributes.md)                | Current | The tenant attribute registry and attribute-driven variant selection.              |
| [Code lists](code-lists.md)                        | Current | Named `{code, label}` collections that constrain attribute values.                 |
| [Data contracts](data-contracts.md)                | Current | The JSON Schema a template accepts, plus example data.                             |
| [Contract schema versioning](schema-versioning.md) | Current | Draft/published lifecycle of contract versions and how versions bind to templates. |
| [Fonts](fonts.md)                                  | Current | Fonts as catalog resources: modelling, resolution and deterministic rendering.     |
| [Locale](locale.md)                                | Current | One BCP-47 locale per render, and the formatting tokens that follow from it.       |

## Generating documents

| Doc                                                   | Status   | What it covers                                                             |
| ----------------------------------------------------- | -------- | -------------------------------------------------------------------------- |
| [Document generation](generation.md)                  | Current  | The generation pipeline from request to rendered output.                   |
| [PDF/A compliance](pdfa.md)                           | Current  | PDF/A-2b archival output and the per-template setting that controls it.    |
| [pdfrender worker](pdfrender.md)                      | Beta     | The slim, UI-less render worker that drains the shared job queue.          |
| [Rendering upgrades](rendering-upgrades.md)           | Current  | Upgrading render dependencies without changing existing documents.         |
| [Multi-format output and channels](output-formats.md) | Proposed | Exploratory survey of a second output format (HTML) and delivery channels. |

## Catalogs and resource exchange

| Doc                                                                 | Status  | What it covers                                                               |
| ------------------------------------------------------------------- | ------- | ---------------------------------------------------------------------------- |
| [Catalogs & resource exchange](exchange/README.md)                  | Current | Architecture, data model and import/export flows. Root of the exchange docs. |
| [Wire format v5](exchange/v5/README.md)                             | Current | Current catalog wire contract, one page per resource part.                   |
| [Wire format v4](exchange/v4/README.md)                             | Current | Previous wire contract, retained for compatibility.                          |
| [Catalog versioning](catalog-versioning.md)                         | Current | How a catalog declares a version and how "is this new?" is decided.          |
| [Catalog contract compatibility](catalog-contract-compatibility.md) | Current | Suite impact of adopting the portable `epistola-catalog` aggregate.          |
| [Resource reference graph](resource-reference-graph.md)             | Alpha   | Tenant-wide view of which resources depend on which.                         |
| [Version axes](version-axes.md)                                     | Current | The independent "version" concepts and which question each answers.          |

## Platform runtime

| Doc                                                        | Status  | What it covers                                                             |
| ---------------------------------------------------------- | ------- | -------------------------------------------------------------------------- |
| [Clock](clock.md)                                          | Current | Application time is owned by mediator context, never by `now()`.           |
| [Timers](timers.md)                                        | Current | PostgreSQL-backed cluster timers for delayed and recurring work.           |
| [Cluster resilience](cluster-resilience.md)                | Current | Surviving a degraded or wedged node: liveness, watchdog, recovery.         |
| [Database migrations](migrations.md)                       | Current | Module-owned Flyway migrations on one global namespace.                    |
| [Blob storage](storage.md)                                 | Current | The two binary stores, their lifecycles, and retention.                    |
| [Credential encryption](encryption.md)                     | Current | Encrypting stored secrets so a database dump alone exposes nothing usable. |
| [Feature toggles](feature-toggles.md)                      | Current | Per-tenant toggles and their two-tier resolution.                          |
| [CQRS eventing](eventing.md)                               | Record  | Design record for command eventing; its core ships today.                  |
| [Minimal eventing](minimal-eventing.md)                    | Record  | The original argument for PostgreSQL as the event substrate.               |
| [Horizontal scaling phase 1](horizontal-scaling-phase1.md) | Record  | The cluster-runtime design whose phase 1 has since shipped.                |

## Security and access

| Doc                                   | Status  | What it covers                                                            |
| ------------------------------------- | ------- | ------------------------------------------------------------------------- |
| [Authentication](auth.md)             | Current | Bean-driven auth, profiles, OIDC setup, auto-provisioning, safety guards. |
| [Authorization](authorization.md)     | Current | Roles, permissions, enforcement, and API-key scoping.                     |
| [Keycloak setup](keycloak-setup.md)   | Current | Configuring Keycloak as the OIDC provider.                                |
| [authentik setup](authentik-setup.md) | Current | Configuring authentik as the OIDC provider.                               |

## Integration surfaces

| Doc                                               | Status   | What it covers                                                             |
| ------------------------------------------------- | -------- | -------------------------------------------------------------------------- |
| [MCP server](mcp.md)                              | Current  | Read-only Model Context Protocol tools at `/api/mcp` for AI assistants.    |
| [Iframe embedding](embedding.md)                  | Current  | Embedding the UI in a host page and driving it over `postMessage`.         |
| [Consumer management API](consumer-management.md) | Proposed | Design for the consumer-management surface defined in `epistola-contract`. |

The REST API contract itself is owned by the external `epistola-contract`
package (the suite consumes its generated server interfaces); the controllers
that implement it live in [`modules/rest-api`](../modules/rest-api).

## Operating an installation

| Doc                                                        | Status   | What it covers                                                           |
| ---------------------------------------------------------- | -------- | ------------------------------------------------------------------------ |
| [Deployment](deployment.md)                                | Current  | Deploying with the `charts/epistola` Helm chart.                         |
| [Metrics & observability](metrics.md)                      | Current  | Exposed metrics and wiring your own OpenTelemetry pipeline.              |
| [Application logs](application-logs.md)                    | Current  | Persisting Logback events into a bounded, queryable table.               |
| [Audit log](audit-log.md)                                  | Current  | The PII-free, append-only record of who did and read what.               |
| [Tenant backup & restore](tenant-backup.md)                | Current  | Full-fidelity per-tenant backups and merge-not-cascade restore.          |
| [Version check](version-check.md)                          | Current  | The default-on daily release and support check, and how to disable it.   |
| [SBOM](sbom.md)                                            | Current  | CycloneDX SBOM generation for backend and frontend, and scanning it.     |
| [Load testing](loadtesting.md)                             | Current  | The embedded load-test feature for measuring installation capacity.      |
| [Collect performance](collect-performance.md)              | Record   | Measured limits of the v0.3 collect mechanism.                           |
| [Adaptive batch polling logs](adaptive-batch-logging.md)   | Record   | Sample log output showing the shape of adaptive-poll logging.            |
| [v0.4 coordinated rebalance](v04-coordinated-rebalance.md) | Proposed | Closing the collect affinity gap with a coordinated ring handover.       |
| [v0.5 push-based collect](v05-push-collect.md)             | Proposed | Push-based collect with a self-coordinating cluster, no external broker. |

## Feature modules

| Doc                               | Status   | What it covers                                                       |
| --------------------------------- | -------- | -------------------------------------------------------------------- |
| [Feedback](feedback.md)           | Current  | In-app feedback stored locally and optionally synced to GitHub.      |
| [Quality checks](quality.md)      | Alpha    | The findings ledger: sources submit, the ledger owns and reconciles. |
| [Plugin architecture](plugins.md) | Proposed | How optional feature modules extend the editor and platform.         |
| [AI plugin](ai.md)                | Proposed | Conversational template editing built on the plugin architecture.    |

## Working on the suite

| Doc                                                                      | Status   | What it covers                                                     |
| ------------------------------------------------------------------------ | -------- | ------------------------------------------------------------------ |
| [Testing guide](testing.md)                                              | Current  | Test profiles, the shared testing module, and the UI-test rules.   |
| [HTMX utilities](htmx.md)                                                | Current  | The Kotlin DSL that binds HTMX to Spring WebMvc.fn endpoints.      |
| [Dialog forms](dialog-forms.md)                                          | Current  | Server-sent, URL-addressable dialogs and their lifecycle helpers.  |
| [`data-testid` reference](data-testid-reference.md)                      | Current  | The soft contract of test ids consumed by the external test suite. |
| [Visual styleguide](brandguide.md)                                       | Current  | Colors, typography, spacing, components and UI patterns.           |
| [Entity ID refactor guide](id-refactor.md)                               | Current  | How to move an entity ID between UUID and slug form.               |
| [Shortcuts: command foundation](shortcuts-command-foundation.md)         | Current  | Command IDs, keybindings and context scoping.                      |
| [Shortcuts: command runtime](shortcuts-command-runtime.md)               | Current  | Key normalization, resolution, chords and event policy.            |
| [Shortcuts: plugin extension guide](shortcuts-plugin-extension-guide.md) | Current  | Adding plugin-provided keyboard shortcuts safely.                  |
| [GitHub repository guide](github.md)                                     | Current  | CI/CD, issue management, releases and community features.          |
| [Testability improvements](testability-improvements.md)                  | Proposed | A plan for shifting the integration-first test suite.              |

## Decision records

Architecture Decision Records live in [`adr/`](adr/README.md) — that index lists
every record with its status.

## Historical plans

Implementation plans that have been carried out (or abandoned) are kept in
[`plans/`](plans/README.md) for the reasoning they contain. They are **not**
current documentation.

## Conventions for new docs

- **One `# H1`** per file, matching the file's subject.
- **kebab-case filenames**: `resource-reference-graph.md`, not `resource_reference_graph.md`.
- **Status banner.** If the page is not current shipped behavior, put a
  `> **Status:** …` blockquote immediately under the H1 saying what it is —
  design, alpha/beta, or a dated record.
- **Add it here.** A doc that is not in this index is a doc nobody finds.
- **Link with relative paths** so links resolve on GitHub and in an editor.
- Run `pnpm format` before committing; Markdown is formatted too.
