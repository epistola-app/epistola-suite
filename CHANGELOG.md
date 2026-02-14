# Epistola Suite Changelog

## [Unreleased]

### Added
- **Kotlin codegen from JSON Schema**: Template model types (PageSettings, Margins, PageFormat, Orientation, Expression, ExpressionLanguage, BorderStyle, etc.) are now generated from JSON Schema using `json-kotlin-schema-codegen`, establishing the schemas as the single source of truth for both TypeScript and Kotlin types
- **Open DocumentStyles**: `DocumentStyles` changed from a closed data class with 8 named properties to `Map<String, Any>`, matching the JSON Schema open object and letting the style-registry drive available properties

### Removed
- **TextAlign enum**: Removed from JSON Schema and all backend code. Text alignment is now a plain string value in the open DocumentStyles map

### Changed
- **Backend: Generated types replace handwritten types**: PageSettings, Margins, Orientation, PageFormat, Expression, ExpressionLanguage, and BorderStyle are now generated from JSON Schema. Enum values use lowercase matching JSON (e.g., `Orientation.portrait` instead of `Orientation.Portrait`). Margins properties changed from `Int` to `Long`. PageSettings/Margins no longer have default parameter values.
- **Editor V2: Style editing and theme resolution** (Phase 3):
  - **Open DocumentStyles data model**: `DocumentStyles` changed from a closed interface with 8 hardcoded properties to `Record<string, unknown>`, matching block styles and letting the style-registry drive available properties
  - **PageSettings gains backgroundColor**: Moved from document styles (it's a page property, not an inheritable text style)
  - **Margins documented as mm**: Schema descriptions now explicitly state the unit
  - **Style registry** (`engine/style-registry.ts`): Defines all style properties with groups (typography, spacing, background, borders), input types, options, units, and inheritable flags
  - **Style resolver** (`engine/styles.ts`): Pure functions implementing the full cascade: theme doc styles → template overrides → preset → inline. Only inheritable properties cascade to child nodes.
  - **Engine integration**: `EditorEngine` accepts optional `Theme` and `StyleRegistry`, computes resolved styles on every state change, exposes `getResolvedNodeStyles(nodeId)`, `resolvedDocStyles`, `resolvedPageSettings`, and `setTheme()`
  - **Inspector style UI**: Registry-driven style property editing grouped by category, with specialized inputs for unit values (number + unit dropdown), colors (native picker + text), spacing (4-value grid), and selects
  - **Style preset dropdown**: Upgraded from plain text to a dropdown populated from `theme.blockStylePresets`, filtered by `applicableTo`
  - **Document-level inspector**: When no node is selected, shows inheritable document styles and page settings (format, orientation, margins in mm, background color)
  - **Canvas style rendering**: Resolved styles applied as inline CSS on block content areas via Lit's `styleMap`; page background color applied to canvas container
  - **Input components** (`ui/inputs/style-inputs.ts`): Reusable UnitInput, ColorInput, SpacingInput, SelectInput with CSS value parsing/formatting
  - **22 new tests** for style resolver functions covering cascade, inheritance, page settings, and preset resolution (total: 100 tests)

- **Editor V2: Tree panel drag-and-drop**: Blocks can now be reordered and moved directly in the structure tree panel
  - Drag tree nodes above/below to reorder within the same slot
  - Drag onto a container node center to move a block inside it (make-child)
  - Cross-panel DnD: drag from tree to canvas, palette to tree — all panels share the same `DragData` protocol
  - Visual indicators: blue reorder lines (top/bottom), blue highlight for make-child targets
  - Root node cannot be dragged; leaf nodes block make-child zone
  - Reparent support for moving blocks to different nesting levels
  - 5 new unit tests for `resolveDropInsideNode()` function
- **Editor V2: Drag-and-drop block positioning**: Blocks can now be dragged to insert or reorder using `@atlaskit/pragmatic-drag-and-drop`
  - Palette items are drag sources — drag from palette to insert a new block at a specific position in the canvas
  - Canvas blocks are drag sources — drag by the block header to reorder within or across slots
  - Drop indicator line (blue 2px) shows exact insertion point on block edges
  - Visual feedback: source dimming during drag, empty slot highlighting on hover
  - Containment validation: drops are rejected if the parent type doesn't allow the child type (e.g. text into text)
  - Cycle prevention: can't drag a block into its own descendant
  - Click-to-insert remains as a quick shortcut alongside drag
  - Tree panel nodes now have `data-node-id` attributes (prep for future tree DnD)
  - 14 new unit tests for drop logic (resolveDropOnBlockEdge, resolveDropOnEmptySlot, canDropHere)

### Changed
- **Editor V2: Replace StylePolicy with applicableStyles**: Each component now declares which style property keys it supports via `applicableStyles: 'all' | string[]` instead of the old `StylePolicy` discriminated union. This makes components the authority over their own styles — groups remain a UI concern for inspector organization only. Layout components (columns, table, conditional, loop) now only support layout styles (spacing, background, borders) while content components (text, container, pageheader, pagefooter) support all styles. Canvas rendering also filters resolved styles through `applicableStyles`.
- **Editor V2: Improved architecture separation**: Extracted shared logic from UI components into headless modules
  - New `dnd/drop-handler.ts`: shared drop execution (InsertNode/MoveNode dispatch) used by canvas and tree panels
  - Moved `getNodeDepth()` and `findAncestorAtLevel()` from `EpistolaTree` into `engine/indexes.ts` as pure functions
  - Added `depthByNodeId` to `DocumentIndexes` for O(1) node depth lookups (computed once via BFS on state change)
  - New `engine/props.ts`: extracted `getNestedValue`/`setNestedValue` from inspector into shared module
  - 14 new tests for extracted utilities (depth, ancestors, nested props)

- **Design System: Split editor CSS into shared design system + component files**: Extracted the monolithic 550-line `editor.css` into a modular architecture
  - New `modules/design-system/` with `tokens.css`, `base.css`, and `components.css` — shared between the editor and the main app
  - Editor-specific styles split into 6 component files under `styles/` (editor-layout, toolbar, tree, canvas, palette, inspector)
  - `editor.css` is now an import-only entry point with `@layer` ordering
  - Generic form controls renamed: `.inspector-input` → `.ep-input`, `.inspector-select` → `.ep-select`, `.inspector-checkbox` → `.ep-checkbox`, `.inspector-delete-btn` → `.ep-btn-danger`
  - Design tokens expanded with additional color palettes (green, yellow), font families, and spacing values
  - Main app `main.css` rewritten to use `var(--ep-*)` design tokens instead of hardcoded colors
  - New `fragments/styles.html` Thymeleaf fragment centralizes CSS includes across all 14 templates
  - Gradle `copyDesignSystem` task copies design-system CSS into Spring Boot static resources
  - Local dev: `application-local.yaml` serves design-system from filesystem for live editing
  - Vite `@design` alias enables editor to import shared CSS from the design-system module

- **Editor V2: Replaced Tailwind CSS with vanilla CSS**: Removed Tailwind CSS dependency in favor of hand-written CSS using modern features
  - Uses `@layer` (base, layout, components, states) for cascade control
  - Custom properties (design tokens) for colors, spacing, typography, radii, and panel widths
  - CSS nesting for component-scoped styles
  - Semantic class names (`.toolbar-btn`, `.canvas-block`, `.inspector-input`, etc.) replace ~120 Tailwind utility classes
  - Removed `tailwindcss` and `@tailwindcss/vite` dependencies
  - CSS output: 7.97 kB (1.77 kB gzipped)

### Added
- **Editor V2 module (`modules/editor-v2/`)**: New template editor built with Lit web components + headless engine architecture, replacing React + TipTap + Zustand
  - **Node/slot data model**: Normalized graph (`TemplateDocument` with flat `nodes` and `slots` maps) replaces recursive `blocks[]` with composite IDs. Every insert/move/remove is a uniform slot.children update.
  - **JSON Schemas**: Draft 2020-12 schemas for `TemplateDocument`, `Theme`, `ComponentManifest`, `StyleRegistry` in `modules/template-model/schemas/`
  - **Type generation pipeline**: `json-schema-to-typescript` generates TS interfaces from schemas
  - **Headless engine** (`EditorEngine`): Framework-agnostic state management with deep-freeze immutability, derived indexes for O(1) parent lookups, subscribe/notify pattern
  - **Command system**: `InsertNode`, `RemoveNode`, `MoveNode`, `UpdateNodeProps`, `UpdateNodeStyles`, `SetStylePreset`, `UpdateDocumentStyles`, `UpdatePageSettings` — each produces an inverse for undo. Validation includes cycle detection, parent-child constraints, duplicate prevention.
  - **Undo/redo**: `UndoStack` with 100-entry depth, redo clears on new dispatch
  - **Component registry**: Built-in types (root, text, container, columns, table, conditional, loop, pagebreak, pageheader, pagefooter) with slot templates, allowed children, style policies, inspector config
  - **Lit UI layer**: `<epistola-editor>` shell with tree, canvas, palette, inspector, and toolbar panels. Light DOM for Tailwind CSS compatibility.
  - **Bundle size**: 57KB raw / 14KB gzipped (vs ~750KB+ for V1 with all React/Radix dependencies)
  - **45 engine tests** covering all commands, undo/redo, registry, selection, subscription, and immutability

### Changed
- **Editor README replaced with architecture documentation**: Replaced Vite boilerplate README with a comprehensive technical specification covering public API, data structures, state management, block system, rich text, expression evaluation, drag & drop, style system, PDF preview, table system, schema/validation, and utility functions. Serves as a rewrite specification with language-agnostic descriptions and collapsible TypeScript reference sections.

### Fixed
- **CI commits now signed by GitHub**: Coverage badge commits made during CI builds are now created via GitHub API instead of direct git commits, ensuring they are automatically signed by GitHub. This fixes issues with unsigned commits causing problems when merging main into feature branches that require signed commits.

 ### Added
- **OpenAPI spec included in GitHub Releases**: The bundled OpenAPI specification (`epistola-openapi.yaml`) is now attached to each release alongside the SBOMs
- **Simplified release artifact names**: Removed version numbers from release artifact filenames since the release itself is versioned. Artifacts are now named `epistola-backend-sbom.json`, `epistola-editor-sbom.json`, and `epistola-openapi.yaml`

### Changed
- **BREAKING: Simplified load test data model - eliminated redundant table**: Removed `load_test_requests` table which duplicated data already present in `document_generation_requests`
  - Load test configuration and metrics remain in `load_test_runs` table with new `batch_id` link
  - Request details queried directly from `document_generation_requests` via `batch_id` (single source of truth)
  - Eliminated 100% request data duplication (saves 6MB per 10K-request load test)
  - Detailed metrics (p50, p95, p99, avg, RPS, success rate) stored in `load_test_runs.metrics` JSONB column
  - Simpler schema: 1 fewer table, 1 fewer partitioned table to manage
  - Benefits: No data duplication, always accurate request data (never stale), simpler executor code, less partition management overhead
  - Breaking change acceptable since project is not yet in production

### Performance
- **Table partitioning for efficient TTL enforcement**: Implemented PostgreSQL table partitioning with automatic partition dropping for instant cleanup
  - Partitioned tables: `documents`, `document_generation_requests` (monthly RANGE partitions by created_at)
  - `PartitionMaintenanceScheduler` creates next month's partition at start of current month (daily execution at 2 AM)
  - Daily execution provides early failure detection (30-day buffer to catch and fix partition creation failures)
  - Minimal bootstrap: migrations create only current + next month partitions (sustainable long-term)
  - Instant cleanup via `DROP TABLE` instead of slow DELETE operations on millions of rows
  - 30-50% query speedup from partition pruning
  - Simple TTL enforcement via partition retention policy (3 months default)
  - Configurable via `epistola.partitions.*` properties

### Changed
- **BREAKING: Calculated batch counters**: Removed real-time batch counter columns in favor of on-demand calculation
  - Removed `completed_count` and `failed_count` columns from `document_generation_batches` (calculated on-demand)
  - Added `final_completed_count` and `final_failed_count` columns (persisted only when batch completes)
  - Added index on `(batch_id, status)` for efficient counter queries
  - Benefits: simpler code (no triggers, no scheduled reconciliation), always accurate, less write overhead
  - In-progress batches calculate counts on-demand; completed batches use stored final counts
- **Removed `DocumentCleanupScheduler`**: All cleanup operations now handled by partition dropping
  - Removed DELETE-based cleanup for expired jobs and old documents
  - Removed scheduled batch counter reconciliation (replaced with calculated counters)
  - Stale job recovery still handled by separate `StaleJobRecovery` component
- **Updated `LoadTestCleanupScheduler`**: Changed focus from requests to runs
  - Removed cleanup for `load_test_requests` (handled by partition dropping)
  - Added cleanup for `load_test_runs` (90-day retention by default)
  - Runs table NOT partitioned (low volume aggregate data)
- **Production configuration**: Added production-optimized settings in `application-prod.yaml`
  - Increased `max-concurrent-jobs` to 50 (from default 20)
  - Configured partition retention (3 months) and maintenance schedule (2 AM daily)
  - Configured load test runs retention (90 days)
  - 8-hour session timeout

### Fixed
- **Load test documents now follow standard retention policy**: Removed immediate deletion of load test documents. Documents now follow the standard 30-day retention policy managed by DocumentCleanupScheduler, allowing proper inspection of generated documents.

### Performance
- **Load test executor uses batch submission**: Replaced N individual `GenerateDocument` commands with single `GenerateDocumentBatch` call
  - 10-50x faster submission for large load tests (100+ documents)
  - One database transaction instead of N transactions
  - Single validation query instead of N queries
  - Simpler code: removed CompletableFuture, synchronized blocks, and executor management
  - Batch submission typically completes in <1 second for 1000 documents (was ~20-50 seconds)
  - **UI Change**: Removed "Concurrency Level" field from load test form (no longer applicable with batch submission)

### Changed
- **Improved load test results display**: Added proper CSS styling for metrics cards to display in a responsive grid layout. Cards now display in a clean grid instead of stacking vertically, with comprehensive styling for all UI components (progress bar, error summary, forms, alerts).
- **Improved document generation performance**: Refactored JobPoller with drain loop pattern for faster throughput
  - Increased `max-concurrent-jobs` from 2 to 20 (10x parallelism)
  - Increased `max-batch-size` from 10 to 50 (claim more per poll)
  - Added on-completion re-polling: when a job completes, immediately check for more work instead of waiting for next scheduled poll
  - Dedicated drain thread continuously claims work until queue is empty or at capacity
  - Scheduled 5s poll now serves as fallback safety net; primary driver is completion-triggered drain
  - Expected improvement: 100 documents now processed in ~execution time instead of several minutes

### Added
- **Adaptive batch job polling**: Job poller now dynamically adjusts batch size based on system performance
  - Uses Exponential Moving Average (EMA) to track job processing times
  - Increases batch size when jobs complete quickly (< 2s default)
  - Decreases batch size when system is under load (> 5s default)
  - Configurable via `epistola.generation.polling.adaptive-batch` properties
  - Exposes Micrometer metrics for monitoring: `epistola.jobs.processing_time_ema_ms`, `epistola.jobs.batch_size`, `epistola.jobs.claimed.total`, `epistola.jobs.completed.total`, `epistola.jobs.failed.total`
  - Default configuration maintains backward compatibility (min-batch-size: 1, max-batch-size: 10)
  - Respects `max-concurrent-jobs` limit when claiming batches

### Changed
- **BREAKING: Flattened document generation architecture for horizontal scaling**
  - Database schema (V5): Updated in place to eliminate two-table structure
    - Removed `document_generation_items` table entirely
    - Each request now represents ONE document (was: container for N items)
    - Added batch_id column to group related requests
    - Removed legacy fields: job_type, total_count, completed_count, failed_count (not needed in flattened structure)
    - Created `document_generation_batches` table for aggregated tracking
  - Simplified DocumentGenerationExecutor and command handlers:
    - Removed item-level concurrency control (Semaphore, CompletableFuture)
    - Concurrency now managed at JobPoller level
    - Removed `fetchPendingItems()`, `processItem()`, `finalizeRequest()` methods
    - Updated `generateDocument()` to accept `DocumentGenerationRequest` directly
    - Added `updateBatchProgress()` to atomically update batch counts
    - Simplified execution flow: generate → save → update batch (if applicable)
    - Updated GenerateDocumentHandler to create request with all data (no separate items)
  - Benefits:
    - True horizontal scaling: each request can be claimed independently by any instance
    - Simpler execution model: no item-level concurrency complexity
    - Better failure isolation: one failed document doesn't affect others
    - Performance: 10,000-doc batch distributed across all instances instead of single instance bottleneck
- **BREAKING: Refined module architecture for clearer separation of concerns**
  - **Business logic** (`modules/epistola-core`): Domain logic, commands, queries, mediator, JDBI config
  - **REST API** (`modules/rest-api`): OpenAPI specs + REST controllers for external systems
  - **UI layer** (`apps/epistola`): Thymeleaf, HTMX, UI handlers (internal only)
  - REST API controllers moved from epistola-core to rest-api module (prevents circular deps)
  - Database migrations moved from app to epistola-core (schema belongs with domain)
  - Domain integration tests moved from app to epistola-core (tests with tested code)
  - Benefits:
    * Clear layering: Core → REST API → App
    * epistola-core is pure business logic (no HTTP dependencies)
    * REST API can be deployed independently
    * Tests live with the code they test
- Enforce strict separation between UI handlers and REST API endpoints
- Editor now saves drafts via UI handler (`PUT /tenants/.../draft`) instead of REST API endpoint (`PUT /v1/tenants/.../draft`)
- All UI code now uses `application/json` content-type instead of REST API content-type (`application/vnd.epistola.v1+json`)

### Added
- New `modules/epistola-core` module for business logic
- Core module test infrastructure: `CoreIntegrationTestBase`, `CoreTestApplication`, `CoreTestcontainersConfiguration`
- Jackson and Flyway dependencies in epistola-core for independent testing
- UI handler for updating drafts: `PUT /tenants/{tenantId}/templates/{id}/variants/{variantId}/draft`
- Automated test to detect UI → REST API violations (`UiRestApiSeparationTest`)
- Documentation in CLAUDE.md explaining UI/REST separation and module structure
- **Session Expiry Handling with Login Popup**: Graceful session timeout UX that preserves unsaved work
  - Warning dialog shown 5 minutes before session expires
  - Expired dialog with "Log In Again" button when session times out
  - Re-authentication via popup window preserves form data on main page
  - Works with both form-based login (local dev) and OAuth2 (production)
  - `SessionExpiryCookieFilter` sets readable session expiry timestamp cookie
  - `PopupAwareAuthenticationSuccessHandler` redirects popup logins to success page
  - `PopupLoginFilter` preserves popup state across OAuth2 redirect chain
  - Native HTML `<dialog>` elements for accessible, modal dialogs
  - Session timeout configurable: 4 hours default, 1 minute for local dev testing
- **Spring Session JDBC**: Database-backed sessions for distributed deployments
  - Uses `spring-boot-session-jdbc` starter (Spring Boot 4.0)
  - Session data stored in `web_session` / `web_session_attributes` tables
  - Session cookie renamed to `sid`
  - `SessionConfig` explicitly enables JDBC sessions with configured timeout
- **CSRF Token Support**: All AJAX requests now include CSRF tokens for Spring Security compatibility
  - Created `fragments/htmx.html` fragment with HTMX and CSRF configuration
  - HTMX requests automatically include `X-XSRF-TOKEN` header via `htmx:configRequest` event
  - Editor fetch requests include CSRF token from `XSRF-TOKEN` cookie
  - All templates updated to use the shared HTMX fragment

### Module Architecture
- **Dependencies flow**:
  ```
  template-model → generation → epistola-core → rest-api → apps/epistola
  ```
- **epistola-core** contains:
  * Domain logic (tenants, templates, documents, themes, environments)
  * CQRS mediator pattern (commands, queries, handlers)
  * JDBI configuration and database access
  * Database migrations (`db/migration/*.sql`)
  * Domain integration tests (42 tests)
- **rest-api** contains:
  * OpenAPI specification files
  * REST API controllers (@RestController)
  * DTO mappers
  * External system integration layer
- **apps/epistola** contains:
  * Thymeleaf templates and HTMX
  * UI handlers (functional routing)
  * HTTP/UI integration tests (36 tests)

### Testing
- **Total: 153 tests** (111 unit + 42 integration)
- **epistola-core**: 153 tests (domain logic + integration)
- **apps/epistola**: HTTP/UI tests only
- Flyway migrations run automatically in epistola-core tests
- Tests live with the code they test

### Changed
- **BREAKING: TenantId changed from UUID to slug format**: Tenant IDs are now human-readable, URL-safe slugs instead of UUIDs
  - Format: 3-63 lowercase characters, letters (a-z), numbers (0-9), and hyphens (-)
  - Must start with a letter, cannot end with hyphen, no consecutive hyphens
  - Pattern: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` (DNS subdomain compatible)
  - Reserved words blocked: `admin`, `api`, `www`, `system`, `internal`, `null`, `undefined`
  - Tenant IDs must now be client-provided (no auto-generation via `TenantId.generate()`)
  - Examples: `acme-corp`, `demo-tenant`, `my-company-2024`
  - Database: `tenant_id` columns changed from `UUID` to `VARCHAR(63)` with CHECK constraint
  - API: `tenantId` path parameters changed from UUID to string with pattern validation
  - Generic `EntityId<T, V>` architecture: `SlugId<T>` for string IDs, `UuidId<T>` for UUID IDs
- **BREAKING: ThemeId changed from UUID to slug format**: Theme IDs are now human-readable, URL-safe slugs instead of UUIDs
  - Format: 3-20 lowercase characters, letters (a-z), numbers (0-9), and hyphens (-)
  - Must start with a letter, cannot end with hyphen, no consecutive hyphens
  - Pattern: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`
  - Theme IDs must now be client-provided (no auto-generation via `ThemeId.generate()`)
  - Examples: `corporate`, `modern`, `my-theme-2024`
  - Database: `theme_id` columns changed from `UUID` to `VARCHAR(20)` with CHECK constraint
  - API: `themeId` parameters changed from UUID to string with pattern validation
  - Web UI: Added slug input field to theme creation form
  - Auto-created default theme uses slug `default`
- **BREAKING: TemplateId changed from UUID to slug format**: Template IDs are now human-readable, URL-safe slugs instead of UUIDs
  - Format: 3-50 lowercase characters, letters (a-z), numbers (0-9), and hyphens (-)
  - Must start with a letter, cannot end with hyphen, no consecutive hyphens
  - Pattern: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`
  - Reserved words blocked: `admin`, `api`, `www`, `system`, `internal`, `null`, `undefined`, `default`, `new`, `create`, `edit`, `delete`
  - Template IDs must now be client-provided (no auto-generation)
  - Examples: `monthly-invoice`, `welcome-email`, `quarterly-report`
  - Database: `template_id` columns changed from `UUID` to `VARCHAR(50)` with CHECK constraint
  - API: `templateId` parameters changed from UUID to string with pattern validation
  - Web UI: Added slug input field to template creation form
  - Demo data: Updated to use explicit template slugs (e.g., `demo-invoice`)
- **BREAKING: VariantId changed from UUID to slug format**: Variant IDs are now human-readable, URL-safe slugs instead of UUIDs
  - Format: 3-50 lowercase characters, letters (a-z), numbers (0-9), and hyphens (-)
  - Must start with a letter, cannot end with hyphen, no consecutive hyphens
  - Pattern: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`
  - Reserved words blocked: `admin`, `api`, `www`, `system`, `internal`, `null`, `undefined`, `default`, `new`, `create`, `edit`, `delete`
  - Variant IDs must now be client-provided (no auto-generation)
  - Examples: `default`, `corporate`, `simple-v2`
  - Database: `variant_id` columns changed from `UUID` to `VARCHAR(50)` with CHECK constraint
  - API: `variantId` parameters changed from UUID to string with pattern validation
  - Web UI: Added slug input field to variant creation forms
  - Auto-created default variant uses slug `{templateId}-default` to ensure uniqueness across templates
- **BREAKING: EnvironmentId changed from UUID to slug format**: Environment IDs are now human-readable, URL-safe slugs instead of UUIDs
  - Format: 3-30 lowercase characters, letters (a-z), numbers (0-9), and hyphens (-)
  - Must start with a letter, cannot end with hyphen, no consecutive hyphens
- **BREAKING: VersionId changed from UUID to auto-incrementing integer (1-200)**: Version IDs are now sequential integers per variant instead of UUIDs
  - Version IDs are now sequential integers (1-200) calculated automatically per variant
  - First version is always 1, maximum 200 versions per variant
  - The `id` field IS the version number - no separate `versionNumber` field
  - **MAJOR BEHAVIOR CHANGE**: Publishing now updates the draft status instead of creating a new version record
    - Publishing converts the draft to published (draft is NOT preserved)
    - After publishing v1, must explicitly create a new draft for v2
    - Sequential versioning enforced (cannot skip version numbers)
  - Database changes:
    - `template_versions.id` changed from `UUID` to `INTEGER` with CHECK constraint (1-200)
    - Removed `version_number` column (merged into `id`)
    - Changed primary key from `id` to composite `(variant_id, id)`
    - Foreign keys now use composite references: `(variant_id, version_id)`
  - API changes:
    - `versionId` path parameters changed from UUID to integer (1-200)
    - Removed `versionNumber` field from VersionDto and VersionSummaryDto
    - Removed `versionNumber` field from ActivationDto
  - Code changes:
    - Removed `VersionId.generate()` method - IDs are calculated based on existing versions
    - `CreateVersion` command no longer accepts `id` parameter
    - `PublishVersion` command now updates status instead of creating new record
    - `UpdateDraft` command creates new draft with next version ID if none exists
  - Pattern: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`
  - Reserved words blocked: `admin`, `api`, `www`, `system`, `internal`, `null`, `undefined`
  - Environment IDs must now be client-provided (no auto-generation via `EnvironmentId.generate()`)
  - Examples: `production`, `staging`, `development`, `test`, `preview`
  - Database: `environment_id` columns changed from `UUID` to `VARCHAR(30)` with CHECK constraint
  - API: `environmentId` parameters changed from UUID to string with pattern validation
- **Code organization improvements**: Refactored large handlers and improved code maintainability
  - Split `DocumentTemplateHandler` (753 lines) into smaller focused handlers:
    - `VariantRouteHandler` for variant create/delete operations
    - `VersionRouteHandler` for draft/publish/archive operations
    - `TemplatePreviewHandler` for PDF preview generation
  - Created `UuidExtensions.kt` with `String.toUuidOrNull()` and `ServerRequest.pathUuid()` extensions
  - Updated `ThemeHandler` to use UUID extensions, removing duplicate `parseUUID()` helper
  - Extracted inline JavaScript from `themes/detail.html` to reusable ES modules:
    - `api-client.js` for shared fetch logic with CSRF handling
    - `theme-editor.js` for theme editing operations
  - Created reusable `fragments/search.html` Thymeleaf fragment for search boxes
  - Updated templates list, tenants list, and themes list pages to use the search fragment
- **Enhanced API exception handling**: Added exception handlers for domain exceptions
  - `ThemeNotFoundException` → 404 Not Found
  - `ThemeInUseException` → 409 Conflict
  - `LastThemeException` → 400 Bad Request
  - `DataModelValidationException` → 422 Unprocessable Entity
  - `ValidationException` → 400 Bad Request
  - Generic exception handler → 500 Internal Server Error (with logging)
- **Added logging to mediator**: `SpringMediator` now logs command/query dispatch and completion

### Added
- **Tenant Default Theme**: Each tenant now has a default theme that serves as the ultimate fallback in the theme cascade
  - Theme cascade order: Variant theme → Template theme → Tenant default theme
  - Creating a new tenant automatically creates a "Tenant Default" theme with sensible defaults
  - Tenant default theme can be changed via the Themes UI or `SetTenantDefaultTheme` command
  - At least one theme must exist per tenant (deletion constraints prevent removing last theme)
  - Cannot delete a theme that is set as the tenant's default
  - Themes list UI shows "Default" badge and "Set as Default" button for non-default themes
  - Demo tenant now uses "Corporate" as default theme instead of auto-created "Tenant Default"

### Fixed
- **CSRF 403 errors for AJAX requests**: Fixed Spring Security CSRF validation failing for AJAX requests (saving examples, schema, themes, PDF preview).
  - Replaced manual `CookieCsrfTokenRepository` + `CsrfTokenRequestAttributeHandler` configuration with Spring Security 7's `csrf.spa()` method which automatically handles SPA/AJAX patterns including BREACH protection
  - Added global `window.getCsrfToken()` helper function in htmx.html fragment that reads from the XSRF-TOKEN cookie
  - Updated all JavaScript fetch calls to include the CSRF token header (`X-XSRF-TOKEN`):
    - `pdf-preview.js` for PDF preview generation
    - `api-client.js` for theme editing (now reads from cookie instead of non-existent meta tag)
    - `templates/detail.html` for theme selection and data contract saves
- **Comprehensive null value handling across all API DTOs**: Fixed `InvalidNullException` when working with nullable fields in JSON payloads across the entire API surface. All OpenAPI DTOs now use Jackson `ObjectNode` instead of `Map<String, Any>` for proper null value handling.
  - Removed all `additionalProperties: true` from OpenAPI schemas (templates.yaml, generation.yaml, themes.yaml, versions.yaml)
  - Configured global type mapping: `object` → `ObjectNode` in OpenAPI generator
  - Updated all DTO mappers to use `valueToTree()` and `treeToValue()` for ObjectNode conversion
  - Affected DTOs: TemplateDto (schema, dataModel), DataExampleDto (data), GenerateDocumentRequest (data), DocumentGenerationItemDto (data), ThemeDto (blockStylePresets), VersionDto (templateModel), UpdateDraftRequest (templateModel)
  - ObjectNode correctly preserves null values during JSON deserialization and serialization, preventing type mismatch errors
- **PDF preview not applying template's default theme**: Preview endpoint was not passing the template's default theme to the PDF renderer, causing previews to miss the template-level theme cascade. Now correctly fetches the template and passes its `themeId` to `renderPdf()`.
- **Thymeleaf JavaScript serialization using wrong ObjectMapper**: Fixed data contract fields (schema, test data) showing Jackson `JsonNode` internal properties instead of actual values in template detail and editor pages. Created custom `IStandardJavaScriptSerializer` that uses Spring's auto-configured ObjectMapper for proper Jackson 3 serialization. Simplified `DocumentTemplateHandler.editor()` by removing manual Map conversions.
- **CreateVersion command now idempotent**: Fixed unique constraint violation when clicking "Create Draft" multiple times. The command now checks for an existing draft first and returns it if found, making the operation safe to call repeatedly without errors.

### Changed
- **EditorContext theme resolution simplified**: Removed `tenantDefaultTheme` field from `EditorContext` data class. The theme cascade (template → tenant) is now resolved server-side, returning a single `defaultTheme` field containing the effective theme for the editor.
- **Theme dropdown shows inherited theme**: When a variant has no theme override but the parent template has a default theme, the dropdown now shows "Default ({theme name})" instead of "No theme", making the theme cascade more obvious to users.
- **BREAKING: Template model now required for versions**: The `template_model` column in `template_versions` is now `NOT NULL`. All template versions must have content.
  - `TemplateVersion.templateModel` is now non-nullable
  - `EditorContext.templateModel` is now non-nullable (always returns resolved model)
  - `UpdateDraft` and `UpdateVersion` commands now require `templateModel` parameter
  - `CreateVariant` now creates draft versions with a default template model
  - Existing databases with NULL template_model values will need migration (reset database for development)

### Added
- **Theme System for Reusable Styling**: Introduced themes for defining reusable style collections across multiple templates
  - New `Theme` entity with document-level styles, page settings, and named block style presets
  - REST API endpoints for theme CRUD operations (`/v1/tenants/{tenantId}/themes`)
  - Web UI for managing themes at `/tenants/{tenantId}/themes`
    - List, create, search themes with Thymeleaf + HTMX
    - Detail page for editing document styles and block style presets
    - "Manage Themes" button added to templates list page
  - Two-level theme assignment for flexible override patterns:
    - Template-level default theme (`DocumentTemplate.themeId`) - dropdown on template detail page
    - Variant-level theme override (`TemplateModel.themeId`) - in editor's Document Properties panel
    - Variant theme overrides template default; variants without override inherit template's theme
  - Demo tenant includes two sample themes: "Corporate" and "Modern"
  - `ThemeStyleResolver` service merges theme and template styles following a cascade:
    1. Theme document styles (lowest priority)
    2. Template document styles (override theme)
    3. Theme block preset (when block has `stylePreset`)
    4. Block inline styles (highest priority)
  - `Block.stylePreset` field for referencing named style presets (like CSS classes)
  - Soft reference design: templates gracefully fall back to own styles if theme is deleted
  - Block style presets support all CSS-like properties: font, colors, margins, padding, alignment

- **Spring Transaction Support for JDBI**: Integrated `jdbi3-spring` to enable JDBI participation in Spring-managed transactions
  - JDBI now uses `SpringConnectionFactory` to reuse Spring's transactionally-managed connections
  - Services can compose multiple commands atomically using `@Transactional` or `TransactionTemplate`
  - Existing handler code continues to work unchanged - JDBI operations automatically participate in surrounding Spring transactions when they exist
  - `DemoLoader.recreateDemoTenant()` now runs in a single transaction - if any operation fails, the entire demo recreation is rolled back

### Changed
- **Test suite performance optimization**: Reduced test execution time from ~2 minutes to ~35 seconds (82% faster)
  - Added synchronous job execution mode for tests (`epistola.generation.synchronous=true`)
  - `SynchronousGenerationListener` executes document generation jobs immediately via Spring events
  - Eliminated Awaitility polling waits that were blocking test execution
  - Added JVM optimization flags for faster test startup (`-XX:+UseParallelGC`, `-XX:TieredStopAtLevel=1`)
  - Added `@ActiveProfiles("test")` to load test-specific configuration
  - JUnit 5 parallel execution prepared but disabled (data isolation needed for future enablement)

### Added
- **ScopedValue-based Mediator context**: Cleaner command/query dispatch using extension functions
  - `MediatorContext` object using JDK 21+ `ScopedValue` for thread-safe, virtual thread compatible mediator access
  - Extension functions: `Command<R>.execute()` and `Query<R>.query()` for idiomatic dispatch
  - `MediatorFilter` automatically binds mediator context for HTTP requests
  - API handlers no longer need `Mediator` constructor injection
  - Test helpers: `withMediator { }` in `BaseIntegrationTest` and `TestFixtureFactory`
  - Before: `mediator.send(CreateTenant(...))` / After: `CreateTenant(...).execute()`
  - Test infrastructure (`TestFixture`, `Scenario` DSLs) now uses extension functions internally
  - DSL scopes (`GivenScope`, `WhenScope`) capture mediator at construction for thread-safety (supports awaitility callbacks)

### Changed
- **BREAKING**: Added type-safe entity ID wrapper classes for compile-time type safety
  - New value classes: `TenantId`, `TemplateId`, `VariantId`, `VersionId`, `EnvironmentId`, `DocumentId`, `GenerationRequestId`, `GenerationItemId`
  - Prevents accidental misuse of IDs (e.g., passing `TemplateId` where `TenantId` is expected)
  - Zero runtime overhead using Kotlin `@JvmInline value class`
  - JDBI integration handled natively by `KotlinPlugin` (no custom factories needed)
  - OpenAPI schemas unchanged - still use `format: uuid` for standard client compatibility
  - Commands, queries, and entities now use typed IDs instead of raw `UUID`

### Fixed
- **JSON deserialization of `List<DataExample>`**: Fixed `ClassCastException` when deserializing template data examples from database
  - Created `DataExamples` wrapper type with custom Jackson serializer/deserializer
  - Properly handles Java type erasure that caused `List<LinkedHashMap>` instead of `List<DataExample>`

### Changed
- **BREAKING**: Migrated all entity IDs from database-generated Long/BIGSERIAL to client-provided UUIDv7
  - All entity IDs (Tenant, Template, Variant, Version, Environment, Document, GenerationRequest, GenerationItem) now use UUID
  - IDs must be provided by the client when creating entities via API or commands
  - Uses [uuid-creator](https://github.com/f4b6a3/uuid-creator) library v6.1.0 for RFC 9562 compliant UUIDv7 generation
  - UUIDv7 provides time-sortable identifiers with better distributed system properties
  - Added `UUIDv7` utility class for consistent ID generation
  - Database migrations updated to use UUID primary keys from the start
  - OpenAPI schemas updated: all ID fields changed from `integer/int64` to `string/uuid`
  - Test DSL updated: `tenant()`, `template()`, `variant()`, `version()` helpers now generate UUIDv7 IDs automatically

### Added
- **Type-safe Scenario DSL for integration tests**: New `scenario {}` DSL with Given-When-Then pattern
  - Type-safe flow: `given` block returns typed setup data accessible in `whenever` and `then` blocks
  - Automatic cleanup: `tenant()` helper automatically registers cleanup on scenario completion
  - Cleaner test setup: Reduced boilerplate from 8+ lines to 5 lines for common document generation setup
  - Reusable setup data classes: `DocumentSetup` for document generation tests
  - Helper methods: `tenant()`, `template()`, `variant()`, `version()` for common operations
  - Coexists with existing `fixture {}` DSL - no breaking changes

### Changed
- **Simplified gradlew wrapper**: Replaced traditional Gradle wrapper with mise-aware scripts
  - `gradlew` and `gradlew.bat` now activate mise environment and delegate to mise-managed Gradle
  - Removed `gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties`
  - All tool versions (Java, Gradle, Node, pnpm) defined in single `.mise.toml` file
  - Works seamlessly in IntelliJ, terminal, and CI (with `jdx/mise-action`)
  - Prerequisite: mise must be installed (`brew install mise` or see mise.jdx.dev)

### Changed
- **BREAKING**: Replaced Spring Batch with custom polling-based job executor for document generation
  - Removed Spring Batch dependency and all BATCH_* database tables
  - New architecture uses `SELECT FOR UPDATE SKIP LOCKED` for safe multi-instance job distribution
  - Jobs execute on virtual threads for non-blocking, high-concurrency processing
  - Added `JobPoller` for scheduled polling and claiming of pending jobs
  - Added `DocumentGenerationExecutor` for concurrent item processing within jobs
  - Added `StaleJobRecovery` for recovering jobs from crashed instances
  - Database schema changes: replaced `batch_job_execution_id` with `claimed_by` and `claimed_at` columns
  - Simpler architecture: 3 classes vs 6 classes + Spring Batch config
  - No external framework abstractions - plain Kotlin with virtual threads
  - Configurable via `epistola.generation.polling.*` properties:
    - `interval-ms`: Polling interval (default: 5000ms)
    - `max-concurrent-jobs`: Max jobs per instance (default: 2)
    - `stale-timeout-minutes`: Timeout before reclaiming stale jobs (default: 10 min)

### Added
- **Correlation ID Support for Document Generation**: Client-provided tracking IDs for documents
    - Added optional `correlationId` field (max 255 chars) to generation requests
    - `correlationId` is stored in both generation items and resulting documents
    - Query documents by `correlationId` using `GET /documents?correlationId=X`
    - Batch validation: rejects requests with duplicate `correlationId` values (null excluded)
    - Batch validation: rejects requests with duplicate `filename` values (null excluded)
    - Clear error messages identify which values are duplicated
- **Comprehensive Document Generation Test Suite**: Complete integration and unit tests for document generation API
    - Integration tests for single and batch document generation
    - Command handler tests for GenerateDocument, GenerateDocumentBatch, CancelGenerationJob, DeleteDocument
    - Query tests for GetDocument, ListDocuments, GetGenerationJob, ListGenerationJobs
    - Test utilities: TestTemplateBuilder for minimal TemplateModel construction
    - Multi-tenant isolation verification tests
    - Partial failure handling in batch processing
    - Job cancellation and document deletion workflows
    - PDF content validation (magic bytes verification)
    - All tests compile successfully with proper error handling
- **Asynchronous Document Generation API**: Comprehensive async document generation system
    - Single document generation with immediate job ID response (202 Accepted)
    - Batch document generation supporting multiple documents in one request
    - Job status tracking with real-time progress monitoring
    - Document download and listing endpoints
    - Job cancellation for pending/in-progress jobs
    - Automatic cleanup of expired jobs and old documents
    - PostgreSQL BYTEA storage for generated PDFs (future migration path to S3/MinIO)
    - Polling-based job execution with virtual threads for high concurrency
    - Fault tolerance: batch processing continues on partial failures
    - Multi-tenant isolation with proper security
    - REST API endpoints under `/v1/tenants/{tenantId}/documents/`
    - Configurable retention: jobs (7 days), documents (30 days)
- **Undo/Redo in Template Editor**: Full history management for structural and text changes
  - Zustand store integration using zundo temporal middleware
  - Tracks template changes (blocks, styles) with 100-entry history limit
  - 500ms debounce batches rapid changes (e.g., dragging, slider adjustments)
  - Keyboard shortcuts: Ctrl+Z/Cmd+Z (undo), Ctrl+Shift+Z/Cmd+Shift+Z/Ctrl+Y (redo)
  - Smart focus detection routes undo/redo to TipTap for text or Zustand for structure
  - Toolbar buttons with enabled/disabled states reflecting history availability
  - TipTap's built-in History extension handles character-level text undo
- **Page Header Block**: New block type to display repeating headers on every PDF page
  - Frontend: Visual component with blue styling and drag-drop child block support
  - Backend: `PageHeaderBlock` model with dedicated `PageHeaderEventHandler`
  - Appears in block palette with PanelTop icon
  - Header automatically repeats on every page using `PdfDocumentEvent.END_PAGE` events
  - Supports any child block types (text, images, tables, loops, conditionals)
  - Supports dynamic template variables and expressions
  - Fully backward compatible - existing templates work unchanged
- **Page Footer Block**: New block type to display repeating footers on every PDF page
  - Frontend: Visual component matching page header pattern with drag-drop child block support
  - Backend: `PageFooterBlock` model with dedicated `PageFooterEventHandler`
  - Appears in block palette with PanelBottom icon
  - Footer automatically repeats at bottom of every page using correct iText positioning (36pt margin from bottom edge)
  - Supports any child block types (text, images, tables, loops, conditionals)
  - Supports dynamic template variables and expressions
  - Fully backward compatible - existing templates work unchanged
  - Architecture: Separate event handlers for header and footer with independent PdfCanvas objects for proper isolation
- **Page Break Block**: New block type to force content onto a new page in PDF generation
  - Frontend: Visual component with horizontal lines and "Page Break" label
  - Backend: `PageBreakBlock` model and `PageBreakBlockRenderer` using iText's `AreaBreak()`
  - Appears in block palette with Minus icon
  - Fully backward compatible - existing templates work unchanged

### Changed

- **OpenSpec Migration**: Updated to new OPSX workflow structure
    - Migrated from legacy commands (`/openspec:proposal`, `/openspec:apply`, `/openspec:archive`) to new OPSX commands (`/opsx:new`, `/opsx:continue`, `/opsx:ff`, `/opsx:apply`, `/opsx:verify`, `/opsx:sync`, `/opsx:archive`, `/opsx:bulk-archive`, `/opsx:explore`)
    - Replaced `.claude/commands/openspec/` with `.claude/skills/openspec-*/` skill structure
    - Created `openspec/config.yaml` for context injection (replaces passive `project.md` approach)
    - Removed `openspec/AGENTS.md` and OpenSpec marker blocks from documentation
    - New workflow supports flexible, action-based development instead of rigid phase-locked process
- **BREAKING**: Reorganized template-related packages into aggregate root structure
  - Moved `variants/` package into `templates/commands/variants/` and `templates/queries/variants/`
  - Moved `versions/` package into `templates/commands/versions/` and `templates/queries/versions/`
  - Moved `activations/` package into `templates/commands/activations/` and `templates/queries/activations/`
  - Moved domain models to `templates/model/`
  - All imports from `app.epistola.suite.variants.*` must be updated to `app.epistola.suite.templates.*`
  - All imports from `app.epistola.suite.versions.*` must be updated to `app.epistola.suite.templates.*`
  - All imports from `app.epistola.suite.activations.*` must be updated to `app.epistola.suite.templates.*`
  - No database or API contract changes - only code organization
  - Clarifies that activation is part of the template deployment lifecycle, not environment configuration
  - Improves clarity of aggregate boundaries and domain model
- **API Architecture**: Refactored V1 API into focused controllers for better
  separation of concerns
  - Split monolithic V1ApiController (713 lines) into two controllers
  - EpistolaTenantApi: Handles tenant and environment operations (10 operations)
  - EpistolaTemplateApi: Handles template lifecycle operations (20 operations)
  - Uses OpenAPI Generator's `useTags` configuration to generate separate
    interfaces per tag
  - Controllers implement multiple tag-based interfaces: TenantsApi,
    EnvironmentsApi, TemplatesApi, VariantsApi, VersionsApi
  - Extracted shared DTO mappers and helpers to `api.v1.shared` package
  - No breaking changes to API contracts or client code
- **PDF Header/Footer Rendering**: Fixed positioning bug by using separate event handlers
  - Reverted from unified `PageHeaderFooterEventHandler` back to separate handlers
  - Root cause: Shared PdfCanvas between header and footer caused positioning conflicts in iText
  - Solution: `PageHeaderEventHandler` and `PageFooterEventHandler` with independent canvases
  - Each handler creates its own content stream for proper isolation
  - Header and footer now render correctly at top and bottom respectively
- **Demo Tenant Management**: Replaced simple existence check with version-tracked demo loader
  - New `app_metadata` table for application-level configuration
  - Demo tenant is now recreated only when version changes (bump `DEMO_VERSION` constant)
  - Demo loading can be disabled via `epistola.demo.enabled: false` configuration
  - Eliminates need to manually delete demo tenant for testing updates
  - Foundation for future deployment package management

### Fixed

- **Template Editor Navigation**: Back button in template editor now navigates to the template detail page instead of the templates list
  - Changed back button URL from `/tenants/{tenantId}/templates` to `/tenants/{tenantId}/templates/{templateId}`
  - Updated button text from "Back to Templates" to "Back to Template"
  - Improves UX by returning users to the specific template they were viewing
- **Template Model**: Simplified `documentStyles` to be consistently non-nullable across frontend and backend
  - Backend: `documentStyles: DocumentStyles = DocumentStyles()` (non-nullable with default)
  - Frontend: `documentStyles: DocumentStyles` (non-nullable)
  - Frontend: Removed unnecessary `normalizeTemplate()` function - no longer needed with consistent non-null contract
  - Impact: Cleaner API contract; templates always have documentStyles object; any dev database templates with null values will fail to load (acceptable since not in production)
- **Template Editor - Loop Block**: Fixed incorrect validation error for literal array expressions
  - Issue: Entering literal arrays like `[]` or `[1,2,3]` in loop expressions showed "Expression must resolve to an array" warning
  - Root cause: Loop validation used simple dot-notation path lookup instead of expression evaluation
  - Solution: Changed `LoopBlock` component to use `useEvaluator` hook for proper expression evaluation
  - Impact: Literal arrays, JSONata queries, and complex expressions now validate correctly in loops
- **PDF Generation**: Fixed iText7 error "Pdf indirect object belongs to other PDF document" when using bold/italic text
  - Root cause: Font objects were cached at class/object level and reused across multiple PDF documents
  - Solution: Implemented `FontCache` class to scope fonts per PDF document
  - Affected files: `TipTapConverter`, `StyleApplicator`, all block renderers
  - Impact: Multiple PDF renders (e.g., preview multiple templates) now work correctly

### Added
- **Enhanced Demo Loader with Realistic Template Content**
  - Demo templates now loaded from JSON files in `resources/demo/templates/`
  - Complete template definitions include: data model (JSON Schema), data examples, and visual layout (blocks, styles)
  - New `TemplateDefinition` data class for declarative template loading
  - Added production-ready Invoice Template with 3 example data sets
  - Invoice template demonstrates all block types: columns, containers, tables, loops, conditionals, expressions
  - DemoLoader now creates complete templates (metadata + visual content) in 4 steps:
    1. Create template metadata
    2. Update with data model and examples
    3. Get default variant
    4. Update draft with visual content
  - Bumped `DEMO_VERSION` to 2.0.1 to trigger reload
  - Foundation for future template marketplace/package system
  - Expression nodes in TipTap content require `"isNew": false` attribute to render correctly
  - See `resources/demo/README.md` for adding new templates
- PDF Preview mode in template editor
  - Toggle between HTML (Fast) and PDF (Actual) preview modes in editor header
  - **Live preview**: Shows PDF of current unsaved editor state, not just saved drafts
  - PdfPreview component sends current template model to backend for real-time rendering
  - Debounced fetching (500ms) to reduce API load during editing
  - Loading, error, and retry states with appropriate UI feedback
  - Blob URL management with proper cleanup to prevent memory leaks
  - HTML mode remains default for fast client-side rendering during editing
  - Backend preview endpoint now accepts optional `templateModel` in request body
- Single data example CRUD endpoints for independent example management
  - `PATCH /tenants/{tenantId}/templates/{id}/data-examples/{exampleId}` - Update a single example
  - `DELETE /tenants/{tenantId}/templates/{id}/data-examples/{exampleId}` - Delete a single example
  - Only validates the single example being updated (not all examples)
  - Supports `forceUpdate` flag to save despite validation warnings
  - Enables fixing/deleting invalid examples without affecting valid ones
  - Frontend editor now uses single-example endpoints for updates and deletes
- Data Contract Management in template editor
  - Visual schema editor with support for string, number, integer, boolean, array, and object types
  - Nested field support for object and array-of-object types (UI limits to one level; types support deeper nesting)
  - Array item type selector
  - Zod schema definitions for type safety and runtime validation
  - Required field toggle
  - "Generate from example" button to infer schema from test data
  - Advanced JSON toggle for direct JSON Schema editing
  - Tab-based UI with Schema and Test Data tabs
  - Expression path extraction from template blocks
  - Impact analysis to detect expressions not covered by schema
  - Frontend schema validation for test data examples
  - Backend schema validation during PDF generation (returns 400 with structured errors)
  - Schema persistence via template's dataModel field
  - Schema compatibility validation endpoint (`POST /validate-schema`)
  - Migration Assistant dialog showing compatibility issues with auto-fix suggestions
  - Type conversion for simple cases (string↔number, string↔boolean)
  - ValidationMessages component with separate error/warning display
  - DialogFooterActions component with unsaved changes indicator
- Data Examples Manager in template editor
  - Dropdown in editor header to select active test data example
  - Settings dialog to create, edit, and delete data examples
  - CodeMirror-based JSON editor for data editing
  - Auto-save to backend via PATCH API on save
  - Selected example automatically updates preview expression evaluation
  - Falls back to built-in default data when no examples exist
- PDF Preview button in versions table
  - Preview PDF button for Draft, Published, and Archived versions
  - Uses template's dataExamples for test data (first example or empty object)
  - Opens generated PDF in new browser tab
  - Loading state with visual feedback during PDF generation
- Expression language support (JSONata, JavaScript, SimplePath)
  - JSONata as the recommended language for template designers (concise, JSON-focused syntax)
  - JavaScript support for power users with full JS capabilities (array methods, etc.)
  - SimplePath for fast, safe path-only traversal (no operations, no code execution)
  - Expression language selection stored in Expression model
  - Server-side: JsonataEvaluator (dashjoin/jsonata), JavaScriptEvaluator (GraalJS sandbox), SimplePathEvaluator
  - Browser-side: JsonataEvaluator (jsonata npm), DirectEvaluator (Function)
  - CompositeExpressionEvaluator dispatches based on language field
  - Language toggle UI in expression editor popover
  - JSONata is now the default expression language
  - Comprehensive tests for all evaluators (JsonataEvaluatorTest, JavaScriptEvaluatorTest, SimplePathEvaluatorTest, CompositeExpressionEvaluatorTest)
- Server-side PDF generation using iText Core 9
  - New `modules/generation` module with DirectPdfRenderer for native PDF generation
  - New `modules/template-model` module with shared template types
  - Expression evaluation ({{customer.name}}) with path traversal and array support
  - TipTap JSON to iText conversion for rich text content
  - Block renderers: Text, Container, Columns, Table, Conditional, Loop
  - Style applicator supporting CSS-like properties (colors, margins, padding, fonts)
  - Preview endpoint: `POST /tenants/{tenantId}/templates/{id}/variants/{variantId}/preview`
  - Streaming PDF output to avoid memory buffering
- Template variants, versions, and lifecycle management (API-first)
  - New domain model: Template → Variant → Version with lifecycle states
  - Environments: Tenant-configurable deployment targets (staging, production, etc.)
  - Variants: Template presentation variations distinguished by free-form tags (locale, brand)
  - Versions: Immutable published versions with draft/published/archived lifecycle
  - Version numbering: Sequential assignment on publish, drafts have no version number
  - Environment activations: Configure which version is active per environment per variant
  - Database migration V3 with new tables: environments, template_variants, template_versions, environment_activations
  - OpenAPI endpoints for complete CRUD on environments, variants, versions, and lifecycle operations
  - API controller stubs ready for implementation
  - Note: Editor UI integration with versions is pending (TODO marked in code)
- Expression editor with CodeMirror integration and popover UI
  - Runtime type inference for autocomplete based on actual data values
  - Intelligent method suggestions for strings, arrays, numbers, and booleans
  - Method chaining support with proper return type tracking
  - Scope variable support for loop items and indices
  - Live preview of expression evaluation results
  - Body scroll lock when popover is open
- Comprehensive test suite for expression utilities (119 tests)
  - Vitest testing infrastructure with jsdom environment
  - Tests for type inference, path parsing, and completion source
  - Coverage reporting with v8 provider
- Unsaved changes detection in template editor
  - Visual indicator when template has unsaved changes
  - Warns users before leaving with unsaved work
- AutoSave component with debounced save and status indicator
  - Configurable debounce delay
  - Visual feedback for save state
- Resizable panel layout for editor components
  - Draggable dividers between canvas, preview, and sidebar
  - Persistent layout preferences
- Ctrl+S keyboard shortcut for saving templates
- shadcn/ui component library integration
  - Button, Popover, Tabs, Accordion, Dialog, Select, and more
  - Consistent design system across editor
- Prettier integration for code formatting
  - Format scripts in package.json (`format`, `format:check`)
  - ESLint configured to work alongside Prettier
- Inter font family for improved typography
- AnimateUI Switch component from shadcn

### Changed
- Refactored Data Contract Manager for better UX
  - Local-first draft state: edits are local until explicit save
  - Save & Stay Open pattern: dialog remains open after save with success feedback
  - Dirty state indicators on tabs showing unsaved changes
  - Close confirmation when there are unsaved changes
  - Force update option to save schema despite validation warnings
  - Replaced `alert()` calls with proper UI feedback components
- Redesigned template editor UI with shadcn components
  - BlockPalette with tabs and animations
  - StyleSidebar with improved organization and version display in footer
  - BlockStyleEditor with cleaner layout
  - Embedded toolbar with async save handling
  - BlockHeader extracted as reusable component with floating toolbar
  - Table config popup migrated to shadcn components
- Moved version display from global footer to editor sidebar for cleaner full-screen experience
- Removed embedded fonts to reduce CSS bundle size (~750KB savings)
- SaveButton refactored with useCallback optimization

### Fixed
- Schema-manager state management and schema-data synchronization
  - Fixed tab switching losing local changes by introducing shared draft state across tabs
  - Moved useDataContractDraft from individual sections to App.tsx for single source of truth
  - Fixed input focus loss in field name editor by replacing ref-based sync with useEffect
  - Implemented atomic schema-examples migration save (examples saved first, then schema)
  - Added dirty state indicators (●) on tabs when unsaved changes exist
  - Added "Save All Changes" button for one-click save of all changes
  - Added warning banner showing when unsaved changes are present
- SchemaEditor component using incorrect Zod imports (`uuidv4`, `ZodUUID`) that don't exist in Zod library
  - Replaced with proper `uuid` package imports for UUID generation
- Schema validation returning `compatible: true` on network/server errors in editor
  - Now correctly returns `compatible: false` with error message so UI can display failure
- MigrationAssistant dialog not updating selected migrations when `migrations` prop changes
  - Added useEffect to sync state when migrations are updated
- Data examples creation flow in template editor
  - Removed premature backend save when clicking "+" to add new example
  - Examples are now created locally first, allowing user to edit before save
  - Backend persistence only happens when user clicks "Save" with valid data
  - Fixes chicken-and-egg problem where empty data `{}` failed schema validation
- Radix UI package conflict causing infinite re-render loops in accordion with select components
- ColorPicker performance: added throttling to color area and commit-on-close pattern
- Color input width in ColorPicker component
- Unit input id placement for accessibility
- Added `pt` CSS unit support in style regex
- Corrected main.tsx path in index.html

### Added
- OpenAPI spec-driven API development infrastructure
  - New `modules/api-spec` module containing OpenAPI 3.1 YAML specifications
  - New `modules/api-server` module with generated Kotlin interfaces and DTOs (Jackson 3.x)
  - New `modules/api-client` module with generated client code (Jackson 2.x for consumer compatibility)
  - OpenAPI Generator 7.12.0 with `kotlin-spring` generator for server, `kotlin` for client
  - Redocly CLI for bundling modular specs, linting, and generating documentation
  - Static API documentation (Redoc) available at `/api-docs/` in the running application
  - Header-based API versioning using `application/vnd.epistola.v1+json` media type
  - Modular spec organization: `paths/` for endpoints, `components/schemas/` for models
  - Initial API spec covers Tenants and Templates CRUD operations
  - `V1ApiController` implementing generated `V1Api` interface using Mediator pattern
  - API endpoints available at `/api/v1/tenants` and `/api/v1/tenants/{tenantId}/templates`
  - Note: `api-spec` module will be extracted to a separate repository with its own release pipeline in the future
- Custom startup banner displaying application version, Spring Boot version, and Java version
  - ASCII art logo with version information printed on application startup
- Version display in UI footer across all pages
  - Fixed footer at bottom of all pages showing application name and version
  - Uses Spring Boot's BuildProperties for version information from build
  - Footer fragment (`fragments/footer.html`) included in all Thymeleaf templates
- Comprehensive product backlog with 58 GitHub issues organized by phase and domain
  - 4 milestones: MVP, Adoption, Enterprise, Governance
  - 11 domain labels for feature categorization (api, editor, generation, sdk, security, etc.)
  - 4 phase labels for release planning (mvp, adoption, enterprise, later)
  - 5 size labels for effort estimation (xs, s, m, l, xl)
- Public roadmap document (`docs/roadmap.md`) with development phases and technical decisions
- Updated project context (`openspec/project.md`) with tech stack, conventions, and domain model

### Changed
- Template content (templateModel) moved from DocumentTemplate to TemplateVersion
  - DocumentTemplate now only contains metadata: name, dataModel, dataExamples
  - Visual layout (templateModel) is stored in versions, enabling versioned content
  - CreateDocumentTemplate command now auto-creates default variant with draft version
- Unified tool version management with mise across local development and CI
  - Added Gradle 9.2.1 to `.mise.toml` for consistent version management
  - GitHub Actions workflows now use `jdx/mise-action@v2` instead of individual setup actions
  - Removed Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) in favor of mise-managed Gradle
  - All documentation updated to use `gradle` command instead of `./gradlew`

### Fixed
- Security scan workflow failing due to non-existent Gradle task `:modules:editor:npmSbom`
  - Frontend SBOM is generated via npm, not Gradle
  - Workflow now runs npm commands directly in the editor module directory

### Added
- GitHub MCP server integration for AI-assisted issue and project management (`scripts/gh-mcp/`)
  - Cross-platform secure token storage using OS credential managers (macOS Keychain, Windows Credential Vault)
  - `pnpm run setup:github-mcp` script guides fine-grained PAT creation with minimal permissions
  - MCP wrapper script validates token availability before starting server
  - `.mcp.json` configuration for Claude Code integration (safe to commit, no secrets)
- GitHub Project configuration as code (`.github/project.yml`)
  - Declarative project field definitions synced via GitHub Actions workflow
  - Custom fields: Status (kanban), Priority (P0-P3), Size (XS-XL), Type, Target Date, Started At, Blocked Reason
  - Automatic project creation and field synchronization on config changes
  - GitHub App authentication for project management (fine-grained PATs don't support Projects v2)
  - Repository linking verified but not automated (requires admin; workflow fails with instructions if missing)

### Changed
- Separated frontend (pnpm) and backend (Gradle) build steps for simpler configuration
  - Frontend: `pnpm install && pnpm build` builds all modules in `modules/`
  - Backend: `gradle build` compiles, tests, and packages (requires frontend built first)
  - Gradle verifies frontend is built before packaging, failing with helpful error if not
  - Removed node-gradle plugin dependency for cleaner Gradle configuration
  - CI workflow updated to run pnpm build before Gradle with proper caching

### Added
- pnpm workspaces for frontend module management
  - Root `pnpm-workspace.yaml` orchestrates all modules in `modules/`
  - Unified dependency management and build commands from repo root
  - `pnpm build` builds all modules, `pnpm --filter @epistola/<module> build` for specific modules
- Import maps for shared JavaScript dependencies to avoid duplicate bundling
  - `modules/vendor` package uses Rollup to bundle React, ReactDOM, Zustand, and Immer as proper ESM with named exports
  - Custom entry wrappers ensure CJS packages export named bindings (not just default)
  - Editor module excludes shared dependencies via Vite externals (~750KB vs ~1.1MB bundle)
  - Thymeleaf import map fragment (`fragments/importmap.html`) for browser-native dependency resolution
  - Shared dependencies loaded once and cached, reducing page load time for multiple interactive components

### Changed
- Simplified template editor data passing by using Thymeleaf's native JavaScript serialization
  - Removed unnecessary JSON stringify/parse roundtrip in template handler
  - `templateModel` is now passed directly to the view and serialized by Thymeleaf

### Added
- Docker image build configuration with JVM and native options
  - JVM image (default): `gradle bootBuildImage`
  - Native image (disabled): `gradle bootBuildImage -PnativeImage=true` - broken due to JDBI/Kotlin reflection ([jdbi#2475](https://github.com/jdbi/jdbi/issues/2475))
  - Images signed with Cosign and SBOM attested
- CloudNativePG (CNPG) support in Helm chart for PostgreSQL database management
  - Three database modes: `cnpg` (create cluster), `cnpgExisting` (use existing), `external` (manual config)
  - New `database.type` configuration with discriminator pattern
  - Automatic CNPG Cluster resource creation when `database.type: cnpg`
  - Support for consuming existing CNPG cluster credentials
  - Backward compatibility with deprecated `postgresql.*` configuration
  - Post-install notes with cluster status and connection commands
- Extended document templates with data model and examples support
  - Renamed `content` column to `templateModel` (visual layout definition)
  - Added `dataModel` column for JSON Schema definitions
  - Added `dataExamples` column for named JSON examples that validate against the schema
  - JSON Schema validation using networknt/json-schema-validator 3.0.0 (Jackson 3 compatible)
  - `PATCH /tenants/{tenantId}/templates/{id}` endpoint for partial updates
  - Strict validation: examples must conform to schema, schema changes validate existing examples
- Form validation for tenant and template creation
  - Validation in command constructors using `require()` for fail-fast behavior
  - Name is required (non-blank) and max 255 characters
  - Inline error display below form fields using HTMX retarget/reswap
  - CSS styles for error states (red border, error message styling)
  - Form value preservation on validation error
- Testing documentation (`docs/testing.md`) covering test infrastructure, patterns, and best practices
  - Test framework overview (JUnit 5, Testcontainers, Spring Boot Test, AssertJ, Kover)
  - Guide to BaseIntegrationTest and TestFixture DSL usage
  - HTTP/route testing patterns including HTMX header handling
  - Testcontainers configuration and troubleshooting tips
- Command/Query Bus (Mediator pattern) for centralized message dispatching
  - `Command<R>` and `Query<R>` marker interfaces for type-safe message passing
  - `CommandHandler` and `QueryHandler` interfaces for handler implementations
  - `Mediator` interface with `send()` for commands and `query()` for queries
  - `SpringMediator` auto-discovers handlers via Spring's `ApplicationContext`
  - Decouples consumers from handlers enabling future cross-cutting concerns
- TestFixture Kotlin DSL for given-when-then style integration tests
  - Type-safe DSL with `@DslMarker` annotation to prevent scope leakage
  - `GivenContext` for test data setup with automatic cleanup
  - `WhenContext` for executing actions under test
  - `ThenContext` with `result<T>()` helper for type-safe result access
  - Automatic tenant cleanup after each test to ensure isolation
- Tenant management UI on homepage
  - Homepage (`/`) displays list of tenants with name and creation date
  - Form to create new tenants directly on the page
  - Search functionality to filter tenants by name
  - Each tenant links to their templates at `/tenants/{id}/templates`
  - Templates list shows breadcrumb navigation back to tenants
  - All template URLs are now tenant-scoped: `/tenants/{tenantId}/templates`
  - API endpoints are tenant-scoped: `/api/tenants/{tenantId}/templates/{id}`
- Multi-tenancy support for tenant isolation
  - New `Tenant` entity with `id`, `name`, and `createdAt` fields
  - `CreateTenant` and `DeleteTenant` commands for tenant management (internal use)
  - All `DocumentTemplate` records now belong to a tenant (required foreign key)
  - Tenant-scoped queries and commands prevent cross-tenant data access
  - Database migrations create `tenants` table and add `tenant_id` to `document_templates`
  - Default tenant (id=1) created for existing data during migration
  - Cascade delete: removing a tenant deletes all its templates
- Editor development modes for live reload
  - **Standalone mode** (`npm run dev`): Full Vite HMR for developing the editor in isolation
  - **Integrated mode** (`npm run watch`): Continuous builds for embedding in Thymeleaf pages
  - Editor module includes Kotlin/Spring auto-configuration
  - Vite watch auto-starts when `epistola.editor.dev-server.auto-start=true`
  - Spring Boot serves editor files from filesystem in `local` profile for fast iteration
  - Development workflow: run Spring Boot with `local` profile, editor rebuilds on file changes
- Editor component integration with Thymeleaf templates
    - Library build mode for the React editor (`mountEditor()` API)
    - Editor can be embedded in Thymeleaf pages while sharing the app layout
    - New `/templates/{id}/editor` route to open the visual template editor
    - REST API endpoints for saving template content (`PUT /api/templates/{id}`)
    - CSS isolation to prevent style conflicts between editor and parent page
    - Edit links added to the templates list page
- HTMX utilities for WebMvc.fn functional endpoints
  - `ServerRequest.isHtmx` extension property for detecting HTMX requests
  - `ServerRequest.render()` helper for HTMX-aware template rendering
  - `ServerRequest.htmx { }` Kotlin DSL for advanced responses:
    - Multiple fragments with Out-of-Band (OOB) swaps
    - Conditional rendering logic
    - Response headers: `trigger()`, `pushUrl()`, `reswap()`, `retarget()`
    - Non-HTMX fallback with `onNonHtmx { }`
  - `HxSwap` enum for all HTMX swap modes
  - Additional extensions: `htmxTrigger`, `htmxTarget`, `htmxBoosted`, etc.
- Automatic PR labeling based on changed files
  - Labels PRs with `backend`, `frontend`, `infrastructure`, or `documentation` based on file paths
  - Uses `actions/labeler@v5` with sync-labels to keep labels up-to-date
- Command-based architecture with JDBI for database access
  - Vertical slices: organize by feature (commands/, queries/) not by layer
  - JDBI 3.49.0 with Kotlin and Postgres plugins
  - Query handlers pattern for read operations
  - CreateDocumentTemplate command with handler for write operations
- Document Templates page with Thymeleaf rendering
  - New `/templates` endpoint displaying a table of document templates
  - Form to create new templates directly on the page (POST /templates)
  - Uses Spring WebMvc.fn functional endpoints (RouterFunction + Handler pattern)
  - Basic security configuration permitting public access to templates page
  - CSS styling for table and form components
  - Flyway migration for document_templates table

### Changed
- Migrated version management from asdf to mise for faster tool installation

- Updated documentation to reflect server-side rendering architecture (Thymeleaf + HTMX) instead of implying a Vite/TypeScript SPA frontend
  - CLAUDE.md: Added Frontend Architecture section, clarified project overview and structure
  - README.md: Added Architecture section, updated project description and modules
  - CONTRIBUTING.md: Added Thymeleaf + HTMX code style section, updated frontend label description
  - .github/labels.yml: Updated frontend label description

### Fixed
- Docker image now uses JDK 25 to match the build environment, fixing Kotlin reflection errors at runtime
- Labels sync workflow now works with private repositories by adding `contents: read` permission
- Docker image build in CI now explicitly sets image name to `epistola-suite` via `--imageName` flag to ensure consistent naming across build and push steps
- Helm chart security scan failures (AVD-KSV-0014, AVD-KSV-0118): added proper pod and container security contexts with `readOnlyRootFilesystem`, `seccompProfile`, `allowPrivilegeEscalation: false`, and capability drops

### Added
- Test coverage reporting using Kover with dynamic badge
  - Generates coverage reports via `gradle koverXmlReport`
  - Coverage badge updated automatically on main branch builds
  - Badge displayed in README alongside build and security badges
- Scheduled security scan workflow (daily) with dynamic vulnerability badge
  - Runs Trivy scans on SBOMs daily at 6 AM UTC
  - Updates badge in `.github/badges/trivy.json` showing vulnerability count
  - Automatically creates GitHub issue when critical vulnerabilities are detected
- README badges for CI build status, security scan, and AGPL-3.0 license
- Manual trigger for Helm chart workflow via `workflow_dispatch` with optional `force_release` input
- Docker image signing with Cosign (keyless OIDC)
  - All published images are cryptographically signed using Sigstore
  - SBOM attestation attached to images using CycloneDX format
  - Verify signatures: `cosign verify ghcr.io/epistola-app/epistola-suite:<tag> --certificate-identity-regexp='.*' --certificate-oidc-issuer='https://token.actions.githubusercontent.com'`
  - Verify SBOM attestation: `cosign verify-attestation ghcr.io/epistola-app/epistola-suite:<tag> --type cyclonedx --certificate-identity-regexp='.*' --certificate-oidc-issuer='https://token.actions.githubusercontent.com'`
- Helm chart for Kubernetes deployment
  - Published to OCI registry at `oci://ghcr.io/epistola-app/charts/epistola`
  - Separate versioning from the application using `chart-X.Y.Z` tags
  - Automatic version bumping based on conventional commits for changes in `charts/` directory
  - Includes: Deployment, Service, Ingress (optional), HPA (optional), ServiceAccount, ConfigMap
  - Trivy security scanning for Kubernetes misconfigurations
  - Spring Boot Actuator health probes (liveness/readiness) configured
- SBOM (Software Bill of Materials) generation using CycloneDX
  - Backend SBOM: `gradle :apps:epistola:generateSbom`
  - Frontend SBOM: `pnpm --filter @epistola/editor sbom`
  - Both SBOMs attached to GitHub Releases (`epistola-backend-{version}-sbom.json`, `epistola-editor-{version}-sbom.json`)
  - Backend SBOM embedded in Docker images at `META-INF/sbom/bom.json`
- Automatic vulnerability scanning using Trivy
  - Scans both backend and frontend SBOMs on every build
  - Fails build on critical vulnerabilities
  - Vulnerability reports uploaded as CI artifacts
- GitHub Releases are now created automatically with release notes and SBOM attachment
- Open source community infrastructure
  - CONTRIBUTING.md with development workflow, commit conventions, and code style guidelines
  - CODE_OF_CONDUCT.md based on Contributor Covenant v2.1
  - SECURITY.md with vulnerability reporting guidelines and 48-hour response SLA
  - GitHub issue templates (bug report, feature request, documentation)
  - Pull request template with checklist
  - Issue template config linking to GitHub Discussions for questions
  - Automated label management via GitHub Actions (`.github/labels.yml`)
  - Comprehensive GitHub documentation (`docs/github.md`) covering CI/CD, releases, labels, and workflows
  - CLAUDE.md with project-specific instructions for Claude Code AI assistant
  - Git hooks with commitlint for conventional commit validation
  - SSH commit signing auto-configuration in init script
- Initial project setup with Spring Boot 4.0.0 and Kotlin 2.3.0
- Multi-module Gradle structure with apps/epistola and modules/editor
- Vite-based editor module with TypeScript, embeddable in the Java application
- GitHub Actions CI/CD pipeline with build, test, and Docker image publishing to ghcr.io
- Automatic semantic versioning using github-tag-action based on conventional commits
- ktlint for consistent code style
- README with getting started guide using mise for version management
