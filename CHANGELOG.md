# Epistola Suite Changelog

## [Unreleased]

### Changed
- **PDF/A-2b compliance**: All generated PDFs now conform to PDF/A-2b (ISO 19005-2 Level B) for long-term archival. Fonts are embedded (Liberation Sans replaces non-embedded Helvetica), an sRGB ICC output intent is included, and XMP metadata is written automatically.
- **Document metadata**: Generated PDFs include title (from template name), author (from tenant name), and creator metadata. Preview PDFs include default creator metadata.
- **Test execution speed**: Optimized backend test infrastructure for parallel execution. Gradle parallel builds, JUnit 5 parallel class execution, per-class tenant namespacing for DB isolation, Testcontainers reuse with tmpfs, UNLOGGED tables in tests, HikariCP pool tuning, and JVM heap pre-sizing. Tests that need exclusive DB access are annotated with `@Isolated`.

### Changed
- **Bean-driven security architecture**: `SecurityConfig` and `LoginHandler` now detect authentication methods from bean presence (`UserDetailsService`, `ClientRegistrationRepository`) instead of checking profile names. Adding a new form-login profile only requires updating `LocalUserDetailsService`'s `@Profile` annotation.
- **`OAuth2UserProvisioningService`**: Replaced `@Profile("!local & !test")` with `@ConditionalOnBean(ClientRegistrationRepository::class)`. `AuthProvider` is now derived from the OAuth2 registration ID instead of a config property.
- **Simplified `AuthProperties`**: Removed `provider` and `registrationId` fields (redundant). Only `autoProvision` remains.
- **Removed `epistola.auth.provider` and `epistola.auth.registration-id`** from all YAML profile configs.

### Added
- **`AuthenticationSafetyValidator`**: Fails fast on startup if `local`/`demo` profiles are combined with `prod` (known passwords in production) or if no authentication mechanism is configured (silent 403s).
- **Excluded `UserDetailsServiceAutoConfiguration`**: Prevents Spring Boot from creating a default in-memory user when no profile is active, letting the safety validator catch the misconfiguration instead.

### Added
- **`demo` Spring profile**: New profile for K8s deployments that enables form-based login with in-memory users (`admin@local`/`admin`, `user@local`/`user`) without requiring Keycloak. Includes safe Flyway settings (no auto-clean) and demo data loading. Set `SPRING_PROFILES_ACTIVE=demo` in your deployment.
- **Auto-recover database on incompatible Flyway migrations**: When Flyway detects checksum mismatches or missing migrations (common during pre-production development), the database is automatically cleaned and recreated from scratch. DemoLoader re-populates demo data after recreation. Production profile explicitly disables this behavior for safety.
- **AI chat file attachments**: Users can attach PDF and DOCX files to AI chat messages via a paperclip button. Files appear as removable chips above the text input, are displayed as badges in sent messages, and are passed through to the transport layer. Validates file type (PDF/DOCX only) and size (max 10 MB). Sending with files-only (no text) is supported. Mock transport acknowledges uploaded files in its response.
- **AI chat plugin (frontend)**: First plugin built on the editor plugin architecture. Adds an "AI" sidebar tab with a chat interface for interacting with an AI assistant. Features streaming responses with blinking cursor, proposal cards (Apply/Reject for template modifications), and auto-scroll. Ships as a separate Vite entry point (`ai-plugin.js` 13 kB, `ai-plugin.css` 4 kB) loaded via dynamic import, keeping AI code out of the main editor bundle. Uses mock transport for development — real backend transport to be added later. Includes `AiChatService` (state machine), `applyProposal()` (command/replace modes), and 37 tests.
- **Editor plugin architecture**: The template editor now supports a plugin system for extending the UI with additional sidebar tabs, toolbar actions, and lifecycle hooks. Plugins implement the `EditorPlugin` interface and are passed via the `plugins` option in `mountEditor()`. The sidebar and toolbar render plugin contributions dynamically alongside built-in controls. See `docs/plugins.md` for the design document.

### Fixed
- **Generation job cancellation race condition**: Cancelling an in-progress generation job no longer gets overwritten by the worker completing the request. The executor now guards status updates with `AND status != 'CANCELLED'` to preserve cancellation.

### Added
- **Data table component** (`datatable`): Data-driven table that iterates over an array expression and generates rows dynamically. Columns are child nodes (`datatable-column`) with configurable headers, widths, per-column styling, and template body slots repeated per data item. Supports border style variants, optional header row, and custom item/index aliases. Editor canvas renders a CSS grid with header row and droppable template slots. PDF renderer combines loop iteration with iText table generation. Column management uses standard InsertNode/RemoveNode/MoveNode commands.
- **Static table component**: Full table support in the editor with CSS Grid rendering, row/column management, cell merging/unmerging via click+shift-click selection, per-column width controls, header rows, and border style options. Each cell is a slot that can contain any block type. Backend PDF renderer supports merged cells via iText rowSpan/colSpan.- **Data Contract Editor — Date field type**: Added `date` as a first-class schema field type. Renders as `<input type="date">` in the example form. Stored as `{ type: "string", format: "date" }` in JSON Schema. Validates ISO date format (YYYY-MM-DD). Auto-inferred from date-like strings when generating schema from examples.
- **Data Contract Editor — Test Data chip list**: Replaced the `<select>` dropdown with a horizontal chip list showing all examples at a glance. Each chip displays the example name and a validation badge (green checkmark or red error count). Active chip is highlighted in blue.
- **Data Contract Editor — Per-example undo/redo**: Example data edits now have undo/redo support via `SnapshotHistory<JsonObject>`. Each example gets its own history stack. Ctrl+Z/Ctrl+Shift+Z works on both Schema and Test Data tabs. History clears on save or example switch.
- **Data Contract Editor — Inline field validation**: Validation errors are now shown inline on each form field (red border + error text) instead of only as a top-level block. Collapsed groups with child errors show a red dot and red left border. A compact validation summary pill replaces the old warning block.
- **SnapshotHistory<T> generic utility**: Extracted a generic snapshot-based undo/redo stack from `SchemaCommandHistory` so the same pattern can be reused across the editor (currently used for both schema and example data editing).
- **Data Contract Editor — Undo/Redo**: Schema mutations now go through a command pattern with snapshot-based undo/redo. Ctrl+Z/Ctrl+Shift+Z keyboard shortcuts and toolbar buttons. History clears on save.
- **Data Contract Editor — Infinite nesting**: Schema fields can now be nested to arbitrary depth (previously limited to 2 levels). The `isNested: boolean` flag is replaced by a numeric `depth` parameter.

### Changed
- **Data Contract Editor — Unified save**: Schema and examples are now saved together with a single "Save" button in the tab bar, replacing the separate per-section save buttons. The save operation first persists the schema (with migration check), then batch-saves all examples.
- **Data Contract Editor — Command architecture**: Schema mutations are now expressed as `SchemaCommand` discriminated union types (`addField`, `deleteField`, `updateField`, `generateFromExample`), executed through pure tree operations. VisualSchema is the primary editing state; JSON Schema conversion only happens on load and save, eliminating redundant roundtrips and ID instability.

### Fixed
- **Data Contract Editor — Expand/collapse broken**: Object and array fields could not be expanded because `jsonSchemaToVisualSchema()` generated new random IDs on every render, causing expanded-field state to be lost. Field IDs are now deterministic, based on the field path (e.g., `field:name`, `field:address.street`).

### Changed
- **Data Contract Editor — Schema field alignment**: Switched schema field header and rows from flex to CSS grid for consistent column alignment across expand button, name, type, array-item-type, required, and action columns.
- **Data Contract Editor — Compact example form**: Redesigned the test data example form with a compact tree layout: labels and inputs are side-by-side on each row, objects/arrays use collapsible `<details>` (collapsed by default for deeply nested), and spacing is minimal.

### Added
- **Data Contract Editor**: Replaced the React-based schema-manager module with a Lit web component data contract editor integrated into `modules/editor/`. Features a visual schema field builder, schema-driven example form (auto-generated inputs from JSON Schema), and migration assistant. Produces `data-contract-editor.js` (53 kB) and `data-contract-editor.css` (30 kB).

### Removed
- **schema-manager module**: Deleted `modules/schema-manager/` entirely. All functionality has been ported to the Lit-based data contract editor in `modules/editor/`, eliminating the React dependency from the project.

- **Test profiles**: JUnit 5 tag-based test categorization with Gradle tasks (`unitTest`, `integrationTest`, `uiTest`) for running test categories independently
- **Duplicate ID error handling**: Creating entities with duplicate IDs now shows inline form errors instead of silently failing. Applies to tenants, environments, themes, templates, attributes, and variants.
- **Confirm dialog for delete operations**: Delete buttons on list pages (themes, environments, attributes) now open a confirm dialog that can display error messages when the operation fails (e.g., deleting a theme in use).
- **Global error safety net**: Unhandled 500 errors from HTMX requests now show a dismissible error banner at the top of the page.
- **Attribute edit dialog error handling**: Validation errors during attribute updates now re-render the form inside the dialog with the error message, instead of silently discarding the response.

### Changed
- **Handler standardization**: All UI handlers now extract `TenantId` as a typed value at method entry, use standardized error keys (`error` and `errors`), and follow consistent delete patterns. Removed duplicate DELETE route for variants.
- **Variant delete uses confirm dialog**: Variant delete buttons now use `openConfirmDialog()` (matching themes/environments/attributes) instead of `hx-confirm` with `hx-delete`. The confirm dialog now supports configurable swap mode via `data-confirm-swap`.
- **Variant delete error reporting**: Attempting to delete the default variant now shows an error message in the UI instead of silently doing nothing.
- **Editor: component extension hooks**: Decoupled table and columns components from generic editor infrastructure (canvas, inspector, palette, editor, sidebar) using `ComponentDefinition` extension hooks (`renderCanvas`, `renderInspector`, `onBeforeInsert`, `commandTypes`/`commandHandler`). Component-specific logic now lives entirely in each component's registration file, following the open-closed principle. Added generic component state store to `EditorEngine` for cross-component communication (replaces table cell selection prop threading).
- **Flyway migration restructuring**: Renumbered and consolidated migrations V1-V10 into V1-V8 for cleaner dependency ordering. Users are now created before tenants (enabling FK-based audit columns), themes use composite PK `(tenant_id, id)` consistent with all other tenant-scoped tables, and user audit columns (`created_by`, `last_modified_by`) are inlined into CREATE TABLE statements instead of added via ALTER TABLE. Old V9 (user audit fields) is eliminated. PostgreSQL domain types (`TENANT_ID`, `THEME_ID`, `TEMPLATE_ID`, `VARIANT_ID`, `ENVIRONMENT_ID`) replace raw VARCHAR declarations for slug-based identifiers, embedding the slug pattern CHECK constraint in a single place. Existing local databases require drop+recreate.
- **Pre-1.0 version capping in CI**: Breaking changes (`feat!:`, `BREAKING CHANGE:`) now bump the minor version instead of the major version, keeping releases below `1.0.0` while the project is pre-production. The `mathieudutour/github-tag-action` runs in dry-run mode and a custom script caps the calculated version. A safeguard fails the build if existing tags are >= 1.0.0.
- **Variants Card Grid**: Replaced the variants table with a responsive card grid. Each card shows title, slug, attribute badges, version status, and action buttons. Default variant is visually distinguished with blue tint and always sorted first.
  - **Attribute Filtering**: Filter bar with dropdowns for each attribute definition, allowing quick narrowing of variants. Client-side filtering for instant response. Filters persist across HTMX swaps.
- **Auto-Draft on Publish**: Publishing a draft version to an environment now automatically creates a new draft (copying the published content), so variants always have an editable version. This removes the need to manually create new drafts after deploying.
- **Template Detail Page Redesign**: Separated the template detail page into focused tabs — Variants (authoring), Deployments (environment matrix), Data Contract, and Settings
  - **Deployment Matrix**: New dedicated Deployments tab with environment x variant grid for managing deployments. Each cell is a select box that deploys on change or removes the activation when "Not deployed" is selected.
  - **Simplified Variants Tab**: Removed inline nested version tables. Variants now shown as cards with attributes, draft/published status, and action buttons. Version history moved to a dialog.
  - **Version History Dialog**: Click the clock icon on any variant to view, archive, and create draft versions in a dialog.
  - Environment publish/unpublish removed from version rows — use the Deployments tab instead.
- **Environment-Targeted Publishing**: "Publish to Environment" replaces the separate publish + activate workflow. Publishing now requires a target environment, freezing drafts and activating in a single action.
- **Archive Guard**: Archiving a version is now blocked if the version is still active in any environment. Remove it from all environments first.
- **DemoLoader Enhancements**: Demo tenant now includes staging/production environments, language attribute definitions, Dutch/English multi-variant templates, and published versions across environments.

### Fixed
- **Editor: subtree undo loss** — Undoing a container deletion now correctly restores all descendant nodes and slots, preventing dangling references
- **ListVariants missing columns** — Added `title` and `description` to the SELECT clause so variant list views display all fields
- **GetEditorContext unsafe JSONB cast** — Replaced `as? Map` cast with Jackson deserialization for `variant_attributes`, fixing silent data loss
- **Deployment matrix unstyled dropdowns** — Changed CSS class from `select select-sm` to `ep-select` from the design system; fixed SpEL `{}` syntax error
- **PDF preview blob URL memory leak** — Blob URLs from `URL.createObjectURL()` are now revoked after 60 seconds
- **DeleteAttributeDefinition missing validation** — Deletion now checks for variants referencing the attribute and throws `AttributeInUseException` instead of creating orphaned references
- **UpdateAttributeDefinition allowed values narrowing** — Narrowing allowed values now checks for existing variants using removed values and throws `AllowedValuesInUseException`
- **VariantResolver scoring tiebreaker** — Changed from `(optionalMatches * 10) + totalAttributes` to `(requiredMatches * 100) + (optionalMatches * 10)` so unrelated attributes no longer influence scoring
- **Unused templateId in queries** — Five queries (GetDraft, GetVersion, ListVersions, ListActivations, GetActiveVersion) now JOIN to `template_variants` to verify the variant belongs to the specified template
- **Missing API exception handlers** — Added handlers for `NoMatchingVariantException` (404), `AmbiguousVariantResolutionException` (409), `AttributeInUseException` (409), and `AllowedValuesInUseException` (409)
- **Missing tenantId in models** — Added `tenantId` to `TemplateVersion`, `VersionSummary`, and `EnvironmentActivation` data classes to match composite PK schema
- **LIKE pattern injection in template search** — Escaped `%`, `_`, and `\` metacharacters in search terms
- **Inconsistent getCsrfToken calls** — All calls in `detail.html` now use defensive `typeof` check
- **Duplicated createDefaultTemplateModel** — Extracted to shared `DefaultTemplateModel.kt`
- **Dead vendor resource handler** — Removed `/vendor/**` handler from `EditorDevConfig` (vendor module was deleted)
- **CSS z-index misleading fallback** — Removed incorrect fallback `40` from `var(--ep-z-sticky)` in `shell.css`
- **Unused variantThemeId in GenerationService** — Removed dead code
- **Editor deepFreeze disabled during tests** — Removed unnecessary `process.env.NODE_ENV` define from vite.config.ts which caused Vitest v4 to set `import.meta.env.PROD = true` during tests, disabling the immutability guard
- **Editor save not working** — `mountEditor` set the `onSave` callback after calling `initEngine`, but `initEngine` checks `this.onSave` to create the SaveService. Moved callback assignments before `initEngine` so save/autosave/Ctrl+S all work correctly

### Removed
- `SetActivation` command and REST API endpoint — replaced by the publish-to-environment action
- `PublishVersion` command — replaced by `PublishToEnvironment` which combines publish + activate

### Added
- **Playwright UI Testing**: Added Playwright Java test infrastructure with `BasePlaywrightTest` base class and `VariantCardUiTest` covering card grid rendering, attribute filtering, and HTMX interactions
- `PublishToEnvironment` command: single action that freezes draft content (if needed) and activates in target environment
- `VersionStillActiveException`: thrown when attempting to archive a version still active in environments
- UI: environment-targeted publish dropdown in version list, environment badges on published versions, unpublish-from-environment action
- **Tenant-Scoped Composite Primary Keys**: All tenant-owned entities (`document_templates`, `template_variants`, `environments`, `template_versions`, `environment_activations`) now use composite primary keys `(tenant_id, id)`, allowing different tenants to reuse the same slugs (e.g., both can have a template called "invoice")
  - `TemplateVariant` domain model now includes `tenantId`
  - Removed JOIN-based tenant isolation in favor of direct `tenant_id` columns on each table
  - Simplified many queries by eliminating multi-table JOINs that existed solely for tenant verification
  - Merged V12 (variant_attribute_definitions) and V13 (tags→attributes rename) into V3

### Added
- **Explicit Default Variant Flag**: Decoupled default variant from empty attributes with an explicit `is_default` boolean column
  - First variant created for a template is automatically the default
  - Default variant can now have any attributes (no longer requires empty `{}`)
  - Database enforces exactly one default per template via partial unique index
  - New `SetDefaultVariant` command to reassign the default
  - Deletion of the default variant is blocked (`DefaultVariantDeletionException`) — reassign default first
  - UI shows "Default" badge, "Set as default" star button for non-defaults, and disables delete for the default
  - REST API: new `POST .../set-default` endpoint, `isDefault` field on variant DTOs, 409 on default deletion
  - Variant resolver falls back to `is_default = true` instead of empty attributes
- **Variant Attribute System**: Structured attribute-based variant management
  - **Attribute Definitions Registry**: Tenant-scoped registry of allowed attribute keys and values (CRUD with UI)
  - **Attribute Validation**: Variant attributes are validated against the registry when creating or updating variants
  - **Attribute-based Variant Resolution**: Auto-select variants based on required/optional attribute criteria with scoring algorithm
    - Required attributes filter candidates, optional attributes score them
    - Scoring: `(requiredMatches * 100) + (optionalMatches * 10)` — most specific variant wins
    - Falls back to default variant (empty attributes) when no match found
    - Throws `AmbiguousVariantResolutionException` when multiple variants tie on score
  - **GenerateDocument/Batch support**: Both commands accept `variantSelectionCriteria` as an alternative to explicit `variantId`
  - Renamed `tags` to `attributes` throughout the entire codebase (model, commands, queries, handlers, templates, API)
- **Edit Variant Dialog**: Added ability to edit variant title and tags via a native `<dialog>` element
  - Edit button on each variant row fetches a pre-filled form via HTMX
  - Form submits via HTMX PATCH and refreshes the variants section on success
  - Extended `UpdateVariant` command to support title updates alongside tags
  - Reusable `.ep-dialog` CSS styles for native HTML dialogs
- **Template Detail Page Redesign: Tabs + Inline Expand/Collapse**: Reorganized the template detail page into a 3-tab layout (Variants, Data Contract, Settings) with inline version management
  - **Tab navigation**: Client-side CSS/JS tabs for Variants, Data Contract, and Settings — all content rendered on page load, no server round-trips for switching
  - **Inline variant versions**: Each variant row has an expand/collapse chevron that lazy-loads versions via HTMX on first click, with subsequent toggles showing/hiding without re-fetch
  - **Collapsible create form**: "New Variant" button toggles an inline form with Cancel support, replacing the always-visible form section
  - **Settings tab**: Groups template details (ID, created/modified dates), theme selection, and delete action into a dedicated settings area with a danger zone section
  - **Removed separate variant detail page**: Versions are now accessed inline; the `/variants/{variantId}` route has been removed
  - **New endpoint**: `GET /{id}/variants/{variantId}/versions` returns the versions fragment for lazy loading
  - **CSS additions**: Tab navigation, expand/collapse toggle, variant detail row, and danger zone styles
- **Page Redesign: Lists, Detail Pages, Create Forms & Dashboard**: Full redesign of all page content within the app shell using consistent design system patterns
  - **Tenant Dashboard**: Replaced nav card grid with stat cards showing counts (Templates, Themes, Environments, Load Tests) and quick action links
  - **List pages**: Consistent structure across Templates, Themes, Environments, and Load Tests with `page-header` (title + search + create button), `ep-table`, and `empty-state` components
  - **Separate create pages**: Extracted inline create forms to dedicated `/new` pages with breadcrumbs, card-wrapped forms, and proper validation error handling
  - **Detail page sections**: Template, Theme, Load Test, and Load Test Request detail pages now use `detail-section` card-wrapped sections with headers and description lists
  - **Load test metrics**: Results displayed as stat cards grid with all performance metrics; error summary in proper `ep-table`; progress bar in card section
  - **CSS additions**: New styles for `dashboard-stats`, `stat-card`, `quick-links`, `detail-section`, `description-list`, `create-form-card`, `page-actions`
- **Unified App Shell with Top Navigation Bar**: Replaced standalone page navigation with a persistent top nav bar for all tenant-scoped pages
  - **App shell layout**: New `layout/shell.html` master template with nav bar, content slot, and footer. All tenant-scoped pages now render within the shell.
  - **Navigation bar**: Persistent top bar with Epistola logo, section links (Templates, Themes, Environments, Load Tests), tenant switcher, and user menu. Active section highlighted based on URL.
  - **HTMX content swapping**: `hx-boost` on the shell body enables seamless SPA-like navigation between sections without full page reloads. Browser history, back/forward navigation work correctly.
  - **ShellModelInterceptor**: Automatically populates `activeNavSection` and `tenantName` for all tenant-scoped requests rendering through the shell.
  - **Responsive design**: Nav bar collapses to hamburger menu on mobile viewports.
  - **Standalone pages preserved**: Login, tenant list (landing), and template/theme editors retain their own full-page layouts.
- **Editor V2: Columns, Conditional & Loop editor components**: Rich editing UX for layout and logic components
  - **Columns layout editor**: Inspector shows column count (+/- buttons, min 1, max 6), per-column relative size inputs, and gap control. Canvas renders columns with dynamic flex sizing and gap from props. `AddColumnSlot` and `RemoveColumnSlot` commands with full undo/redo support including child subtree preservation.
  - **Reusable expression dialog**: Extracted the expression dialog from inline expression chips into a standalone `openExpressionDialog()` function, enabling reuse from both ProseMirror node views and inspector fields. Includes field path autocomplete with filtering, instant JSONata validation, debounced live preview, and quick reference panel.
  - **Inspector expression triggers**: Conditional and loop expression fields now use clickable trigger buttons (instead of plain text inputs) showing the current expression with valid/invalid/empty state indication. Click opens the full expression dialog with field paths and live preview. Loop expressions highlight array-type fields.
  - **Engine helpers**: `EditorEngine.fieldPaths` (cached lazy getter) and `EditorEngine.getExampleData()` centralize data model extraction logic previously duplicated across components.
  - **Data model alignment**: Columns `defaultProps` changed from `columns: [{size:1},{size:1}]` to `columnSizes: [1, 1]` to match the backend `ColumnsNodeRenderer` expectations. `createInitialSlots` hook added to `ComponentDefinition` for components whose slot count is derived from props.
- **Project overview documentation**: New `docs/epistola.md` providing a high-level orientation for developers and AI assistants — what Epistola is, use cases, implemented features, technology stack, and architecture overview
- **Theme Editor Redesign**: Replaced the Thymeleaf form-based theme editor with a Lit web component (`<epistola-theme-editor>`) that provides the same rich style editing controls as the template editor inspector
  - **Visual style inputs**: Color pickers, unit inputs (px/em/rem/pt), select dropdowns, spacing inputs — all driven by the shared `defaultStyleRegistry`
  - **Page settings editing**: Format (A4/Letter/Custom), orientation, margins (mm), background color
  - **Block style presets**: Expandable preset cards with label, key, applicableTo multi-select, and full visual style property editing per preset
  - **Unified autosave**: 2-second debounce after any change, Ctrl+S for manual save, dirty state tracking, beforeunload warning
  - **Minimal PATCH payloads**: Only changed fields are sent to the backend
  - **ThemeEditorState**: Pure TypeScript state management class with 32 unit tests
  - **Multi-entry Vite build**: Produces both `template-editor.js` and `theme-editor.js` bundles with shared code chunking
- **Editor V2: Enhanced expression dialog with live preview**: Expression editing dialog now provides real-time feedback and discovery tools
  - **Live preview**: Debounced (250ms) evaluation shows the expression result with green (success) or red (error) background, updates as you type
  - **Instant validation**: Synchronous JSONata parse check on every keystroke gives immediate green/red border feedback without waiting for async evaluation
  - **Searchable field paths**: Filter input next to "Available fields" narrows the field list with case-insensitive matching
  - **JSONata quick reference**: Collapsible `<details>` panel with 12 common patterns (path access, concatenation, conditionals, aggregations, string functions, array filtering). Clicking a pattern fills the input and triggers preview.
  - **New expression helpers**: `tryEvaluateExpression` (discriminated Result type), `formatForPreview` (human-readable for all value types including objects/arrays/null), `isValidExpression` (synchronous parse-only check)
- **Editor V2: Save + Autosave** (Phase 6): Automatic and manual save functionality for the template editor
  - **SaveService**: Pure TypeScript class managing save lifecycle with state machine (idle → dirty → saving → saved → idle). 3-second debounced autosave coalesces rapid changes, concurrent save prevention queues pending documents, saved state auto-transitions to idle after 2 seconds. 19 unit tests cover all transitions.
  - **Ctrl+S**: Keyboard shortcut for immediate save, bypasses autosave debounce
  - **Save button**: Toolbar button between undo/redo and preview with state-dependent rendering — idle (checkmark), dirty (save icon, enabled), saving (spinner animation), saved (green checkmark), error (red warning, click to retry with tooltip)
  - **Dirty tracking**: First keystroke immediately transitions to dirty state for instant UI feedback
  - **beforeunload warning**: Browser warns when closing/navigating away with unsaved changes
  - **EditorOptions.onSave**: Callback pattern where the host page owns the HTTP request and the editor owns autosave/debounce/state lifecycle
  - 2 new Lucide icons: check, triangle-alert
- **Editor V2: PDF preview panel** (Phase 5): Resizable panel next to the canvas that displays server-rendered PDF previews
  - **PreviewService**: Pure TypeScript class managing debounced fetch scheduling, AbortController for in-flight cancellation, blob URL lifecycle (creation/revocation), and a state machine (idle → loading → success/error). 12 unit tests cover all transitions.
  - **EpistolaPreview**: Lit component subscribing to `doc:change` and `example:change` events, rendering an iframe with blob URL on success, or loading/error/idle placeholder states with retry button
  - **EpistolaResizeHandle**: Pointer-event drag handle setting `--ep-preview-width` CSS variable on `.editor-main`, with min 200px / max 800px constraints, persisted to localStorage
  - **Toolbar toggle**: Eye/eye-off button in toolbar dispatches `toggle-preview` event, open/close state persisted to localStorage
  - **EditorOptions.onFetchPreview**: Callback pattern where the host page owns the HTTP request (CSRF, URL, format conversion) and the editor owns debounce/abort/blob lifecycle
  - **Stub callback**: `editor.html` wired with `onFetchPreview` that calls the backend preview endpoint — real PDF rendering depends on Phase 7 backend adaptation
  - 5 new Lucide icons: eye, eye-off, loader-2, alert-circle, refresh-cw
- **Editor V2: Resolved expression values in chips**: Expression chips now show the resolved value from the currently selected data example (e.g., "John Doe" instead of `{{customer.name}}`). Falls back to showing the raw expression when no data example is selected, the expression evaluates to undefined/null/empty, or the result is a non-displayable type (object/array). Uses JSONata for full expression evaluation support including aggregations, string concatenation, and conditionals. Hovering a resolved chip shows the expression path in a tooltip. Switching data examples refreshes all chips asynchronously with a generation counter to prevent stale results.

### Fixed
- **Preview PDF generation**: Fixed `ClassCastException` (LinkedHashMap cannot be cast to BlockStylePreset) when previewing templates with a theme that has block style presets. Root cause was Java type erasure in JDBI's `@Json` deserialization of `Map<String, BlockStylePreset>`. Introduced `BlockStylePresets` wrapper type with explicit Jackson serializers, following the same pattern as `DataExamples`.

### Changed (Breaking)
- **Block style presets format**: Changed `blockStylePresets` from flat `Map<String, Map<String, Any>>` to typed `Map<String, BlockStylePreset>` where `BlockStylePreset` has `{label, styles, applicableTo}` fields. This aligns the Kotlin backend with the TypeScript template-model type. Existing preset data in the database needs to be migrated to the new nested structure.
- **Complete editor rewrite from v1 to v2**: Replaced the entire editor stack (React + TipTap + Zustand → Lit + ProseMirror + headless engine) and data model (flat `blocks[]` → normalized node/slot graph). This is a full-stack change:
  - **Data model**: `TemplateModel` replaced by `TemplateDocument` with `nodes: Map<String, Node>` and `slots: Map<String, Slot>`. All domain commands, queries, services, and REST API updated.
  - **PDF generation**: All `BlockRenderer` types replaced by `NodeRenderer` types with `renderNode()`/`renderSlot()` traversal.
  - **Editor**: Thymeleaf page now loads the Lit/ProseMirror editor (`/editor/template-editor.js`). Import map removed.
  - **Modules deleted**: `modules/editor` (v1 React), `modules/vendor` (React/Radix/Zustand bundles)
  - **Schema-manager**: Now bundles all dependencies directly (no import map dependency)
  - **V1 model types removed**: All `Block`, `TextBlock`, `ContainerBlock`, etc. types deleted from `template-model` module

### Changed
- **Editor V2: Unified Change interface for undo/redo**: Extracted all undo/redo logic from EditorEngine into self-contained Change classes (`CommandChange`, `TextChange`). Engine's `undo()` and `redo()` are now type-agnostic two-liners that delegate to `Change.undoStep()`/`redoStep()`. Eliminates ~60 lines of type-branching code from EditorEngine. `TextChangeEntry` interface replaced by `TextChange` class. `UndoEntry` union type replaced by `Change` interface.
- **Editor V2: PM state preservation across block deletion**: When a text block is deleted, its ProseMirror EditorState is cached by the engine. If the user undoes the deletion, the cached state is restored — preserving character-level undo history. TextChange entries are revived with fresh ops when the PM view reconnects. Content divergence check prevents stale state restoration.

### Changed
- **Editor V2: Merged palette, tree, and inspector into tabbed sidebar**: Combined the three separate tool panels (palette, tree, inspector) into a single left-side `<epistola-sidebar>` component with tabs (Blocks, Structure, Inspector/Document). This reduces horizontal space usage and simplifies the layout from `palette | tree | canvas | inspector` to `sidebar | canvas`. Inspector tab label dynamically shows "Document" when no node is selected. Only the active panel is rendered in the DOM, ensuring proper DnD lifecycle management.

### Changed
- **Comprehensive UI redesign with shadcn/ui-inspired design system**: Unified visual language across the entire application (main app and editor-v2) targeting modern, polished aesthetics
  - **Design system**: Enhanced tokens with complete color palettes (blue 50-900, amber, purple, green), semantic aliases, 5-level shadows, ring-based focus system, transition tokens, Inter font
  - **Base resets**: Antialiased rendering, global focus-visible ring, improved heading/link/paragraph defaults
  - **Component classes**: Full library of CSS components — buttons (primary/secondary/outline/ghost/destructive), cards, badges, form controls, tables, alerts, icons, layout helpers
  - **Lucide icons**: SVG sprite with 31 icons for Thymeleaf templates, inline SVG helper for Lit components
  - **Main app styles**: Complete rewrite using design tokens — wider max-width, ring-based focus, card-like tables, sticky footer, backdrop-filter dialogs, navigation cards with icon circles
  - **Template updates**: Removed all inline style blocks, replaced emoji with Lucide SVG icons, applied component classes across all 15 templates
  - **Editor-v2 layout**: Figma-like panel dividers using 1px background gap trick, subtle toolbar shadow with separator dividers
  - **Editor-v2 panels**: Uppercase category labels in palette, icon circles with hover lift, tree selected state with inset accent border, gradient block headers, shadow-md page rendering
  - **Editor-v2 rich text**: Expression chips with pill radius and shadow, bubble menu with shadow-lg, expression dialog with backdrop blur
  - **Editor-v2 icons**: Undo/redo toolbar buttons, palette block type icons, tree node type icons — all using inline Lucide SVGs

### Changed
- **Editor V2: TextChange as first-class undo entry**: Replaced the `UndoHandler` strategy pattern with `TextChangeEntry` on the engine's undo stack. Text editing sessions now delegate undo/redo to ProseMirror's native history using `undoDepth()` as session boundaries. Character-level undo works even after blurring the text block (PM history persists). Snapshot fallback handles destroyed PM views. Removes focus/blur handler registration, `_hasPendingFlush`/`_isSyncing` guards, coalesced undo entries, and the `beforeinput` interception for strategy-based double-undo prevention.

### Changed
- **Editor V2: EventEmitter, debounce, and dual undo stack**:
  - **Typed EventEmitter** (`engine/events.ts`): Replaced 3 ad-hoc listener Sets in `EditorEngine` with a single typed `EventEmitter<EngineEvents>` supporting `doc:change`, `selection:change`, and `example:change` events. Old `subscribe()`, `onSelectionChange()`, and `onExampleChange()` methods are preserved as deprecated wrappers for backward compatibility.
  - **Conditional index rebuild**: Added `structureChanged` flag to `CommandOk`. Structural commands (InsertNode, RemoveNode, MoveNode) trigger index rebuild; property/style commands skip it for better keystroke performance.
  - **skipUndo + pushUndoEntry**: `dispatch()` accepts `{ skipUndo: true }` to skip undo recording. New `pushUndoEntry()` allows external components to push coalesced undo entries.
  - **Debounced text editing**: `EpistolaTextEditor` now debounces ProseMirror content dispatches (300ms). On blur/deselect, flushes pending content and pushes a single coalesced undo entry restoring the content-before-editing snapshot. ProseMirror handles character-level undo natively; engine undo reverts entire editing sessions.
  - **Toolbar undo state sync**: Toolbar subscribes to `doc:change` to keep undo/redo button state in sync without manual `requestUpdate()` after each action.
  - UI components (`EpistolaEditor`, `EpistolaToolbar`) migrated to new `events.on(...)` API.

### Added
- **Editor V2: Rich text editing with ProseMirror** (Phase 4):
  - **ProseMirror integration**: Direct ProseMirror (no TipTap wrapper) for rich text editing in text blocks, with full JSON compatibility with the existing TipTap-based backend converter
  - **Inline formatting**: Bold, italic, underline, strikethrough marks with keyboard shortcuts (Ctrl+B/I/U)
  - **Block types**: Paragraphs, headings (H1-H3), bullet lists, ordered lists
  - **Expression chips**: Inline `{{expression}}` nodes rendered as styled pills; type `{{` to insert, click to edit
  - **Expression editor dialog**: Native `<dialog>` with text input and field path autocomplete from JSON Schema data model
  - **Floating bubble menu**: Selection-based toolbar with formatting buttons, heading toggles, list wrapping, and expression insertion; positioned with `@floating-ui/dom`
  - **Heading input rules**: `# `, `## `, `### ` at line start auto-converts to headings
  - **Schema field path extractor** (`engine/schema-paths.ts`): Walks JSON Schema properties recursively to extract dot-notation field paths for expression autocomplete
  - **Data model support**: `EditorOptions` accepts `dataModel` and `dataExamples`; `EditorEngine` stores and exposes them for expression UI
  - **Content sync**: Bidirectional sync between ProseMirror state and EditorEngine using `isSyncing` flag + JSON equality check to prevent loops
  - **EpistolaTextEditor Lit component**: Light DOM component wrapping ProseMirror with lifecycle management, external content sync (engine undo), and resolved style passthrough
  - **28 new tests** for ProseMirror schema (roundtrip, marks, lists), input rules (regex matching, handler), and schema-paths (flat, nested, arrays, depth limit)

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
