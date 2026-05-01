# Epistola Suite Changelog

> **Note:** Helm chart changes are tracked separately in [`charts/epistola/CHANGELOG.md`](charts/epistola/CHANGELOG.md).

## [Unreleased]

### Changed

- **Removed `px` unit support**: `StyleApplicator.parseSize` no longer recognizes `px` and the editor's spacing input no longer converts `px` values. Templates created before the `sp`/`pt` switch that still hold `Npx` values for margin/padding will not render those margins in the PDF, and the inspector will show the numeric portion in the fallback unit (`pt`) without scaling. Re-enter the value in `sp` or `pt` to fix.
- **Default text and table-cell spacing zeroed (retroactive V1 change)**: Three `RenderingDefaults.V1` values that produced visible whitespace authors couldn't see in the inspector are now `0`: `componentSpacing["text"].marginBottom` (`1.5sp` → `0sp`), `paragraphMarginBottom` (`6pt` → `0pt`), and `tableCellPadding` (`8pt` → `0pt`). Because `RenderingDefaults` is versioned and published `template_versions` store `rendering_defaults_version`, this change applies retroactively to **all** existing published versions that resolved to V1 — they will now render with the new defaults. Pre-production project, so no V2 is introduced; set `marginTop`/`marginBottom`/`padding` explicitly via the inspector to restore previous spacing where needed.

### Fixed

- **Renovate toolchain group now actually wins**: Reordered `renovate.json` package rules so the toolchain rule (Kotlin, Gradle, Kover, Java, Node, pnpm) is evaluated after the catch-all minor/patch rule. Renovate applies later matching rules on top of earlier ones, so the previous order let the non-major rule override the toolchain group, leaking toolchain bumps into the "all non-major dependencies" PR. Also added `java` to the toolchain matchers so mise Java bumps are grouped with the rest.
- **Editor build with Vite 8.0.9+**: Switched minifier from `esbuild` to the built-in default (`oxc`). Vite 8 no longer bundles esbuild, so the explicit `minify: 'esbuild'` setting caused build failures.
- **Editor canvas didn't show `sp` margins**: Spacing values like `2sp` were emitted as raw CSS, which browsers don't understand, so the editor preview silently ignored them. The canvas now rewrites `Nsp` tokens to absolute `pt` so the preview matches the PDF output.
- **Inspector dropdown could go out of sync with stored unit**: If a node's stored margin/padding used a unit no longer offered by the inspector, `currentUnit` could fall outside the dropdown options and any new value would be saved with the unsupported unit. The dropdown is now clamped to one of the offered options.
- **Import always creates contract version**: Templates imported without a data model or data examples now always get a contract version (previously skipped, causing NPE when saving). Backfill migration (V24) creates missing contract versions for existing templates.
- **Export only includes published template versions**: Catalog export now only includes templates with published versions, skipping draft-only templates.
- **Clear error on missing contract version**: `UpdateDraft`, `CreateVariant`, and `CreateVersion` now throw a descriptive `IllegalStateException` instead of a raw `NullPointerException` when no contract version exists.
- **Publishing subscribed catalog resources to environments**: Removed incorrect read-only catalog check from `PublishToEnvironment` and `PublishVersion` (for already-published versions). Environment activations are tenant-scoped operations, not catalog modifications.
- **Header/footer style rendering in PDF**: Page header/footer event handlers now apply node-level styles by wrapping rendered slot content in a styled `Div`, restoring expected borders, background, and padding in generated PDFs.
- **Expression editor discoverability**: Added a subtle inline hint in text block headers (`type {{ for expressions`) so users can discover inline expression insertion without leaving the editor flow.
- **Negative sizing enforcement**: Editor size inputs now prevent negative values and clamp on change; backend parsers (`StyleApplicator.parseSize`, `ImageNodeRenderer.parseToPt`) also clamp to `>= 0` to prevent malformed layout and iText failures when invalid values slip through.

## [0.17.0] - 2026-04-28

### Added

- **Contract schema versioning**: Template data contracts (schema + examples) are now versioned separately from the template's visual content. See `docs/schema-versioning.md` for the full design.
  - `contract_versions` table with draft/published lifecycle (V23 migration)
  - Each template version links to a specific contract version via FK
  - On-demand draft pattern: drafts only exist when there are unpublished changes
  - Compatible contract changes auto-publish when deploying template versions
  - Breaking changes require explicit publish with two-step confirmation
- **Schema compatibility checking**: Three-level checking — structural schema diff, template field usage extraction, and per-version compatibility analysis. Only template versions that actually reference removed/changed fields are flagged as incompatible.
- **Referenced paths extraction**: `TemplatePathExtractor` extracts all data contract variable paths from template expressions (including loop alias resolution, nested loops, conditionals, QR codes, and TipTap inline expressions). Stored on each template version as `referenced_paths`.
- **Data contract tab view/edit mode**: View mode shows schema fields table and example names in read-only format. Edit mode mounts the full Lit editor. Status bar adapts to each mode with version badge, publish/edit buttons, usage dialog, and history dialog.
- **Contract publish impact preview**: Breaking contract publishes show a confirmation dialog listing breaking changes and affected template versions with their active environment deployments.
- **Deployment matrix error handling**: Contract compatibility errors shown inline in the deployment matrix instead of generic "An error occurred" message.
- **Contract usage overview**: Dialog showing all template versions with their contract version (color-coded: green=current, amber=outdated) and active deployments.

### Changed

- **Renovate PR consolidation**: GitHub Actions updates (digests + majors) grouped into a single PR; major dependency updates merged into the non-major PR; lock file maintenance merged into the non-major PR.
- **Contract data moved from `document_templates` to `contract_versions`**: Removed `schema`, `dataModel`, `dataExamples` columns. All queries, handlers, and catalog import/export updated.
- **Contract code organized into `templates/contracts/` package**: Dedicated package with `commands/`, `queries/`, `model/` sub-packages plus `SchemaCompatibilityChecker` and `SchemaPathNavigator` utilities.
- **Border input UX**: The per-side border input now shows the style dropdown (None/Solid/Dashed/Dotted) on top and the width/unit/color controls below. Width/unit/color are hidden when style is `None`. New borders default to `None` instead of `Solid`, and the width is clamped to at least 0.5 in the active unit whenever a visible style is chosen.
- **Table borders via CSS only**: Removed the `borderStyle`/`borderColor`/`borderWidth` props on the `table` component and the "Border Style" inspector dropdown. Borders are now set exclusively via the CSS `border` property — on the node for the outer table border, or on selected cells for per-cell borders. Default cell borders are suppressed; new tables render borderless until the user opts in.
- **Table inspector scoping**: Selecting a cell shows only cell-specific controls (merge actions and cell style). Selecting the table without a cell shows only table-level controls (rows, columns, widths, header rows, and node styles). Previously both sets were shown at once, which caused confusion about which properties applied where.

### Fixed

- **Theme cascade catalog key**: `ThemeStyleResolver` now passes `defaultThemeCatalogKey` through the cascade, preventing "Expected zero to one elements" errors when the same theme ID exists in multiple catalogs.

### Added

- **SVG and WEBP image support in PDF**: The PDF renderer now supports SVG (via iText SVG converter) and WEBP (via TwelveMonkeys ImageIO) image formats in addition to PNG and JPEG.
- **Render mode (STRICT/PREVIEW)**: New `RenderMode` controls error handling during PDF generation. STRICT mode fails fast on corrupt assets (for production renders), PREVIEW mode renders a visible error placeholder (for editor previews). Live cascade renders default to PREVIEW; snapshot renders default to STRICT.

### Changed

- **SVG converter decoupled from RenderContext**: SVG image conversion is now injected into `ImageNodeRenderer` via a `SvgImageConverter` fun interface, keeping `RenderContext` library-agnostic for future non-iText PDF renderers.

## [0.16.0] - 2026-04-23

### Added

- **Catalog resource usage**: The catalog browse page shows per-resource usage counts from other catalogs. Clicking a count opens a dialog listing which templates reference that resource.
- **`UpgradeCatalog` command**: Upgrades a subscribed catalog in place — re-fetches the manifest, updates metadata and version, upgrades previously installed resources, and removes installed resources no longer in the manifest. Replaces the old unregister/re-register approach which failed when other templates referenced the catalog's themes.
- **Catalog theme references**: Templates imported from catalogs now carry a `themeId` that links them to a theme in the same catalog. Previously imported templates always had `theme_key = NULL`, requiring themeRef overrides in the templateModel. The theme reference is set on import, included in exports, and updated on reimport.

### Changed

- **Data contract editor: single-page layout**: Replaced the Schema/Test Data tab layout with a single scrollable page showing both sections. The Visual/JSON sub-tabs are replaced with a collapsible "View JSON" panel. Breaking changes display as a live banner between the header and schema section.
- **Data contract editor: block save on invalid examples**: The Save button is now disabled when any example has validation errors. Examples must be valid before saving.
- **Demo templates**: Replaced `themeRef` overrides (which lock the theme at the variant level) with `themeId` at the resource level, allowing the theme to be changed from the template settings page.
- **Style inheritance in PDF**: Inheritable styles (font family, size, weight, color, line height, letter spacing, text align) now cascade from parent nodes to children in generated PDFs, matching the browser behavior in the editor. Previously each node resolved styles only from the document level, ignoring parent overrides.
- **Editor unit input clearing**: Setting a unit-type style value (font size, letter spacing, border radius) to 0 now removes the property instead of storing an explicit "0pt", allowing inheritance from the parent or document level.

### Fixed

- **Image aspect ratio lock**: Changing width or height with "Lock Aspect Ratio" enabled now correctly adjusts the other dimension. Previously the ratio calculation always evaluated to 1 because the new value was applied before the `onPropChange` hook could read the old value.
- **Data contract: save fails after field rename**: Renaming a schema property now automatically renames the key in all example data, and schema + examples are saved together so the backend validates consistently. Previously the stale key caused backend validation to reject the save.
- **Data contract: property name validation**: Field names are now restricted to valid identifiers (letters, digits, underscores). Names with dashes or special characters broke JSONata expressions in the template editor. Validation is enforced in the editor UI, backend API, and catalog import.
- **Data contract: unknown field detection**: The migration dialog now detects and offers to remove example data keys that are not defined in the schema.
- **Data contract: breaking change review**: A confirmation dialog now shows before saving when schema changes include field renames, deletions, or type changes. Uses field IDs to distinguish renames from add/delete pairs, ensuring the user is aware of the impact on external systems.
- **Bold, italic, and strikethrough in PDF**: Text marks now render correctly in generated PDFs. The PDF converter expected TipTap mark names (`bold`, `italic`, `strike`) but ProseMirror uses `strong`, `em`, and `strikethrough`.
- **Stencil creation**: Fixed crash when saving a newly created stencil. The initial draft version was stored with an empty JSON object (`{}`), which failed deserialization because `TemplateDocument` requires a `root` node. Now stores a valid minimal document with an empty root node.
- **API docs**: Fixed 404 on `/api-docs/epistola-contract.yaml` — the resource handler used an exact path instead of a wildcard pattern, preventing Spring from resolving the OpenAPI spec from the classpath.
- **Catalog export**: Cross-catalog theme dependencies from resource-level `themeId` are now included in the exported `catalog.json` dependencies list. Previously only `ThemeRefOverride` inside template models was scanned.
- **Catalog ZIP import**: Template `themeId` and `themeCatalogKey` are now preserved when importing from a ZIP archive. Previously these fields were silently dropped.
- **Catalog export assets**: Cross-catalog asset references are now listed as dependencies in the exported `catalog.json`, consistent with how themes and stencils are handled.
- **Asset catalog scoping**: Assets are now properly catalog-scoped. The `catalogKey` parameter is required on `UploadAsset` and `ImportAsset` (no more DEFAULT fallback). The template editor passes the current catalog when listing and uploading assets.
- **Catalog import dialog**: Import errors now display inline in the dialog instead of replacing the dialog content with the full catalog list page.
- **Catalog delete dialog**: Delete errors (e.g. "catalog in use") now display in the confirm dialog instead of silently failing. Returns 422 with JSON error for HTMX requests.
- **Address block positioning**: Replaced `left` prop with `align` (`left`/`right`) and `sideDistance` (distance from the aligned page edge). The `standard` select now acts as a preset that populates these properties. Previously, right-window positioning required manually computing the left offset from the page edge.

## [0.15.0] - 2026-04-20

### Added

- **Custom error pages**: Added styled error pages for 404, 500, and a generic fallback, replacing the Spring Boot whitelabel error page. Stacktraces are no longer exposed.
- **Clean stacktraces in logs**: Added a custom Logback converter that collapses framework frames (Spring, Tomcat, reflection, etc.) in stacktraces, keeping only application-relevant frames for readability.
- **UI exception filter**: Replaced `AuthorizationExceptionFilter` with a generic `UiExceptionFilter` that catches all UI request exceptions, maps known domain exceptions to appropriate HTTP status codes, and returns a generic 500 for unknown errors — preventing Tomcat from rendering raw stacktraces.
- **Per-side border controls**: The editor now supports setting border width, style, and color independently per side (top, right, bottom, left). Replaces the previous all-or-nothing border controls. Backwards compatible with existing unified border styles.
- **Separator component**: New horizontal rule block type for visually separating sections in templates. Renders as a styled line with configurable border and margin.
- **Line height in PDF**: The `lineHeight` style property now renders correctly in generated PDFs. Resolved styles are passed to `TipTapConverter` via a single map, enabling future style properties without parameter changes.
- **Subscript and superscript**: New text formatting marks for m², footnote markers, and legal references. Available in the bubble menu toolbar and rendered in PDF output.
- **Keep-together / keep-with-next**: New page flow style properties that prevent page breaks from splitting a block or separating it from the next block. Available as checkboxes in the inspector's Page Flow section.
- **Widow/orphan control**: PDF paragraphs and headings now enforce a minimum of 2 lines at the top and bottom of each page, preventing single isolated lines.
- **List numbering formats**: Ordered lists now support decimal, lower/upper alpha (a,b,c / A,B,C), and lower/upper roman (i,ii,iii / I,II,III) numbering. Toggle format via the # button in the bubble menu. Custom start numbers are also supported.
- **First-page header/footer variation**: Page headers and footers now have a "Hide on first page" checkbox. When enabled, the header/footer is not rendered on the first page of the PDF.
- **Data list component**: New block that loops over a data expression and renders items as a formatted list (bullet, numbered, alpha, roman, or no marker). Combines the iteration of loop blocks with proper list formatting.
- **Table cell styling**: Per-cell background color, text alignment, and padding via the inspector when a cell is selected. Table border color and width are now overridable from props instead of hardcoded.
- **Address block**: Two-part envelope window component. Address content renders at absolute page coordinates (exact mm from page edge, regardless of margins). Aside content renders in normal flow beside the address. DIN C5/6 left/right presets. Nestable in stencils/containers.

### Fixed

- **Page margins**: Margins are now correctly converted from mm to points before passing to iText. Previously mm values were treated as points, making margins ~2.8x smaller than intended.

### Fixed

- **Read-only catalog enforcement for version commands**: The `UpdateDraft`, `CreateVersion`, `UpdateVersion`, `PublishToEnvironment`, and `ArchiveVersion` command handlers now check `requireCatalogEditable` before executing, preventing modifications to templates in subscribed (read-only) catalogs.
- **Document generation passes catalogId**: The REST API mappers (`DocumentDtoMappers`) now correctly pass `catalogId` from API requests to the `GenerateDocument`, `BatchGenerationItem`, and `PreviewDocument` commands. Previously these defaulted to `CatalogKey.DEFAULT`, causing `DEFAULT_VARIANT_NOT_FOUND` errors when generating documents for templates in non-default catalogs.
- **Version fallback when no environment specified**: Document generation and preview no longer require an explicit `versionId` or `environmentId`. When neither is provided, the latest published version is used automatically. This fixes failures for catalog-imported templates that have published versions but no environment activations.
- **Editor loads published versions when no draft exists**: The template editor now falls back to the latest published version when no draft exists for a variant. When the user saves, a new draft is created automatically. This supports catalog-imported templates that only have published versions.

### Changed

- **Catalog import creates published versions**: Imported templates now get published versions instead of drafts, making catalogs ready to use immediately without manual publishing.
- **Removed template import endpoint**: The `POST /templates/import` endpoint (superseded by catalog import) has been removed. The `importTemplates` override was already a throwing stub.
- **ProtocolMapper centralizes type conversions**: Extracted all inline conversions between protocol `Map<String, Any?>` types and suite internal types (ObjectNode, DocumentStyles, BlockStylePresets) into a new `ProtocolMapper` component. Cleaned up scattered conversion logic in `ImportCatalogZip`, `InstallFromCatalog`.

### Added

- **Export catalog as ZIP**: All catalogs (authored and subscribed) can be exported as self-contained ZIP archives from the Catalogs page. The ZIP contains the catalog manifest, all resource detail files (templates, themes, stencils, attributes, assets), and asset binary content with proper file extensions. Exports all resources in the catalog, not just template dependencies.
- **Import catalog from ZIP**: Upload a ZIP archive to create or update a catalog. User chooses whether the imported catalog should be authored (editable) or subscribed (read-only). If a catalog with the same slug already exists and is authored, resources are updated in place. Subscribed catalogs cannot be overwritten.
- **Dedicated asset upload page**: Assets are now uploaded via a dedicated `/assets/new` page with an explicit catalog selector, replacing the inline drag-drop zone. Catalog is always explicitly chosen.
- **Delete authored catalogs**: Authored catalogs (except the default) can now be deleted with a confirmation dialog warning about resource deletion. Subscribed catalogs retain the existing remove functionality.
- **Cross-catalog deletion protection**: Deleting a catalog now checks for cross-catalog references (themes, stencils, assets used by templates in other catalogs) and prevents deletion with a descriptive error listing the affected templates.
- **Global closeDialog event**: HTMX responses can trigger `closeDialog` via `HX-Trigger` header to close any open dialog. Replaces CSP-incompatible `hx-on::after-request` attributes.
- **Theme editor read-only mode**: The theme editor now supports an optional `readonly` flag. When enabled, all inputs are disabled, autosave is suppressed, keyboard shortcuts are ignored, and the save status bar is hidden.
- **Read-only enforcement for subscribed catalogs**: Resources in subscribed catalogs are protected from modification at both the backend and UI levels. All 21 mutating command handlers check `IsCatalogEditable` and throw `CatalogReadOnlyException` for subscribed catalogs. The UI shows a "Read-only" badge and hides edit/delete buttons for subscribed resources.
- **Catalog-aware UI for all resource types**: Themes, stencils, assets, and attributes now have full catalog integration in the UI — catalog filter dropdown on list pages, catalog column in tables, catalog selector in create forms, and `/{catalogId}/{resourceId}` URL patterns for detail pages. Consistent with the template catalog UI.
- **Complete catalog resource types**: Catalogs now support themes, stencils, attributes, and assets alongside templates. A catalog is a self-contained package — importing one installs everything needed.
  - **Import**: All 5 resource types with dependency-ordered installation (assets → attributes → themes → stencils → templates). Auto-includes dependencies when installing individual resources (e.g., installing a template pulls in its theme, stencils, and attributes). Recursive scanning resolves transitive deps (template → stencil → asset).
  - **Export**: `ExportCatalog` builds self-contained manifests. `DependencyScanner` auto-includes all referenced resources. Per-type export queries for themes, attributes, stencils, and assets.
  - **Validation**: Catalogs with missing dependencies are rejected before install with a clear error listing what's missing.
  - **Protocol**: Polymorphic `CatalogResource` sealed hierarchy with Jackson `@JsonTypeInfo`. Asset binaries fetched via `contentUrl` (separate binary URLs). Schema version bumped to 2.
  - **DB**: Generic `catalog_resources` table replaces `catalog_templates`.
  - **UI**: Browse page shows resource types with badges. Install confirmation dialog previews what will be installed (including auto-resolved dependencies). HTMX inline updates with OOB success/error alerts.
- **BREAKING: Import/export moved to catalog module**: `ImportTemplates` and `ExportTemplates` moved from `epistola-core` to `epistola-catalog`. The catalog module is now the exchange boundary for all import/export operations. `TestTemplateBuilder` moved to the shared testing module.
- **Helm chart release skill**: Added `/release-helm-chart` Claude Code skill for releasing new Helm chart versions, mirroring the app `/release` flow
- **Separate Helm chart changelog**: Chart changes are now tracked in `charts/epistola/CHANGELOG.md`, independent of the app changelog
- **Editor block clipboard copy/paste**: The template editor can now copy the selected block subtree to a dedicated clipboard payload and paste it before, after, or inside another target block using a placement dialog with slot selection for multi-slot containers.

### Fixed

- **Thymeleaf CVE-2026-40477 and CVE-2026-40478**: Override Thymeleaf to 3.1.4.RELEASE to fix critical expression injection vulnerabilities (pending Spring Boot 4.0.6)

### Changed

- **Helm chart workflow aligned with app release**: The Helm workflow now triggers on `release: published` (for `chart-*` tags) instead of `workflow_dispatch`, matching the app release pattern
- **Vulnerability scan output in CI**: Trivy scan results are now printed to the workflow log when vulnerabilities are found, instead of silently failing with just an exit code

## [0.14.0] - 2026-04-10

### Added

- **Explicit OIDC vs Keycloak admin chart config**: Split the Helm chart's single Keycloak block into separate `oidc` and `keycloakAdmin` settings, so browser login and admin/bootstrap wiring can be configured independently.
- **Helm pinned in `.mise.toml`**: Added `helm` to project-level tool versions for consistent chart development.
- **Per-tenant feature toggles**: Tenant managers can enable/disable features per tenant via Settings > Features. Global defaults are configured in `application.yaml` (`epistola.features.*`), with per-tenant overrides stored in the database. The feedback feature is the first gated capability — when disabled, the feedback nav link, FAB button, and console capture script are hidden for that tenant.
- **CodeQL static analysis**: New GitHub Actions workflow (`.github/workflows/codeql.yml`) that runs CodeQL SAST scanning on every push and PR to main, plus a weekly schedule. Scans both Java/Kotlin backend and JavaScript/TypeScript frontend with the `security-and-quality` query suite. Results appear in the GitHub Security tab under Code scanning.
- **Content Security Policy headers**: CSP headers are now set on all UI responses, restricting script/style/font/image sources and blocking framing and plugin-based attacks.
- **`localauth` profile**: New profile for form-based login with configurable users via environment variables. Allows staging/test environments to have form login with custom credentials alongside Keycloak.

### Fixed

- **Systematic catalog_key fix across all SQL queries**: Added `catalog_key` to WHERE/JOIN/SELECT clauses in ~35 queries across template commands, variants, activations, versions, stencil commands, attribute commands, document queries, and catalog import. Prevents ambiguous results when the same slug exists in multiple catalogs.
- **CSP-compliant dialog close**: Replaced `hx-on::after-request` (uses `eval()`, blocked by CSP) with `HX-Trigger: closeDialog` pattern in variant edit and attribute edit forms.
- **Missing catalogId in variant edit URL**: Fixed Thymeleaf URL expression missing the `catalogId` parameter, causing `CatalogKey` validation failure.
- **Export includes all catalog resources**: ZIP export now queries all resources by `catalog_key` directly instead of relying on dependency scanning. Fixed `ExportAssets` to filter by asset ID instead of name.
- **ImportAsset updates existing assets**: Re-importing a catalog with modified assets now updates the asset metadata and binary content instead of silently skipping.
- **DeleteAsset fails with JDBI mapping error**: Replaced `mapTo<CatalogKey>()` with a manual row mapper in `DeleteAssetHandler`, fixing `IllegalArgumentException: Could not match constructor parameters [catalog_key] for CatalogKey`. JDBI cannot auto-map Kotlin value classes.
- **DemoLoader fails on user ID scheme change**: Fixed `ON CONFLICT DO NOTHING` in user upsert to `DO UPDATE`, so existing users with changed deterministic UUIDs are updated instead of silently skipped (which caused FK violations on `tenant_memberships`).
- **PDF preview blocked by CSP**: Added `frame-src blob:` to the Content Security Policy to allow blob URLs in iframes, fixing the PDF preview feature.
- **DOM XSS in HTMX error banner**: Replaced `innerHTML` with safe DOM API (`textContent` + `createElement`) in the global HTMX error handler to prevent potential cross-site scripting via error messages.
- **Catalog HTTP restriction**: Remote catalog fetching now requires HTTPS by default. Plain HTTP is only allowed when explicitly enabled via `epistola.catalog.allow-http=true` (set in the local profile).
- **Pagination bounds validation**: API pagination parameters (`page`, `size`) are now sanitized to prevent integer overflow and unbounded queries (max page size: 1000).
- **Hardcoded credentials in login UI**: Removed hardcoded username/password hints from the login page.

### Changed

- **GitHub Actions pinned to commit SHAs**: All GitHub Actions across all workflows are now pinned to immutable commit SHAs instead of mutable version tags, preventing supply chain attacks.
- **BREAKING: Orthogonal profile restructuring**: Profiles are now single-concern and composable. Each profile controls exactly one axis (auth, data, dev, hardening). Migration:

  | Before           | After                                        |
  | ---------------- | -------------------------------------------- |
  | `prod`           | `prod,keycloak`                              |
  | `demo`           | `keycloak,demo` or `keycloak,demo,localauth` |
  | `local`          | `local` (unchanged)                          |
  | `local,keycloak` | `local,keycloak` (unchanged)                 |

- **Demo data is now opt-in**: Base config defaults to `epistola.demo.enabled=false`. Activate via `demo` or `local` profile.
- **`demo` profile no longer provides form login**: The `demo` profile only loads demo data. For form login, use `local` or `localauth` profile.

## [0.13.0] - 2026-04-07

### Added

- **In-app changelog dialog**: Added a `Changelog` action in the app footer that opens a native dialog and loads release notes from `CHANGELOG.md` via a server-rendered HTMX fragment.
- **"What's New" dashboard card**: Compact teaser card on the tenant dashboard showing the latest version number and a summary (e.g. "6 new features, 1 fix"). Click "View details" to open the full changelog dialog. Dismiss records the current app version per user (stored in `changelog_acknowledgments` table). The card reappears when a new version is deployed.
- **Two-panel changelog dialog**: The changelog dialog now features a version sidebar listing all releases, with HTMX-powered version switching. Selecting a version loads its content into the detail panel.
- **Delete tenant from UI**: Tenant managers can now delete tenants from the tenant list page via a confirmation dialog. Warns that all associated data (templates, themes, environments, assets, memberships) will be permanently deleted.
- **Page total system parameter**: New system parameter `sys.pages.total` (total pages) available in all contexts — body content, headers, and footers. Combine with `sys.pages.current` to build custom formats like `Y/X` or `X of Y`. Two-pass rendering only triggers when `sys.pages.total` is used; `sys.pages.total` is validated against use in conditionals/loops to prevent page count instability. The first (counting) pass uses a 2-digit placeholder (99) to reserve character width and minimise layout instability for documents up to 99 pages.

### Fixed

- **Release workflow race condition**: Docker images are now only built on `release` events, not on every push to main. This eliminates the race condition where simultaneous `push` and `release` events could cause the release event to be suppressed, resulting in missing version-tagged Docker images. The release skill now also pins the tag to the exact changelog commit using `--target`.
- **Restrict `sys.pages.current` to page headers/footers**: The current page number is only available during per-page rendering and cannot work in body content. The renderer now validates this at render time, and the editor expression dialog hides `sys.pages.current` for nodes outside page headers/footers.

### Changed

- **Navigation: grouped dropdowns**: Replaced 12 flat header links with 4 grouped dropdown menus (Authoring, Resources, Operations, Settings). Includes keyboard navigation (arrow keys, Escape), ARIA attributes, and responsive mobile overlay menu with hamburger toggle.

## [0.12.0] - 2026-04-04

### Added

- **Stencils domain model**: New `stencils` package in epistola-core with full CQRS implementation for reusable template components. Includes `Stencil` and `StencilVersion` entities, version lifecycle (draft/publish/archive), usage tracking across templates, and no-nesting validation at publish time.
- **Stencils REST API**: Full CRUD controller implementing the generated `StencilsApi` interface — stencil and version management, usage tracking, and upgrade preview endpoints at `/api/tenants/{tenantId}/stencils/...`.
- **Stencils management UI**: List, create, and detail pages with HTMX search, form validation, version history with publish/archive/new-draft actions. Navigation link added to sidebar.
- **Stencil editor integration**: Stencil component in template editor with full lifecycle — create new (empty container), insert existing (browse dialog with search), publish as stencil (from inspector), update stencil source (push changes back as draft), and detach. Content is copy-on-insert with re-keyed IDs.
- **Stencil permissions**: `STENCIL_VIEW`, `STENCIL_EDIT`, `STENCIL_PUBLISH` permissions mapped to reader, editor, and manager roles respectively.
- **Stencil database schema**: Flyway migration V19 creating `stencils` and `stencil_versions` tables with GIN indexes for tag and content search.
- **Resource exchange design**: Design document for exchanging templates between Epistola instances via externally hosted catalogs. Defines the catalog protocol (manifest + detail URL format), content hashing for version detection, and upgrade strategy with local change protection.
- **Catalog module (Phase 1)**: New `epistola-catalog` module for importing templates from remote catalogs. Register a catalog URL, browse available templates, and install them. Includes catalog entity, HTTP client, CQRS commands/queries, and settings UI.
- **Shared testing module**: New `modules/testing` module consolidating test infrastructure (Testcontainers, IntegrationTestBase, Scenario DSL, TestFixture DSL, TestIdHelpers) previously duplicated across modules.
- **System parameter `sys.render.time`**: Added `sys.render.time` system parameter that returns the render timestamp as ISO-8601 UTC datetime. Use `$formatDate()` for locale-specific formatting (date and/or time). `$formatDate` converts to the configured timezone (default: Europe/Amsterdam). Available in all expression contexts (body, headers, footers).
- **Manual workflow dispatch**: Build workflow can now be triggered manually via `workflow_dispatch` as a fallback when release events don't fire.

### Fixed

- **Data contract editor remount on boosted navigation**: Fixed editor not remounting when navigating between templates via HTMX boost.

## [0.11.0] - 2026-04-02

### Added

- **Version comparison**: Side-by-side PDF comparison of two template versions. Accessible from the deployment matrix via a "Compare" button per variant. Opens a fullscreen overlay where you can select two versions and a data example, then view both rendered PDFs side by side.
- **Generation history dashboard**: New "Generation" page in the tenant navigation showing document generation statistics (total generated, in queue, completed, failed), most used templates, recent jobs with status filter, and recent failures with error messages.
- **Additional string formats in schema editor**: Added support for `date-time` (ISO 8601) and `uri` formats on string fields. These formats are now available in the visual editor dropdown, pass compatibility checks, and have validation for test data.
- **Attribute definitions REST endpoints**: Wired up REST API endpoints for managing attribute definitions.

### Fixed

- **False unsaved changes warning on load**: Fixed a bug where the data contract editor showed an "unsaved changes" prompt when navigating away without making any changes. This occurred when the schema used features that triggered json-only mode, because the committed baseline was not set for the raw JSON schema.

## [0.10.0] - 2026-04-01

### Added

- **Typed document generation exceptions**: Replaced generic `require()` / `IllegalArgumentException` in `GenerateDocument` and `GenerateDocumentBatch` handlers with typed exceptions (`TemplateVariantNotFoundException`, `VersionNotFoundException`, `EnvironmentNotFoundException`, `DefaultVariantNotFoundException`). Each maps to a specific HTTP status code and structured error response in the API.
- **Complete API exception handling**: Added missing exception handlers for `AssetNotFoundException` (404), `AssetTooLargeException` (413), `UnsupportedAssetTypeException` (400), `AssetInUseException` (409), `EnvironmentInUseException` (409), and `FeedbackAccessDeniedException` (403).
- **JSONB GIN indexes**: Added GIN indexes on `template_versions.template_model` and `template_variants.attributes` for faster JSON traversal queries.
- **QR code block**: Templates can now include a `QR Code` component that generates a code from an expression. The editor shows a live preview using example data, and PDF generation renders the same QR code in output documents. Values are limited to 2,500 bytes (UTF-8) due to QR specification constraints; the editor preview shows a clear error when this limit is exceeded.
- **QR code in demo invoice**: The demo invoice template now includes a "Scan to pay" QR code in the footer, linked to a `paymentLink` data field.
- **JSON Schema view in contract editor**: Schema tab now has a Visual/JSON toggle to view the JSON Schema representation of the data contract. Includes copy-to-clipboard button.
- **Import JSON Schema**: New "Import Schema" button opens a dialog to paste or upload a JSON Schema file. Imported schemas are checked for compatibility with the visual editor.
- **Schema compatibility checking**: When an imported schema uses features not supported by the visual editor (enum, $ref, allOf/anyOf/oneOf, etc.), the visual editor is disabled and a read-only JSON view with a compatibility warning banner is shown instead.
- **Field constraints in visual editor**: Number/integer fields support `minimum` and `maximum` constraints. Array fields support `minItems`. String fields support `format: "email"`.
- **Two-panel schema editor**: The visual schema builder now uses a list + detail panel layout. A compact field list on the left shows field names, types, and required indicators. Clicking a field opens its full editing form in the detail panel on the right, including name, type, required, description, and type-specific constraints. The page is widened to `72rem` to accommodate the layout.
- **Feedback screenshot**: Replaced file upload (click, paste, drag-and-drop) with two in-browser capture modes — region selection (draw a rectangle on the page) and viewport capture. Uses the native Screen Capture API (zero dependencies). Buttons are hidden in unsupported browsers.

### Changed

- **Hierarchical Keycloak groups**: Replaced flat `ep_{tenant}_{role}` group naming convention with hierarchical Keycloak groups under `/epistola/tenants/{tenant}/{role}`, `/epistola/global/{role}`, and `/epistola/platform/{role}`. Group membership mapper now uses full paths. `KeycloakAdminClient` supports hierarchical group operations (`findGroupByPath`, `createSubGroup`, `ensureGroupPath`). Reserved words `tenants`, `global`, `platform` added to `TenantKey`. Optional startup initializer (`epistola.keycloak.ensure-groups=true`) creates the base group hierarchy in Keycloak for environments without a realm import.

### Fixed

- **Local admin tenant access**: The `admin@local` user now has global roles, granting access to all tenants. Previously, it only had platform role (TENANT_MANAGER) which allowed creating tenants but not accessing them.
- **Authorization error page**: Authorization errors (403) now render the styled error page for full-page navigations instead of returning raw JSON. HTMX requests continue to receive JSON for the client-side error banner.
- **Docker Compose SELinux compatibility**: Fixed volume mounts for SELinux-enforcing hosts.
- **QR code PDF generation**: Handle encoding failures (e.g., input exceeding QR capacity) gracefully instead of crashing the entire PDF render.
- **Dialog header layout**: Close button now correctly positions to the right of the title in `ep-dialog-header` (added flexbox).
- **Schema import undo**: Importing a JSON Schema now snapshots the previous state so the import can be undone with Ctrl+Z.
- **Schema import migration detection**: Importing a schema (including incompatible/json-only schemas) now properly detects breaking changes against existing data examples and shows the migration dialog.
- **Live example validation on schema edits**: Renaming, deleting, or changing field types now immediately re-validates all data examples, showing validation errors in real time instead of only at save time. Also applies to undo/redo.
- **"Save Anyway" on backend validation failure**: When the backend rejects a schema save due to example validation issues (e.g. making a field required when examples lack it), a "Save Anyway" button now appears alongside the error, allowing users to force-save with `forceUpdate=true`.
- **Constraints dropped on type change**: Changing a field's type now properly drops inapplicable constraints (e.g. minimum/maximum are dropped when changing from number to string, format is dropped when changing from string to number).
- **Migration detection for array item fields**: Renaming or removing required fields inside array-of-objects (e.g. `items[].total`) now correctly triggers the migration dialog instead of failing silently at save time.

## [0.9.0] - 2026-03-30

### Added

- **Clean mode toggle**: New toolbar button and keyboard shortcut (Leader + C) to toggle between design mode (full block chrome) and clean mode (headers and borders hidden, shown on hover). Persists across sessions via localStorage.
- **Collapsible containers**: Container blocks (sections, columns, loops, conditionals, etc.) can now be collapsed to a single header line showing the component label and child count. Click the chevron in the block header to toggle. Reduces vertical clutter when working on large templates.

### Fixed

- **Table rendering broken in demo**: Fixed table nodes in the invoice demo template using an old `rows` array format instead of the current numeric `rows`/`columns`/`headerRows` props.
- **Invoice demo template rewritten**: Replaced the hacky loop+table combination with a proper **datatable** for line items (iterates over `items` array) and a **static table** for the totals summary (Subtotal, Tax, Total). Both table component types are now properly showcased.
- **Scoped variables missing in inspector expression dialog**: The inspector's expression dialog now includes iteration variables (`item.*`, `item_index`, etc.) from ancestor loops and datatables, matching the behavior already present in inline text expression chips.
- **Zero-value spacing deletion**: `expandSpacingToStyles` now treats all zero values (`0px`, `0em`, `0rem`, etc.) as removable, not just `0pt` and `0sp`.

### Changed

- **CI**: Added frontend tests (`pnpm test`) to the GitHub build pipeline.
- **Renovate: separate toolchain from dependency updates**: Kotlin, Gradle, Kover, Node, and pnpm are now grouped into a dedicated "toolchain" PR. Epistola-contract packages (server SDK + editor model) are grouped together. Prevents toolchain incompatibilities from blocking dependency security patches.

## [0.8.0] - 2026-03-30

### Added

- **Scoped iteration variables in expression builder**: Expression chips inside loops and datatables now show iteration-scoped variables (`item.name`, `item_index`, `item_first`, `item_last`) in the builder field dropdown under an "Iteration variables" group. Supports nested loops — inner chips see both outer and inner scopes. Live preview resolves scoped expressions against the first array element.
- **Auto-rewrite expressions on alias rename**: When changing a loop/datatable's `itemAlias` (e.g., `item` → `row`), all expression chips in the loop's subtree automatically update their references. Undo restores both the alias and expressions. Scoped to the specific loop — sibling loops with the same alias are not affected.
- **Command metadata on doc:change events**: The `doc:change` engine event now includes the full command object, enabling decoupled components to react to command-specific metadata (e.g., alias renames).
- **Generic scope provider on component registry**: Components can now declare scoped variables for their descendants via a `scopeProvider` hook on the component definition. The engine collects scopes by walking ancestors — no hard-coded component types. Loop and datatable use the shared `buildIterationScope` helper. Variable shadowing is prevented: the inspector validates that proposed scope variables don't conflict with existing variables at that position.
- **Custom `$formatDate` JSONata function**: Register a `$formatDate(value, pattern)` function in both the Kotlin PDF renderer and TypeScript editor preview. Formats ISO date and datetime strings (e.g., `"2024-01-15"`, `"2024-01-15T14:30:00Z"`) using standard date/time patterns (`dd-MM-yyyy`, `dd-MM-yyyy HH:mm`, `d MMMM yyyy`, etc.). Datetimes are converted to the configured timezone (default: Europe/Amsterdam). Works in any expression context including string concatenation and conditionals.
- **Date format dropdown in expression dialog**: When editing an expression that references a date field, a format dropdown appears with common date format presets. Selecting a format auto-wraps the expression with `$formatDate(...)`. The dropdown parses existing `$formatDate` expressions to pre-select the current format.
- **Date field type detection**: Fixed `extractFieldPaths()` to detect JSON Schema date fields (`{ type: "string", format: "date" }`) as `type: "date"` instead of `"string"`, enabling the expression dialog to show the format dropdown for date fields.
- **Builder/Code dual-mode expression dialog**: Inline expression chips now open a Grafana-style builder as the default mode. The builder provides field and format dropdowns for the common case (pick a field, optionally format it). A Code toggle switches to the full JSONata editor for power users. Complex expressions that can't be represented in builder mode automatically default to Code mode. Inspector loop/conditional dialogs are unchanged.
- **Editor and template label associations**: Editor and template labels are now properly associated with their inputs for accessibility.

### Fixed

- **Oxfmt**: Exclude generated `.github/badges/` from formatting checks.

### Changed

- **Renovate**: Disabled auto-updates for `epistola-contract` — this dependency is managed manually as part of cross-repo workflows.
- **Tooling**: Migrated ESLint/Prettier to Oxlint/Oxfmt; switched oxfmt to single quotes.

## [0.7.0] - 2026-03-28

### Added

- **Synchronous document preview API endpoint**: New `POST /api/tenants/{tenantId}/documents/preview` endpoint that generates a preview PDF and returns it directly. Supports variant selection (explicit, attribute-based, or default), version resolution (explicit version ID or environment-based), and data validation against template schema. Not PDF/A compliant, not stored — intended for preview purposes only.
- **Focused preview queries**: `PreviewDocument` (API — published versions) and `PreviewDraft` (editor — drafts/live template model) with shared `DocumentPreviewRenderer` service, replacing the previous `GetPreviewContext` query.
- **IllegalArgumentException API handler**: `require()` failures in commands/queries now return 400 Bad Request instead of 500.

### Changed

- **Upgraded epistola-contract to 0.1.19**: Picks up the new `previewDocument` operation in the `GenerationApi` interface.
- **Refactored TemplatePreviewHandler**: Now delegates to the `PreviewDraft` query instead of containing its own rendering logic.

### Fixed

- **CI build failure due to import ordering**: Fixed lexicographic import ordering in `DocumentGenerationExecutor.kt` that caused ktlint check to fail.
- **Document generation fails with "No authenticated user in current scope"**: The `JobPoller` executes generation jobs on virtual threads outside the HTTP request scope, where no `SecurityContext` principal is bound. The mediator's authorization checks (`RequiresPermission`, `RequiresAuthentication`) would then reject all queries. Fixed by creating a system principal with full tenant access for the request's tenant and binding it via `SecurityContext.runWithPrincipal()` before executing the job.

### Changed

- **oxlint config simplified for gradual code quality improvements**: Enabled correctness, suspicious, perf, restriction as warnings (real code quality issues). Disabled pedantic and style categories (cosmetic noise). Added test file overrides for empty functions, magic numbers, and type assertions. Drops from ~9k warnings to ~1.8k warnings of real code quality issues, providing a foundation for incremental lint error fixes.

- **Data contract example editor UX polish**: Added focus-visible ring styles for keyboard accessibility. Refactored hover states to nested CSS selectors. Redesigned empty states with icons, larger dimensions, and dashed borders. Improved tree layout with larger inputs, better spacing, and grid-based label/input arrangement. Added semantic color-coded type badges for object/list fields. Added validation success indicator to toolbar. Enhanced array item rows with numbered indicators, inline remove buttons, and collapsible object items.

- **Data contract example editor accessibility**: Added role="list"/"listitem" semantics for array containers. Added aria-describedby to inputs with validation errors for screen reader announcements. Added aria-hidden to decorative elements (badges, icons, error dots). Added aria-label to collapsible details elements for context. Added aria-label to checkbox inputs in boolean fields. Added for/id association between example name label and input. Enhanced chip buttons with aria-label describing example name and validation status.

- **Theme editor UX polish**: Added focus-visible ring styles to all interactive elements (buttons, inputs, selects). Refactored hover states to nested CSS selectors for consistency. Redesigned empty state with icon and centered layout. Improved preset card styling with smooth transitions. Added inspector style group labels with uppercase text styling. Updated status bar with right-aligned layout. Applied uniform border-radius and spacing throughout.

- **Theme editor accessibility**: Replaced custom div-based preset expand/collapse with native `<details>`/`<summary>` elements for built-in accessibility (aria-expanded, keyboard support). Added for/id associations on preset key and label inputs. Added aria-label to remove preset buttons. Added aria-hidden to decorative toggle icons.

### Added

- **Authorization enforcement in mediator**: All commands and queries now declare authorization requirements via marker interfaces (`RequiresPermission`, `RequiresPlatformRole`, `RequiresAuthentication`, `SystemInternal`). The `SpringMediator` enforces these before dispatching — unauthenticated or unauthorized requests are rejected with `PermissionDeniedException`, `TenantAccessDeniedException`, or `PlatformAccessDeniedException`.
- **Authorization coverage safety net**: `AuthorizationCoverageTest` uses classpath scanning to verify every `Command` and `Query` implements `Authorized`, preventing unprotected operations from being added.
- **Enterprise authorization model**: Four-layer authorization architecture (L1: Authentication, L2: Tenant access, L3: Coarse roles, L4: Fine-grained permissions). Keycloak owns L1-L3; Epistola owns L4.
- **Permission enum**: Fine-grained permissions (`TEMPLATE_VIEW`, `TEMPLATE_EDIT`, `TEMPLATE_PUBLISH`, `DOCUMENT_VIEW`, `DOCUMENT_GENERATE`, `THEME_VIEW`, `THEME_EDIT`, `TENANT_SETTINGS`, `TENANT_USERS`) mapped from coarse tenant roles in application code.
- **Permission and platform role checks**: `requirePermission()`, `requireTenantManager()` security extensions with dedicated exception types (`PermissionDeniedException`, `PlatformAccessDeniedException`).
- **Keycloak group-based authorization**: Roles and platform roles are now derived from Keycloak groups using the `ep_` prefix convention (`ep_{tenant}_{role}`, `ep_{role}`, `ep_tenant-manager`). Replaces custom `epistola_tenants` claim and `resource_access` client roles.
- **Global tenant roles**: Users assigned to groups like `ep_reader` gain the role across all tenants. Global roles are merged with per-tenant roles at runtime.
- **Group membership parser**: Shared `GroupMembershipParser` utility used by both JWT converter and OAuth2 provisioning to parse the `groups` JWT claim.
- **Keycloak Admin Client**: REST client for the Keycloak Admin API, using client credentials for group management. Configured via `epistola.keycloak.*` properties.
- **Tenant provisioning**: `TenantProvisioningPort` interface with Keycloak implementation that auto-creates groups (`ep_{key}_reader/editor/generator/manager`) when a tenant is created. Falls back to no-op when Keycloak is not configured.
- **Membership sync on login**: `SyncTenantMemberships` command upserts JWT-derived memberships to `tenant_memberships` table on OAuth2 login for API key fallback and audit.
- **Tenant membership role column**: `tenant_memberships` table now includes `role` and `last_synced_at` columns for JWT claim sync.
- **Spacing scale system**: Introduced a systematic spacing scale based on a 4pt base grid with `Nsp` notation. All spacing values are now multiples of a configurable base unit (default 4pt), providing consistent visual rhythm across documents. Themes can customize the `spacingUnit` for tighter or looser designs.
- **Border rendering in PDF**: StyleApplicator now renders borderWidth/borderStyle/borderColor, compound shorthands (borderTop/Bottom/Left/Right), and borderRadius.

### Changed

- **Keycloak realm export**: Replaced `epistola-tenants-mapper` (organization membership) and `epistola-client-roles-mapper` (client roles) with built-in Group Membership Mapper. Removed `tenant-manager` client role. Test users now assigned to `ep_*` groups.
- **Platform roles sourcing**: `TENANT_MANAGER` is now sourced from the `ep_tenant-manager` group instead of `resource_access.epistola-suite.roles` client role.
- **EpistolaPrincipal**: Added `globalRoles` field. `hasAccessToTenant()`, `rolesFor()`, `hasPermission()`, and `hasRole()` now merge global roles with per-tenant roles.
- **Simplified unit system**: Only 3 units — pt (all sizes), sp (spacing scale), mm (page margins). Removed px, em, rem, cm.
- **Release workflow**: GitHub Releases and versioned Docker images are now only created when a release is published (via GitHub UI or `gh release create`), no longer on every push to main. Main branch pushes still build, test, and push `latest`/SHA-tagged Docker images.

### Changed

- **template-model moved to epistola-contract**: The `template-model` module (JSON schema types for documents, themes, components) has been moved to the `epistola-contract` repository as `editor-model` and is now consumed as an external Maven artifact (`app.epistola.contract:editor-model`) and npm package (`@epistola/editor-model`). This eliminates the last Gradle configuration cache blocker.
- **Gradle configuration cache**: Fully enabled — all tasks in the build graph are now configuration cache compatible. Fixed 2 blockers in the editor module (project reference in `doLast`, redundant `upToDateWhen`).
- **Convention plugins**: Extracted shared Kotlin/test/kover configuration into `epistola-kotlin-conventions` and `epistola-kover-conventions` buildSrc plugins, eliminating `allprojects`/`subprojects`/`configure` blocks from the root build file. Repositories moved to `settings.gradle.kts` via `dependencyResolutionManagement`.
- **Linting and formatting tooling**: Replaced ESLint + Prettier with Oxlint + Oxfmt for faster (50-100x) TypeScript/JavaScript linting and formatting. Added `oxlint-tsgolint` for type-aware rules. CI now enforces `lint:check` and `format:check`. Thymeleaf templates and Helm charts are excluded from formatting via `ignorePatterns`.

### Fixed

- **Session expired dialog never showing**: The `session_expires_at` cookie had the same lifetime as the session, so the browser deleted it at the exact moment the expired dialog should appear. Cookie now outlives the session by 10 minutes, and the JS monitor treats a vanished cookie as an expired session.
- **Editor save error**: `UpdateDraft` SQL queries were missing `template_key` filter, causing errors when multiple templates share the same variant slug (e.g., "default"). Added `template_key` to UPDATE, SELECT, and MAX(id) queries.
- **Autocomplete missing data model variables**: `GetEditorContext` cast JSONB `data_model` directly to `ObjectNode` instead of deserializing from `PGobject`, causing it to always be null. Variable autocomplete now correctly shows data model variables.
- **Version list showing all variants' versions**: SQL JOINs in `ListVersions`, `GetVersion`, and `GetVariant` subqueries were missing `template_key` in JOIN conditions, causing version lists to include versions from other templates when variant slugs collide.
- **New template form**: Removed the "JSON Schema" textarea from the template creation form — data contracts are managed through the dedicated data contract editor instead.
- **Page header/footer overlap**: Page header and footer content no longer overlaps with body content in PDF output. The document margins are now automatically increased to reserve space for header/footer bands when present.
- **Theme margin update crash**: Setting margins in the theme editor no longer crashes with a Jackson null deserialization error. `PageSettings` now has default values for `format` (A4) and `orientation` (portrait), allowing partial payloads from the frontend.

### Added

- **PDF link rendering**: `TipTapConverter` now renders ProseMirror `link` marks as clickable hyperlinks in PDF output. Links are styled with blue color and underline, and support both `https://` and `mailto:` URIs. Other marks (bold, italic, etc.) are correctly applied on top of links.
- **Local dev Docker Compose**: Unified `apps/epistola/docker/docker-compose.yaml` with PostgreSQL and Keycloak services. Single `docker compose up -d` to start all local dependencies.

### Fixed

- **API docs link broken**: The nav and footer links to `/api-docs/index.html` pointed to a page that no longer existed since the OpenAPI spec moved to the `epistola-contract` repo. Replaced with an `/api-docs` page powered by Scalar that loads the bundled spec from the contract artifact. Removed the stale `rest-api` `package.json` (no longer needed).

### Changed

- **epistola-contract updated to 0.1.14**: The server artifact now bundles `epistola-contract.yaml` on the classpath, served at `/api-docs/epistola-contract.yaml`.

### Fixed

- **Template import: cross-template version collision**: When importing multiple templates that share the same variant key (e.g., `"default"`), SQL queries in `upsertDraft` and `publishDraft` filtered by `(tenant_key, variant_key)` without `template_key`, causing them to match version rows from other templates. This resulted in FK violations on `environment_activations` for all but the first template. Added `template_key` filter to all 6 affected queries.

### Added

- **Feedback integration tests**: 22 integration tests across 7 test groups covering CreateFeedback, GetFeedback, ListFeedback (with status/category filters), Comments (local + external dedup), Assets (storage + fromDataUrl), SyncStatus (updates, retry attempts, max attempts exclusion), SyncConfig, and tenant isolation.
- **GitHubIssueSyncAdapter unit tests**: Comprehensive test suite (12 tests) covering issue creation, screenshot upload via Contents API, graceful degradation on upload failure, multiple asset handling, console logs rendering, comment posting with author attribution, and status updates. Uses `MockRestServiceServer` for HTTP layer mocking without Spring context.

### Changed

- **Feedback sync: mediator consistency**: Extracted all direct JDBI calls in sync handlers/schedulers into proper CQRS commands and queries (`UpdateFeedbackCommentExternalRef`, `UpdateFeedbackSyncStatus`, `GetFeedbackByExternalRef`, `UpdateFeedbackSyncConfigLastPolledAt`, `ListEnabledFeedbackSyncConfigs`). Sync code now follows the same mediator pattern as the rest of the codebase.
- **Feedback sync: retry limit**: Added `sync_attempts` column to feedback table. After 5 failed sync attempts, feedback is marked as `FAILED` and excluded from retry. Prevents infinite retry loops when external service is misconfigured.
- **Feedback sync: config check consistency**: `OnFeedbackCreated` and `OnFeedbackCommentAdded` now check both `config != null` and `config.enabled` before attempting sync.
- **Feedback sync: safe lastPolledAt**: Poll scheduler now only advances `last_polled_at` to the timestamp of the last successfully processed update, not unconditionally to `now()`.
- **Screenshot decoding**: Moved base64 data URL parsing from `FeedbackHandler` into `AddFeedbackAsset.fromDataUrl()` factory method for better separation of concerns.
- **Event handler safety**: Added warning logging to `OnFeedbackCreated` and `OnFeedbackCommentAdded` when result type casting fails, instead of silently returning.
- **Badge template fragments**: Extracted inline Thymeleaf ternary chains for status/priority/category badges into reusable `feedback/fragments/badges.html` fragments.

### Changed

- **Feedback assets: dedicated storage with GitHub upload**: Screenshots are now stored in a dedicated `feedback_assets` table instead of the shared assets system. The `screenshot_key` column has been removed from the `feedback` table. When syncing feedback to GitHub, screenshots are uploaded to `.epistola/screenshots/` in the target repository and embedded as images in the issue body. The `FeedbackSyncPort.createTicket()` signature now accepts `List<FeedbackAssetContent>` instead of `ByteArray?`, enabling multiple attachments per feedback item in the future.

### Added

- **Feedback FAB with page issues popover**: The feedback floating action button now shows a popover with open feedback items for the current page (matched by URL pathname). Includes a badge count, inline links to feedback detail, and a "New" button to submit feedback. The FAB is a self-contained JS module loaded from the feedback module. The `/search` endpoint now accepts a `url` query parameter to filter feedback by source URL.

### Changed

- **Feedback sync: PAT-based auth**: Replaced GitHub App authentication (JWT + installation tokens) with per-tenant Personal Access Token (PAT) authentication. Tenant admins now enter a fine-grained PAT in the settings page instead of a server-level GitHub App installation ID. Removed `GitHubAppAuthService`, `app-id`, and `private-key-path` configuration. PAT is masked in the UI for security.
- **Removed webhook support**: Removed `GitHubWebhookController`, `GitHubWebhookVerifier`, and `GitHubAppProperties`. Inbound sync now uses polling exclusively. PAT-based auth does not support inbound webhooks.

### Added

- **Feedback sync settings UI**: New settings page at `/tenants/{id}/settings/feedback-sync` for configuring per-tenant feedback sync. Supports enabling/disabling sync, selecting provider (GitHub), and entering provider-specific settings (PAT, repository owner/name, optional label). Includes form validation and persistence via existing `SaveFeedbackSyncConfig` / `GetFeedbackSyncConfig` commands.

### Changed

- **Feedback sync generalization**: Refactored the feedback sync layer from GitHub-specific to provider-agnostic architecture.
  - **Port renamed**: `IssueSyncPort` → `FeedbackSyncPort` with `createTicket` (was `createIssue`), `fetchUpdates` (new), and `verifyWebhookSignature` removed from the interface.
  - **Generic config model**: `FeedbackConfig` replaced by `FeedbackSyncConfig` with `providerType` enum and `settings` JSONB column. Provider-specific settings (repo, installation ID, label) parsed by each adapter from the JSONB payload.
  - **Backend-agnostic comments**: `CommentSource.GITHUB` → `CommentSource.EXTERNAL`, `external_comment_id` column changed from `BIGINT` to `TEXT` (supports GitHub numeric IDs, Jira string IDs, etc.).
  - **Database table renamed**: `feedback_config` → `feedback_sync_config` with new `provider_type`, `settings` JSONB, and `last_polled_at` columns.
  - **Property namespace**: Sync properties moved from `epistola.github.sync.*` to `epistola.feedback.sync.*`. GitHub-specific properties remain under `epistola.github.*`.
  - **Inbound polling**: New `FeedbackPollScheduler` polls external trackers for comments and status changes. Controlled via `epistola.feedback.sync.polling.enabled`.

### Added

- **Feedback system**: In-app feedback submission with optional GitHub Issues integration for tracking bugs, feature requests, and questions.
  - **Per-tenant roles**: JWT `epistola_tenants` claim now supports structured role objects (`MEMBER`, `ADMIN`) alongside legacy flat list format. Roles control feedback access (detail/comments visible to admin or creator only).
  - **Feedback module**: New `modules/feedback` module with full CQRS domain model — `Feedback`, `FeedbackComment`, `FeedbackSyncConfig` entities with commands for create, status update, comment, and sync operations.
  - **Feedback UI**: List page with status/category filters, detail page with comments timeline, submit dialog with title/category/priority/description fields. Floating action button (FAB) on every tenant-scoped page for quick submission.
  - **Screenshot capture**: Upload, paste (Ctrl+V), or drag-and-drop screenshots in the feedback form. Screenshots stored as assets and displayed in detail view.
  - **Client metadata**: Auto-captured browser info (user agent, viewport, screen size, pixel ratio), app version, platform, language, URL, and timestamp stored as JSONB.
  - **Console log capture**: Monkey-patches `console.log/warn/error/info` to buffer last 100 entries, attached to feedback submissions for debugging context.
  - **GitHub App integration**: Port/adapter pattern (`FeedbackSyncPort`) with `GitHubIssueSyncAdapter` for syncing feedback to GitHub Issues. Pure JDK crypto for GitHub App JWT auth (RS256). Installation token caching with 50-minute refresh. Tenant label support for shared repositories.
  - **Outbound sync**: `OnFeedbackCreated` event handler triggers sync after commit. `FeedbackSyncScheduler` retries pending items on configurable interval. Issues created with category/priority labels.
  - **Inbound polling**: Periodic polling of external trackers for new comments and status changes as alternative to real-time sync.
- **Production observability**: Comprehensive metrics, Grafana dashboards, and alerting for production operations.
  - **Separate management port**: Actuator endpoints (health, info, Prometheus metrics) run on port 4040, isolated from application traffic on port 4000. A dedicated security filter chain permits all management endpoints without authentication (network-level access control should restrict the management port in production).
  - **Prometheus endpoint**: Exposed `/actuator/prometheus` on management port, making all auto-configured and custom metrics available to scrapers.
  - **Mediator instrumentation**: All ~55 CQRS commands and queries are now timed via `epistola.mediator.command.duration` and `epistola.mediator.query.duration` (tags: operation name, outcome).
  - **Generation pipeline metrics**: Document generation timer with template and render path tags, PDF size distribution summary, proper job duration timer replacing ad-hoc tracking, generation queue depth gauge, and active jobs gauge.
  - **Event log metrics**: Audit trail persistence timer and failure counter for detecting audit gaps.
  - **Storage metrics**: `InstrumentedContentStore` decorator adds latency timing and size tracking across all storage backends (Postgres, S3, filesystem).
  - **API authentication metrics**: `epistola.api.auth.attempts` counter with result tags (success, invalid_key, disabled, expired, etc.) for security monitoring.
  - **Grafana dashboards**: 5 dashboards deployed as Grafana Operator CRDs via Helm (Overview, Generation, Mediator, Infrastructure, API & Security). Feature-toggled via `observability.grafana.enabled`.
  - **Alert rules**: 10 alert rules across 3 severity tiers (critical, warning, info) deployed as `GrafanaAlertRuleGroup` CRD, covering pipeline stalls, high failure rates, connection pool exhaustion, queue growth, and more.
- **Deterministic PDF rendering**: Three-layer defense ensuring same template + same data = same PDF output, even across Epistola upgrades.
  - **Versioned rendering defaults**: All hardcoded rendering constants (font sizes, margins, spacing, borders) centralized in `RenderingDefaults`. Published template versions record which defaults version was in effect. Old versions render with their original defaults forever.
  - **Theme snapshot at publish time**: When a template version is published, the full resolved theme cascade is frozen as a `ResolvedThemeSnapshot`. Published documents render with the theme as it was at publish time, not the current live theme.
  - **Visual regression tests**: Canonical templates rendered against stored baselines catch accidental rendering changes from code modifications, library upgrades, or constant changes. Run with `-DupdateBaselines=true` to regenerate baselines after deliberate changes.
  - **Engine version tracking**: Every generated PDF embeds an `EngineVersion` metadata field (e.g., `epistola-gen-1+itext-9.5.0`) for traceability.
- **Rendering upgrade documentation**: `docs/rendering-upgrades.md` documents procedures for iText upgrades, rendering defaults changes, and engine version tracking.
- **System parameters for template engine**: Runtime values provided by the rendering engine (independent of template data model) are now available in expressions. The first system parameter is `sys.pages.current`, which resolves to the current page number in page headers and footers. System parameters use a `sys.*` namespace that works across all expression evaluators (JSONata, JavaScript, SimplePath).
- **System parameter editor support**: Expression dialog shows system parameters in a dedicated "System parameters" section with visual distinction (badge). Mock values are injected for expression preview (e.g., `sys.pages.current` previews as "1").

### Changed

- **Registry-driven system parameters**: `SystemParameterRegistry` is now the runtime source of truth for system parameter injection. Adding a new system parameter only requires adding a descriptor (with `mockValue`) to the registry and updating `buildPageParams()`/`buildGlobalParams()` — no scattered code changes needed. Mock data injection for the editor is centralised in `EditorEngine.getExampleData()`, eliminating duplication across UI call sites.
- **Editor feedback flow alignment after rebase**: Removed the temporary `editor-notice` event path and restored the mainline drag-and-drop handler contract (`handleDrop` returns `void`). Shortcut feedback remains centralized through `LeaderModeController`.

### Fixed

- **CycloneDX SBOM generation fails in Gradle 9**: The `cyclonedxDirectBom` task used module jar outputs without declaring task dependencies, causing build failures with Gradle's strict task dependency validation. Fixed by declaring `runtimeClasspath` as a task input so Gradle infers dependencies automatically.
- **Security scan workflow fails**: The `security-scan.yml` workflow ran `generateSbom` without building the frontend first, causing `verifyFrontendBuild` to fail because the `dist/` directory didn't exist.
- **Footer always shows "vdev"**: Replaced `@ControllerAdvice`-based `VersionConfig` with a `HandlerInterceptor` (`VersionInterceptor`) so that `appVersion` and `appName` are injected into all Thymeleaf models, including functional route handlers. The old `@ModelAttribute` approach only applied to annotated `@Controller` beans.
- **ImportTemplates ON CONFLICT clause**: Fixed `ON CONFLICT (tenant_key, id)` in two `template_variants` upsert statements to `ON CONFLICT (tenant_key, template_key, id)`, matching the table's actual primary key. Without this fix, PostgreSQL rejected the queries at runtime because no unique constraint existed on just `(tenant_key, id)`.
- **Grafana dashboard/alert PromQL queries**: Fixed metric names in all 5 dashboards and alert rules to match actual Micrometer-exported names. Mediator panels now use `epistola_mediator_command_duration_seconds_count` instead of non-existent `epistola_commands_total`. Auth failure alert uses `epistola_api_auth_attempts_total` instead of `epistola_auth_attempts_total`. "Snapshot vs Legacy Path" panel now queries `epistola_generation_document_duration_seconds_count` by `path` tag.
- **Missing `epistola.jobs.max_concurrent` gauge**: The "Active vs Max Concurrent Jobs" dashboard panel referenced a gauge that was never registered. Added gauge registration in `JobPoller.init`.
- **EventLogSubscriber timer**: Refactored to use `finally` block and added `outcome` tag for success/failure distinction. Eliminates fragile duplicate `sample.stop()` calls.
- **Version queries missing rendering columns**: `GetVersion`, `GetDraft`, and `GetActiveVersion` queries now include `rendering_defaults_version` and `resolved_theme` columns. Without these, published versions always fell back to live theme resolution instead of using the frozen snapshot.
- **GetEditorContext query**: Added missing `template_key` to the LEFT JOIN on `template_versions`, preventing potential cross-template draft version matching when two templates share a variant slug.

### Changed

- **Handler ID extraction**: Added `ServerRequest` extension functions (`tenantId()`, `templateId()`, `variantId()`, `versionId()`, `themeId()`, `environmentId()`, `attributeId()`) that replace repetitive composite ID construction boilerplate across all 9 handler files.
- **Load test form upgrade**: Replaced static text inputs with dynamic HTMX-driven dropdowns for variant, version, data example, and environment selection. Variants, versions, and data examples load dynamically based on template and variant selection. Selecting a data example pre-fills the test data JSON. Version and environment fields are now dropdowns. Fixed blank test data submission bug.
- **Editor shortcuts architecture refactoring**: Major simplification of the keyboard shortcuts system — extracted LeaderModeController into a standalone testable class (17 new tests), made KeybindingDefinition generic to eliminate 13 type casts, collapsed the dual config/foundation type system by making runtime files self-contained (removing ~570 lines of translation boilerplate), unified the two ShortcutResolver instances into one using context-based routing, and slimmed shortcuts-config.ts from ~395 to ~70 lines. Adding a new shortcut now requires 1-2 files instead of 3.

### Added

- **Bulk template import endpoint** `POST /api/tenants/{tenantId}/templates/import`
  - Create-or-update semantics for idempotent template synchronization
  - Processes templates individually (one failure doesn't block others)
  - Per-template: creates/updates template metadata, variants, drafts, and publishes to environments
  - Returns per-template results: created, updated, unchanged, or failed with error message
  - New command: `ImportTemplates` with `ImportTemplatesHandler`

### Changed

- **Editor shortcuts command-system hardening**: Completed migration cleanup and helper projection hardening. Shortcut helper sections now render from runtime registry projection with deterministic layout thresholds, live filter, and active-key feedback. Legacy helper/leader lookup builders were removed from `shortcuts-config.ts`.
- **Docker image size reduction**: Replaced `paketobuildpacks/run-noble-full` (765MB) with a custom run image based on `run-noble-base` (188MB) plus only fontconfig and DejaVu fonts. Reduces total image size by ~565MB (from ~1.26GB to ~500MB). The custom image is built automatically as part of the `bootBuildImage` task. Removed the `Aptfile` in favor of a Dockerfile at `apps/epistola/docker/run-image/`.
- **Editor preview toggle focus behavior**: Using `Leader + P` to open preview now automatically focuses the resize handle once it is mounted, so width adjustment works immediately with arrow keys.
- **Editor resize handle visual states**: Resize handle CSS now uses host-driven hover/active/focus states, shows click-focus immediately (not only `:focus-visible`), and provides a subtler grip with a larger hit area.

### Added

- **Shortcut startup validation and plugin extension guardrails**: Added startup validation that checks merged core/plugin shortcut registries, plus plugin namespace validation and failure-mode tests for invalid plugin-provided bindings.
- **Shortcut docs refresh**: Added shortcut foundation/runtime docs and plugin shortcut extension guidance.
- **API key authentication**: External systems can authenticate REST API calls using an `X-API-Key` header. Keys use the `epk_` prefix, are SHA-256 hashed for storage, and support enable/disable and expiration. API key identities are Non-Personal Accounts (NPA) scoped to a single tenant.
- **OAuth2 JWT resource server**: REST API endpoints accept Bearer JWT tokens when an OIDC provider is configured. JWT claims are mapped to `EpistolaPrincipal` with tenant memberships.
- **Demo API key**: The `demo` profile auto-creates a well-known API key (`epk_demo_...`) logged at startup for easy testing.
- **CQRS commands for API key management**: `CreateApiKey`, `RevokeApiKey`, and `ListApiKeys` commands/queries for programmatic key lifecycle management.
- **Editor shortcut `Leader + R`**: Added a leader command to focus the preview resize handle directly.
- **Keyboard resizing for preview panel**: Focused resize handle now supports `ArrowLeft`/`ArrowRight` adjustments with a configurable `KEYBOARD_RESIZE_STEP` constant (16px), and `ArrowRight` at minimum width closes preview.

### Changed

- **BREAKING: REST API mounted under `/api` prefix**: All REST API endpoints now live under `/api/` (e.g., `/api/tenants` instead of `/tenants`). This enables a dedicated stateless security filter chain for API traffic.
- **Dual security filter chains**: `SecurityConfig` is split into an API chain (`/api/**` — stateless, no CSRF, API key + JWT auth) and a UI chain (session-based, form/OAuth2 login, CSRF enabled).
- **SecurityFilter ordering fix**: `SecurityFilter` now runs at `@Order(-99)` instead of `HIGHEST_PRECEDENCE + 1`, so it executes after Spring Security populates the `SecurityContext`.

### Fixed

- **Docker image version**: Application now displays the correct release version in the UI footer instead of "vdev". The Gradle build version is now set from a `-PreleaseVersion` property passed by CI, replacing the misleading hardcoded `0.0.1-SNAPSHOT`.
- **AccessDeniedException returns 403 instead of 500**: Introduced `TenantAccessDeniedException` and added exception handlers for 401/403 responses in `ApiExceptionHandler`.
- **Editor resize handle click focus**: Removed pointer-event default suppression that prevented mouse click from focusing the resize handle, restoring consistent click and keyboard focus behavior.

### Added

- **Pluggable content storage**: Binary content (images, PDFs) is now stored via a `ContentStore` abstraction instead of inline PostgreSQL BYTEA columns. Four backends are available: `postgres` (default, zero-config), `s3` (for production at scale), `filesystem` (local dev), and `memory` (fast tests). Configured via `epistola.storage.backend` property.
- **`content_store` table**: New key-value table for the PostgreSQL backend, storing binary content with S3-style keys (`assets/{tenantId}/{assetId}`, `documents/{tenantId}/{documentId}`).
- **Streaming document downloads**: REST API document downloads now use `InputStreamResource` instead of `ByteArrayResource`, enabling streaming of large PDFs without loading them entirely into heap memory.

### Changed

- **BREAKING**: The `content` column has been removed from both `assets` and `documents` tables. Binary content is now stored in the `content_store` table (PostgreSQL backend) or external storage (S3/filesystem). The `Document` model no longer has a `content` field.

### Changed

- **Demo invoice template improvements**: Added a vendor logo image block to the invoice header, converted multi-paragraph address/metadata blocks to use `hard_break` for tighter line spacing, and normalized `hardBreak` to `hard_break` for ProseMirror schema consistency. `UploadAsset` command now accepts an optional pre-defined `id` parameter.
- **Proper spacing architecture**: Unified the spacing system between the editor canvas and PDF renderer to eliminate the 2x vertical spacing mismatch. Individual spacing keys (`marginTop`, `marginBottom`, etc.) are now used throughout instead of compound objects, ensuring user-configured spacing is correctly applied in both the editor and PDF output. Added component default styles (`marginBottom: 0.5em`) for content blocks. TipTap paragraph and list spacing now matches the editor's ProseMirror CSS values.

### Added

- **Block deletion from canvas**: Blocks can now be deleted by pressing Delete/Backspace on the keyboard when selected, or by clicking the trash icon that appears in the block header. Escape key deselects the current block. Keyboard shortcuts are suppressed when focus is inside text editors, inputs, or textareas.
- **Asset deletion protection**: Assets referenced by draft or published template versions cannot be deleted. The system scans template document JSONB for image nodes referencing the asset before allowing deletion. Returns a descriptive error listing which templates use the asset.
- **Asset manager**: Tenant-scoped image asset management with upload, list, search, delete, and raw content serving. Assets are stored as PostgreSQL BYTEA with a 5MB size limit. Supports PNG, JPEG, SVG, and WebP. Includes a dedicated asset manager page with drag-and-drop upload and thumbnail grid.
- **Image block in template editor**: New "Image" block type in the template editor with an asset picker dialog for selecting or uploading images. The picker shows a thumbnail grid of existing assets and supports drag-and-drop upload.
- **Image rendering in PDF generation**: `ImageNodeRenderer` renders image blocks in generated PDFs. Supports pixel and percentage dimensions with proportional auto-scaling. Uses an `AssetResolver` interface to decouple the generation module from asset storage.
- **Static resource cache busting**: Content-hash based URLs for CSS, JS, and SVG assets (e.g. `/css/main-abc123.css`). Combined with 1-year cache headers, browsers cache aggressively but always get fresh content after deployments. Uses Spring Boot's `VersionResourceResolver` — no build-time file renaming needed. Disabled in local dev profile for fast iteration.

### Fixed

- **Drag-and-drop bypasses onBeforeInsert hooks**: Dragging a component (image, table, datatable) from the palette onto the canvas now triggers the `onBeforeInsert` hook (e.g. asset picker dialog, table config). Previously, `handleDrop` called `createNode()` directly, inserting nodes with default/null props. Clicking an image placeholder or an existing image on the canvas now opens the asset picker to (re-)select an image.
- **Intermittent integration test hangs**: Added `@PreDestroy` lifecycle management to `JobPoller`, `StaleJobRecovery`, and `PartitionMaintenanceScheduler`. `JobPoller` now shuts down its virtual thread and drain executors on context close, preventing in-flight jobs from blocking on a closed HikariCP pool. Replaced `Thread.sleep` polling in `awaitIdle()` with a `CountDownLatch` for immediate wakeup. All scheduled components now guard against execution during shutdown.
- **Flyway `clean-on-validation-error` removed in Flyway 10+**: Replaced the deprecated YAML property with a programmatic `FlywayMigrationStrategy` that catches `FlywayValidateException` and auto-cleans when `clean-disabled=false`. Production (`clean-disabled=true`) re-throws validation errors as before.

### Changed

- **PDF/A-2b compliance**: PDF/A-2b (ISO 19005-2 Level B) is now available as an opt-in per-template setting (default: off). When enabled, fonts are embedded (Liberation Sans), an sRGB ICC output intent is included, and XMP metadata is written. Templates that don't need archival compliance use standard PDF with non-embedded Helvetica for smaller, faster output. Preview rendering always uses standard PDF regardless of the setting.
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
- **Flyway migration restructuring**: Renumbered and consolidated migrations V1-V10 into V1-V8 for cleaner dependency ordering. Users are now created before tenants (enabling FK-based audit columns), themes use composite PK `(tenant_key, id)` consistent with all other tenant-scoped tables, and user audit columns (`created_by`, `last_modified_by`) are inlined into CREATE TABLE statements instead of added via ALTER TABLE. Old V9 (user audit fields) is eliminated. PostgreSQL domain types (`TENANT_ID`, `THEME_ID`, `TEMPLATE_ID`, `VARIANT_ID`, `ENVIRONMENT_ID`) replace raw VARCHAR declarations for slug-based identifiers, embedding the slug pattern CHECK constraint in a single place. Existing local databases require drop+recreate.
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
- **Tenant-Scoped Composite Primary Keys**: All tenant-owned entities (`document_templates`, `template_variants`, `environments`, `template_versions`, `environment_activations`) now use composite primary keys `(tenant_key, id)`, allowing different tenants to reuse the same slugs (e.g., both can have a template called "invoice")
  - `TemplateVariant` domain model now includes `tenantId`
  - Removed JOIN-based tenant isolation in favor of direct `tenant_key` columns on each table
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
    - Clear layering: Core → REST API → App
    - epistola-core is pure business logic (no HTTP dependencies)
    - REST API can be deployed independently
    - Tests live with the code they test
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
  - Domain logic (tenants, templates, documents, themes, environments)
  - CQRS mediator pattern (commands, queries, handlers)
  - JDBI configuration and database access
  - Database migrations (`db/migration/*.sql`)
  - Domain integration tests (42 tests)
- **rest-api** contains:
  - OpenAPI specification files
  - REST API controllers (@RestController)
  - DTO mappers
  - External system integration layer
- **apps/epistola** contains:
  - Thymeleaf templates and HTMX
  - UI handlers (functional routing)
  - HTTP/UI integration tests (36 tests)

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
  - Database: `tenant_key` columns changed from `UUID` to `VARCHAR(63)` with CHECK constraint
  - API: `tenantId` path parameters changed from UUID to string with pattern validation
  - Generic `EntityId<T, V>` architecture: `SlugId<T>` for string IDs, `UuidId<T>` for UUID IDs
- **BREAKING: ThemeId changed from UUID to slug format**: Theme IDs are now human-readable, URL-safe slugs instead of UUIDs
  - Format: 3-20 lowercase characters, letters (a-z), numbers (0-9), and hyphens (-)
  - Must start with a letter, cannot end with hyphen, no consecutive hyphens
  - Pattern: `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`
  - Theme IDs must now be client-provided (no auto-generation via `ThemeId.generate()`)
  - Examples: `corporate`, `modern`, `my-theme-2024`
  - Database: `theme_key` columns changed from `UUID` to `VARCHAR(20)` with CHECK constraint
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
  - Database: `template_key` columns changed from `UUID` to `VARCHAR(50)` with CHECK constraint
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
  - Database: `variant_key` columns changed from `UUID` to `VARCHAR(50)` with CHECK constraint
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
    - Changed primary key from `id` to composite `(variant_key, id)`
    - Foreign keys now use composite references: `(variant_key, version_key)`
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
  - Database: `environment_key` columns changed from `UUID` to `VARCHAR(30)` with CHECK constraint
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
  - Removed Spring Batch dependency and all BATCH\_\* database tables
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
  - Database migrations create `tenants` table and add `tenant_key` to `document_templates`
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
