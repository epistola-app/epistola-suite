# Epistola — Project Overview

Epistola is a multi-tenant document generation platform for creating professional PDF documents from templates with dynamic data binding. It serves two audiences: **developers** who integrate via a REST API to generate documents programmatically, and **business users** who design templates visually in a block-based editor without writing code.

> **Status:** Epistola is in active pre-production development. Breaking changes to schemas, APIs, and architecture are expected.

## Use Cases

- **Invoices & statements** — Generate branded invoices from order data, with per-customer layout variants and language localisation.
- **Letters & notifications** — Produce personalised correspondence (reminders, confirmations, welcome letters) from CRM or case-management systems.
- **Contracts & agreements** — Assemble contracts with conditional clauses, dynamic party details, and calculated totals.
- **Reports** — Create data-driven reports with tables, loops over datasets, and expression-based summaries.
- **Batch generation** — Submit bulk jobs to generate hundreds of documents from a single template and a data array.

In all cases the workflow is the same: define a template once in the visual editor, bind it to a data contract (JSON Schema), then call the API with real data to produce a PDF.

## Features

### Template Management

Templates are the central concept. Each template defines:

- **Data contract** — A JSON Schema (Draft 2020-12) describing the data the template expects, plus named data examples for testing.
- **Variants** — Presentation variations of the same template, tagged by language, brand, or audience (e.g. `en`, `nl`, `corporate`).
- **Versions** — Each variant has immutable, numbered versions (1–200) with a three-state lifecycle:
  - `DRAFT` — Editable work-in-progress (at most one per variant).
  - `PUBLISHED` — Frozen snapshot ready for use.
  - `ARCHIVED` — Retired version, preserved for audit.

### Visual Template Editor

A block-based WYSIWYG editor built with Lit 3 web components and ProseMirror for rich text.

**Block types:**

| Type | Category | Purpose |
|------|----------|---------|
| Text | Content | Rich text with inline expression chips (e.g. `{{customer.name}}`) |
| Container | Layout | Generic wrapper for grouping blocks |
| Columns | Layout | Multi-column layout with configurable widths |
| Table | Layout | Structured table with header rows and dynamic column widths |
| Conditional | Logic | Show/hide content based on a data expression |
| Loop | Logic | Repeat content for each item in an array |
| Page break | Page | Force a page break in PDF output |
| Page header | Page | Repeated header on every page |
| Page footer | Page | Repeated footer on every page |

**Editor capabilities:**

- Drag-and-drop block reordering (Atlassian Pragmatic DnD)
- Undo/redo with separate stacks for block operations and rich-text editing
- Autosave with debounce (3 s) and manual save (Ctrl+S)
- Live PDF preview panel with resizable split view
- Data contract manager with schema editing, example management, and migration assistant
- Expression dialog with live evaluation preview and JSONata quick reference
- Resolved expression values displayed inline in chips

### Dynamic Expressions

Templates support three expression languages, stored per expression:

| Language | Implementation | Use case |
|----------|----------------|----------|
| **JSONata** (default) | Dashjoin JSONata (Java) | Concise data transformation — paths, filters, aggregations, formatting |
| **JavaScript** | GraalJS (sandboxed, no file/network access) | Full JS for advanced calculations |
| **Simple path** | Custom lightweight evaluator | Fast, safe dot-path traversal only |

Expressions are used for data binding in text blocks, conditional visibility, loop iteration, and calculated values.

### PDF Generation

Server-side rendering via **iText Core 9.5.0** — direct PDF construction from the node/slot graph, with no intermediate HTML step.

| Document complexity | Typical render time |
|---------------------|---------------------|
| Simple (text + table) | 10–50 ms |
| Complex (loops, conditionals, multi-page) | 50–200 ms |

The rendering pipeline: load template version → evaluate expressions against input data → traverse node/slot graph → map each node type to an iText element (Paragraph, Table, Div, AreaBreak, etc.) → apply resolved styles → write PDF bytes.

Page headers and footers are rendered via iText event handlers so they repeat on every page automatically.

### Theming

Themes are reusable style definitions that can be shared across templates. A theme provides:

- **Document styles** — Default typography, colours, and alignment.
- **Page settings** — Paper format (A4, Letter), orientation, margins.
- **Block style presets** — Named style sets (like CSS classes) applicable to specific block types.

**Style cascade** (lowest → highest priority):

1. Theme document styles
2. Template document style overrides
3. Block style preset (from theme)
4. Block inline styles

**Theme selection cascade** (highest → lowest priority):

1. Variant-level theme override (`themeRef` in the template document)
2. Template-level default theme
3. Tenant default theme

### Multi-Tenancy

All data is isolated per tenant. Every entity (templates, themes, environments) belongs to a tenant via foreign keys with cascading deletes. Tenants are identified by a slug (e.g. `acme-corp`). Users are linked to tenants through memberships with roles.

Supports both SaaS multi-tenant deployment and self-hosted single-tenant deployment.

### Environment Management

Environments represent deployment stages (e.g. `staging`, `production`). Each environment can have one active version per variant, enabling:

- Testing a new version in staging before promoting to production.
- Rolling back to a previous version by re-activating it.
- Running different versions in different environments simultaneously.

### REST API

- Versioned under `/api/v1/` — separate from internal UI endpoints.
- OpenAPI 3.1 specification (managed with Redocly).
- RFC 7807 problem detail error responses.
- Supports single and batch document generation with job tracking.

### Document Generation Jobs

Batch generation is tracked as jobs with status progression: `PENDING` → `PROCESSING` → `COMPLETED` / `FAILED`. Includes adaptive batch sizing, stale job recovery, and partition-based cleanup.

## Technology Stack

### Backend

| Component | Technology |
|-----------|------------|
| Framework | Spring Boot 4.0.0 |
| Language | Kotlin 2.3.0 on JDK 25 |
| Database | PostgreSQL |
| SQL mapping | JDBI 3 |
| Migrations | Flyway |
| JSON | Jackson 3 |
| PDF engine | iText Core 9.5.0 (direct rendering) |
| Expression sandbox | GraalJS |
| JSONata | Dashjoin JSONata (Java) |
| JSON Schema | Draft 2020-12 validation |

### Frontend

| Component | Technology |
|-----------|------------|
| Server rendering | Thymeleaf |
| Dynamic interactions | HTMX |
| Editor | Lit 3 (web components) + ProseMirror |
| Drag & drop | Atlassian Pragmatic DnD |
| Editor build | Vite 7, TypeScript 5.9 |
| Editor tests | Vitest |

The frontend uses **server-side rendering** with Thymeleaf and HTMX for most pages (navigation, forms, lists). Only components that require rich client-side interactivity — primarily the template editor — are built as embedded JavaScript modules. This avoids the complexity of a full SPA while keeping the interactive editor experience.

### Infrastructure

| Component | Technology |
|-----------|------------|
| Build system | Gradle (Kotlin DSL) + pnpm workspace |
| Containers | Docker (Spring Boot Buildpacks) |
| Orchestration | Helm / Kubernetes |
| CI/CD | GitHub Actions |
| Tool versions | mise |
| API docs | Redocly (OpenAPI 3.1) |
| Linting | ktlint (Kotlin), ESLint + Prettier (TypeScript) |

## Architecture Overview

### Module Structure

```
apps/
  epistola/              → Spring Boot application (UI layer: Thymeleaf + HTMX)

modules/
  epistola-core/         → Business logic (domains, CQRS commands/queries, JDBI, REST API)
  generation/            → Pure PDF rendering (iText, expression evaluation, style application)
  template-model/        → Canonical domain types + JSON Schemas (shared by backend and editor)
  editor/                → Visual template editor (Lit + ProseMirror web components)
  rest-api/              → OpenAPI specification + generated server interfaces
```

### CQRS / Mediator Pattern

Business logic is organised around **commands** (mutations) and **queries** (reads), dispatched through a central `Mediator` interface. Each domain (templates, themes, environments, documents) defines its own command/query handlers. This decouples the UI/API layer from business logic and makes operations independently testable.

Examples: `CreateDocumentTemplate`, `PublishVersion`, `SetActivation`, `GenerateDocument`, `GetActiveVersion`, `ListVersions`.

### UI Handlers vs REST API

The backend has two distinct endpoint layers:

- **UI handlers** — Internal routes for Thymeleaf pages and HTMX fragments. Free to change.
- **REST API** — External `/api/v1/` endpoints with stable contracts, OpenAPI spec, and versioned media types.

UI code never calls REST API endpoints. This separation is enforced by an automated test.

### Template Document Model

Templates use a **normalised node/slot graph** rather than a deeply nested tree. The `TemplateDocument` contains flat maps of nodes and slots keyed by ID:

```
TemplateDocument
  ├── nodes: { "abc": Node, "def": Node, ... }
  └── slots: { "s1": Slot, "s2": Slot, ... }
```

Each `Node` references its child `Slot` IDs; each `Slot` references its parent `Node` and ordered child `Node` IDs. This flat structure simplifies operations like move, insert, and undo/redo compared to a nested tree.

## Quick Links

| Document | Description |
|----------|-------------|
| [README.md](../README.md) | Setup, build commands, getting started |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Contribution guidelines |
| [CHANGELOG.md](../CHANGELOG.md) | Release history |
| [Roadmap](roadmap.md) | Development phases and planned features |
| [Generation Architecture](generation.md) | PDF rendering pipeline details |
| [Editor Architecture](editor.md) | Frontend component architecture |
| [Visual Styleguide](brandguide.md) | UI design system and colour palette |
| [Testing](testing.md) | Test strategy and conventions |
| [GitHub Workflows](github.md) | CI/CD pipeline documentation |
