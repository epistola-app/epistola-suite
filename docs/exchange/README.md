# Catalogs & Resource Exchange

Catalogs are first-class entities in Epistola for organizing, sharing, and importing resources. A catalog groups templates, themes, stencils, attributes, and assets under a common identity. Every resource belongs to exactly one catalog.

> **This page is the root of the exchange docs.** The architecture, data model, protocol, and import/export flows are below. The **wire contract of each part** is documented separately and versioned per part — see the next section.

## Parts & contract versions

Import/export is a **wire format**: a `catalog.json` [manifest](manifest/v4/contract.md) plus one detail file per resource. Each of these is a **part** with its own JSON contract. Every part is documented in its own folder, with a version subfolder per contract version, so a contract change is a new folder you can diff against the previous one.

**Each part is versioned independently.** The manifest is just another versioned part — there is no single "whole-catalog" version that the parts move together. A catalog is at a _set_ of part versions (e.g. manifest `v4`, template `v2`, attribute `v3`); changing one part's shape bumps only that part. This is the decision recorded in [ADR 0006](../adr/0006-catalog-wire-format-migrations.md), whose JSON-tree migration mechanism applies one chain per part.

**Current contract of each part:**

| Part                                                 | Current | Versions                                       | Carries                                                                           |
| ---------------------------------------------------- | ------- | ---------------------------------------------- | --------------------------------------------------------------------------------- |
| [Manifest](manifest/v4/contract.md) (`catalog.json`) | **v4**  | [`manifest/`](manifest/)                       | catalog identity, `release` (version + fingerprint), resource index, dependencies |
| [Asset](resources/asset/v2/contract.md)              | **v2**  | [`resources/asset/`](resources/asset/)         | image metadata + `contentUrl` to the binary                                       |
| [Code list](resources/code-list/v3/contract.md)      | **v3**  | [`resources/code-list/`](resources/code-list/) | reusable enumerations (`entries`)                                                 |
| [Font](resources/font/v1/contract.md)                | **v1**  | [`resources/font/`](resources/font/)           | asset-backed font family + variants                                               |
| [Attribute](resources/attribute/v3/contract.md)      | **v3**  | [`resources/attribute/`](resources/attribute/) | variant attribute + `codeListBinding`                                             |
| [Theme](resources/theme/v2/contract.md)              | **v2**  | [`resources/theme/`](resources/theme/)         | document styles, page settings, presets                                           |
| [Stencil](resources/stencil/v2/contract.md)          | **v2**  | [`resources/stencil/`](resources/stencil/)     | published fragment (`content` + `version`)                                        |
| [Template](resources/template/v2/contract.md)        | **v2**  | [`resources/template/`](resources/template/)   | `templateModel`, `dataModel`, variants                                            |

(Rows are in install order — see [`CatalogConstants.RESOURCE_INSTALL_ORDER`](../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogConstants.kt).)

**The three version axes** (orthogonal — don't conflate them):

| Axis                         | What it versions                                                     | Where                                               | Reference                                                 |
| ---------------------------- | -------------------------------------------------------------------- | --------------------------------------------------- | --------------------------------------------------------- |
| **Part `schemaVersion`**     | the JSON _shape_ of one part (manifest or a resource type), per part | each part's `schemaVersion`; the `vN/` folders here | [ADR 0006](../adr/0006-catalog-wire-format-migrations.md) |
| **Release version** (SemVer) | the catalog _content_ at a point in time                             | `release.version`, `catalog_releases`               | [catalog-versioning.md](../catalog-versioning.md)         |
| **Content fingerprint**      | content _identity_ (did anything change?)                            | `release.fingerprint`                               | [catalog-versioning.md](../catalog-versioning.md)         |

These three are the **catalog-level** axes. For the full picture — including the editorial **template / contract / stencil** versions and how they do (and don't) relate to import/export — see [version-axes.md](../version-axes.md).

**Maintaining these docs:** when you change a part's wire shape, add a new `vN+1/contract.md` for **that part only**, update its row's _Current_ above, and note the change under "Changed in vN+1". Leave the old version folder in place so the diff stays visible.

> **Implementation status.** Per-part versioning is **implemented** ([ADR 0006](../adr/0006-catalog-wire-format-migrations.md)): export stamps each detail with its own part's version (`CatalogContentBuilder`), and `CatalogSchemaMigrator` gates/migrates each part by its own `schemaVersion` against that part's window — wired at **both** import chokepoints (the ZIP path and `CatalogClient`, manifest and resource details). See [Wire-format version gate](#wire-format-version-gate). The per-part numbers above are the canonical contract versions (matching the bundled fixtures); a part's chain stays empty (`baseline == current`) until its shape changes, at which point a migration is added to that part's chain. Roadmap: [`plans/catalog-wire-format-migrations.md`](../plans/catalog-wire-format-migrations.md).

## Concepts

### Catalog Types

- **Authored**: Created within the Epistola instance. Resources are authored and managed here. Can be exported as ZIP archives.
- **Subscribed**: Sourced from a remote URL. Resources are installed from the remote catalog. The installed release version is tracked for upgrade detection.

Every tenant has a `default` catalog (authored) that cannot be deleted. Additional catalogs can be created or subscribed to.

### Resource Types

Catalogs contain seven resource types, installed in dependency order (see [`RESOURCE_INSTALL_ORDER`](../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogConstants.kt)):

1. **Assets** — Binary images (PNG, JPEG, WebP, SVG) used in templates
2. **Code lists** — Reusable enumerations (e.g. BCP-47 languages) bound by attributes
3. **Fonts** — Asset-backed font families (system/`CLASSPATH` fonts are not exchanged)
4. **Attributes** — Variant attribute definitions (e.g., language, region)
5. **Themes** — Document styles, page settings, block style presets
6. **Stencils** — Reusable content blocks with versioning
7. **Templates** — Document templates with variants, versions, and data contracts

The wire contract of each (and the manifest) is documented per part under [Parts & contract versions](#parts--contract-versions).

### Catalog Scoping

All catalog-scoped tables use a composite primary key: `(tenant_key, catalog_key, id)`. This means the same slug can exist in multiple catalogs within a tenant. All queries filter by `catalog_key` to ensure isolation.

### Read-Only Enforcement

Resources in subscribed catalogs are read-only. All mutating command handlers call `requireCatalogEditable()` which checks `IsCatalogEditable` (verifying the catalog type is `AUTHORED`). The UI hides edit/delete controls and shows a "Read-only" badge for subscribed resources.

During catalog import, the `CatalogImportContext` ScopedValue bypasses editability checks so that `InstallFromCatalog` can write resources into subscribed catalogs.

## Architecture

All catalog logic lives in `modules/epistola-core` under `app.epistola.suite.catalog`:

```
catalog/
  Catalog.kt                          # Data model (CatalogType, AuthType)
  CatalogClient.kt                    # HTTP/file/classpath fetching
  CatalogImportContext.kt             # ScopedValue for import bypass
  CatalogReadOnlyException.kt         # requireCatalogEditable()
  DependencyResolver.kt               # Transitive dependency resolution
  DependencyScanner.kt                # Scan template model for refs
  MultipleStencilVersionsInUseException.kt  # Export-side stencil pin drift
  commands/
    CreateCatalog.kt                  # Create authored catalog
    RegisterCatalog.kt                # Subscribe to remote catalog
    UnregisterCatalog.kt              # Remove catalog + all resources
    InstallFromCatalog.kt             # Import orchestrator
    ExportCatalog.kt                  # Export by template dependencies
    ExportCatalogZip.kt               # Export entire catalog as ZIP
    OnStencilConflict.kt              # Import conflict policy + renumber map
    Import*.kt                        # Per-type importers
  queries/
    BrowseCatalog.kt                  # List resources in catalog
    ExportAssets.kt                   # Query assets for export
    ExportResources.kt                # Query themes/stencils/attributes for export
    FindStencilVersionExportConflicts.kt  # Export precheck
    GetCatalog.kt, ListCatalogs.kt
    PreviewInstall.kt                 # Dependency resolution preview
  protocol/
    CatalogManifest.kt                # Wire format
    ResourceDetail.kt                 # Resource detail wire format
```

UI handlers and routes are in `apps/epistola`:

- `CatalogHandler.kt` — list, create, register, unregister, browse, install, export
- `CatalogRoutes.kt` — route bindings under `/tenants/{tenantId}/catalogs`

## Data Model

### `catalogs`

| Field                       | Type              | Description                                       |
| --------------------------- | ----------------- | ------------------------------------------------- |
| `id`                        | CatalogKey (slug) | URL-safe slug. Unique per tenant.                 |
| `tenant_key`                | TenantKey         | Owning tenant.                                    |
| `name`                      | varchar(255)      | Display name.                                     |
| `description`               | text, nullable    | Optional description.                             |
| `type`                      | varchar(20)       | `AUTHORED` or `SUBSCRIBED`.                       |
| `source_url`                | text, nullable    | Remote catalog URL. Required for `SUBSCRIBED`.    |
| `source_auth_type`          | varchar(20)       | `NONE`, `API_KEY`, or `BEARER`.                   |
| `source_auth_credential`    | text, nullable    | Credential for authenticated remote catalogs.     |
| `installed_release_version` | varchar(50)       | Installed release version. `SUBSCRIBED` only.     |
| `installed_fingerprint`     | char(64)          | Installed content fingerprint. `SUBSCRIBED` only. |
| `installed_at`              | timestamptz       | Last install/upgrade timestamp.                   |
| `released_version`          | varchar(50)       | Latest released SemVer. `AUTHORED` only.          |
| `released_fingerprint`      | char(64)          | Latest released content fingerprint. `AUTHORED`.  |
| `released_at`               | timestamptz       | When the latest `AUTHORED` release was cut.       |
| `created_at`                | timestamptz       | Creation timestamp.                               |
| `updated_at`                | timestamptz       | Last modification timestamp.                      |

Primary key: `(tenant_key, id)`.

### `catalog_releases`

Immutable release history for `AUTHORED` catalogs — one row per **Release
version** action. Not parallel installs; the changelog + Phase 2 upgrade-diff
source. PK `(tenant_key, catalog_key, version)`, columns `version` (SemVer),
`fingerprint`, `notes`, `manifest_snapshot` (jsonb), `released_at`,
`released_by`. See [`catalog-versioning.md`](../catalog-versioning.md).

### Resource Tables

Resources have `catalog_key` as part of their primary key:

- `document_templates (tenant_key, catalog_key, id)`
- `template_variants (tenant_key, catalog_key, template_key, id)`
- `template_versions (tenant_key, catalog_key, template_key, variant_key, id)`
- `themes (tenant_key, catalog_key, id)`
- `stencils (tenant_key, catalog_key, id)`
- `stencil_versions (tenant_key, catalog_key, stencil_key, id)`
- `variant_attribute_definitions (tenant_key, catalog_key, id)`
- `assets (tenant_key, catalog_key, id)` — Note: assets are catalog-scoped for ownership but can be referenced by any template in the tenant.

## Protocol Specification

The catalog exchange protocol defines the wire format for sharing catalogs between Epistola instances. It is used for remote catalog URLs, ZIP exports, and classpath-bundled demo catalogs.

### Catalog Manifest (`catalog.json`)

```json
{
  "schemaVersion": 4,
  "catalog": {
    "slug": "acme-templates",
    "name": "Acme Corp Templates",
    "description": "Official templates for Acme Corp documents."
  },
  "publisher": {
    "name": "Acme Corp"
  },
  "release": {
    "version": "1.0.0",
    "fingerprint": "9f3a1c2b…"
  },
  "resources": [
    {
      "type": "template",
      "slug": "invoice-standard",
      "name": "Standard Invoice",
      "detailUrl": "./resources/template/invoice-standard.json"
    },
    {
      "type": "theme",
      "slug": "corporate",
      "name": "Corporate Theme",
      "detailUrl": "./resources/theme/corporate.json"
    },
    {
      "type": "asset",
      "slug": "01966a00-0000-7000-8000-000000000001",
      "name": "Company Logo",
      "detailUrl": "./resources/asset/01966a00-0000-7000-8000-000000000001.json"
    }
  ],
  "dependencies": [
    { "type": "theme", "catalogKey": "shared-styles", "slug": "base-theme" },
    { "type": "stencil", "catalogKey": "shared-components", "slug": "company-header" },
    { "type": "asset", "slug": "01966a00-0000-7000-8000-000000000002" }
  ]
}
```

| Field                       | Type    | Required    | Description                                                                                                                                               |
| --------------------------- | ------- | ----------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `schemaVersion`             | integer | yes         | Protocol version. Currently `4`.                                                                                                                          |
| `catalog.slug`              | string  | yes         | Catalog identifier (URL-safe slug).                                                                                                                       |
| `catalog.name`              | string  | yes         | Display name.                                                                                                                                             |
| `publisher.name`            | string  | yes         | Publisher name.                                                                                                                                           |
| `release.version`           | string  | yes         | Release version label. SemVer (`MAJOR.MINOR.PATCH`) for versioned catalogs; `-dev`-suffixed when exported from a drifted/never-released working copy.     |
| `release.fingerprint`       | string  | no          | Lowercase hex SHA-256 of the catalog's canonical content. Drives content-based change detection. See [`catalog-versioning.md`](../catalog-versioning.md). |
| `resources`                 | array   | yes         | List of available resources.                                                                                                                              |
| `resources[].type`          | string  | yes         | `template`, `theme`, `stencil`, `attribute`, or `asset`.                                                                                                  |
| `resources[].slug`          | string  | yes         | Unique identifier within the catalog.                                                                                                                     |
| `resources[].name`          | string  | yes         | Display name.                                                                                                                                             |
| `resources[].detailUrl`     | string  | yes         | URL to the resource detail JSON. Relative to the manifest URL.                                                                                            |
| `dependencies`              | array   | no          | Cross-catalog dependencies. Import is blocked if these are missing.                                                                                       |
| `dependencies[].type`       | string  | yes         | `theme`, `stencil`, or `asset`.                                                                                                                           |
| `dependencies[].catalogKey` | string  | conditional | Source catalog slug. Required for themes and stencils. Absent for assets (tenant-global).                                                                 |
| `dependencies[].slug`       | string  | yes         | Resource identifier in the source catalog (or asset UUID).                                                                                                |

### Cross-Catalog Dependencies

Templates may reference resources from other catalogs:

- **Themes**: via `themeRef.catalogKey` in the template model
- **Stencils**: via `node.props.catalogKey` in stencil nodes
- **Assets**: via `node.props.assetId` in image nodes (tenant-global, no catalog needed)

During export, Epistola scans all template models and compares references against the catalog's own resources. Any reference to a resource NOT in the catalog is added to the `dependencies` list.

During import, Epistola validates that all declared dependencies exist in the target tenant before installing. If any are missing, the import is rejected with a clear error listing what's needed.

Dependencies use a sealed type hierarchy:

| Type      | Fields               | Scope                                                |
| --------- | -------------------- | ---------------------------------------------------- |
| `theme`   | `catalogKey`, `slug` | Catalog-scoped — must exist in the specified catalog |
| `stencil` | `catalogKey`, `slug` | Catalog-scoped — must exist in the specified catalog |
| `asset`   | `slug` (UUID)        | Tenant-global — must exist anywhere in the tenant    |

### Resource Detail Files

Each resource has a detail JSON file (`./resources/{type}/{slug}.json`) containing the full resource payload:

```json
{
  "schemaVersion": 2,
  "resource": {
    "type": "template",
    "slug": "invoice-standard",
    "name": "Standard Invoice",
    "templateModel": { ... },
    "variants": [ ... ],
    "dataModel": { ... },
    "dataExamples": [ ... ]
  }
}
```

Resource types use Jackson polymorphic serialization (`@JsonTypeInfo` on `CatalogResource`):

- **TemplateResource**: `templateModel`, `variants` (with per-variant `templateModel`, `attributes`, `isDefault`), `dataModel`, `dataExamples`
- **ThemeResource**: `documentStyles`, `pageSettings`, `blockStylePresets`, `spacingUnit`
- **StencilResource**: `content` (TemplateDocument), `version` (Int, required — the published version number of the carried content), `tags`
- **AttributeResource**: `allowedValues`
- **AssetResource**: `mediaType`, `width`, `height`, `contentUrl` (relative path to binary file)

### Asset Binary Content

Asset resources reference their binary content via `contentUrl`:

```json
{
  "type": "asset",
  "slug": "01966a00-0000-7000-8000-000000000001",
  "name": "Company Logo",
  "mediaType": "image/png",
  "contentUrl": "./resources/assets/01966a00-0000-7000-8000-000000000001"
}
```

The `contentUrl` is resolved relative to the manifest URL. For ZIP exports, the binary file is stored at the path directly (no extension). For HTTP-served catalogs, it's a URL endpoint.

### URL Schemes

`CatalogClient` supports multiple URL schemes for fetching catalogs:

| Scheme       | Usage                                                        |
| ------------ | ------------------------------------------------------------ |
| `https://`   | Remote catalogs (production)                                 |
| `http://`    | Remote catalogs (only if `epistola.catalog.allow-http=true`) |
| `file://`    | Local filesystem                                             |
| `classpath:` | Bundled demo catalogs                                        |

Relative `detailUrl` and `contentUrl` values are resolved against the manifest URL. Path traversal (`..`) is rejected.

### Authentication

Remote catalogs support three authentication types:

- `NONE` — No authentication
- `API_KEY` — Sent as `X-API-Key` header
- `BEARER` — Sent as `Authorization: Bearer <token>` header

## Import Flow

`InstallFromCatalog` orchestrates the import process:

1. Fetch catalog metadata from DB (source URL, auth type, credential)
2. Fetch remote manifest via `CatalogClient.fetchManifest()`
3. Filter resources by optional slug list (or install all)
4. Resolve transitive dependencies via `DependencyResolver`
5. Sort by install order: assets, attributes, themes, stencils, templates
6. For each resource, fetch the detail JSON and call the type-specific importer
7. Asset binaries are fetched separately via `CatalogClient.fetchBinaryContent()`

Before installing, the import validates that all declared cross-catalog dependencies exist in the target tenant. If any are missing, the import is rejected with a descriptive error.

The import runs within `CatalogImportContext.runAsImport {}` to bypass editability checks on the subscribed catalog.

### Wire-format version gate

Every part (the manifest and each resource detail) is gated by **its own** `schemaVersion` against that part's window in `CATALOG_PART_SCHEMAS` before it is bound (per-part versioning — [ADR 0006](../adr/0006-catalog-wire-format-migrations.md)). `CatalogSchemaMigrator` decides per part:

| Part `schemaVersion`     | Behaviour                                                                                                               |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| `== current`             | Bind directly (fast path).                                                                                              |
| `baseline ≤ v < current` | Run that part's migration chain `v → … → current`, then bind.                                                           |
| `> current`              | **Reject** — `CatalogSchemaTooNewException` ("exported by a newer Epistola; upgrade this instance").                    |
| `< baseline`             | **Reject** — `CatalogSchemaTooOldException` ("predates the oldest supported version; re-export from a current source"). |
| missing / non-integer    | **Reject** — `CatalogSchemaUnknownException` (not a recognised catalog wire payload).                                   |

The gate runs at both import chokepoints (the ZIP path and `CatalogClient`), so browse / preview / upgrade-check see migrated content too. Migration never recomputes `release.fingerprint` — see [catalog-versioning.md](../catalog-versioning.md#fingerprint-algorithm).

**Operator surfaces for a rejected payload:**

- **Web UI** (import dialog): the rejection is rendered inline in the dialog (the same `alert-error` slot as a stencil conflict) with the actionable remediation message — upgrade this instance, or re-export from a current source.
- **REST** (`POST /api/tenants/{id}/catalogs/import`): an [RFC 9457](../adr/0004-rfc7807-problem-details.md) problem with a dedicated `type` — `…/errors/catalog-schema-too-new` (extensions `version`, `supportedVersion`), `…/catalog-schema-too-old` (`version`, `baselineVersion`), or `…/catalog-schema-unknown` — all `400`.
- **MCP**: catalog import is not an MCP surface (the MCP server is read-only), so there is no MCP change.

### Dependency Resolution

`DependencyScanner` scans template models for references to other resources:

- **Themes**: via `themeRef` overrides (with `catalogKey`)
- **Stencils**: via `stencilId` in stencil node props (with `catalogKey`)
- **Assets**: via `assetId` in image node props (UUID)
- **Attributes**: via variant attribute keys

`DependencyResolver` takes selected resources and the full manifest, then iteratively adds missing dependencies until the set is complete. It validates that all dependencies exist in the manifest.

## Export

### ZIP Export

`ExportCatalogZip` exports all resources in a catalog as a self-contained ZIP:

```
catalog.json
resources/
  template/hello-world.json
  theme/corporate.json
  stencil/company-header.json
  attribute/language.json
  asset/01966a00-0000-7000-8000-000000000001.json
  assets/01966a00-0000-7000-8000-000000000001      (binary)
```

The ZIP structure matches the protocol layout, so it can be served as a static catalog. Available via `GET /tenants/{tenantId}/catalogs/{catalogId}/export`.

Both authored and subscribed catalogs can be exported. For authored catalogs, all resources in the catalog are included. For subscribed catalogs, all installed resources are included.

The export stamps `release.version` with the catalog's latest released SemVer
and `release.fingerprint` with the fingerprint of the actual exported bytes. A
never-released catalog exports as `0.0.0-dev`; a working copy that drifted from
its latest release exports as `<version>-dev`. Catalog-version drift is never
blocked — see [`catalog-versioning.md`](../catalog-versioning.md). Stencil-version
drift _is_ blocked at export time — see [Stencil version
preservation](#stencil-version-preservation) below.

Cross-catalog dependencies are automatically detected during export by scanning template models against the catalog's own resource list. Any external reference is added to the `dependencies` field in `catalog.json`.

Configurable size limits protect against oversized exports:

- `epistola.catalog.max-zip-size`: Maximum compressed ZIP size (default 10MB)
- `epistola.catalog.max-decompressed-size`: Maximum decompressed size (default 20MB)

### Stencil version preservation

Templates reference stencils through a stencil node whose
`props.version: Int` pins the consumer to a specific published version of
that stencil. To preserve those pins across an export/import round-trip, the
wire format carries the published version number alongside the content
(`StencilResource.version`, required since `epistola-model 0.6.0`). The
exporter ships the **latest** published version of each stencil and stamps
its number; the importer installs the stencil at that exact number in
target. The architecture and rationale are recorded in
[ADR 0003](../adr/0003-stencil-version-in-export.md).

#### Export precheck

Because each catalog ZIP carries only the latest published version of each
stencil, every template in the catalog must pin **that** version for the
round-trip to be safe. Before assembling the ZIP, `ExportCatalogZip` runs
`FindStencilVersionExportConflicts` over the catalog's latest-published
template versions and flags any own-catalog stencil where some template
pins a version other than the latest published. The check fires in two
failure modes — folded into the same rejection:

- **Inconsistent** — different templates pin different versions of the same
  stencil.
- **Stale** — every template pins the same version, but that version is not
  the latest published.

When the check finds anything, the export is blocked with
`MultipleStencilVersionsInUseException` carrying every affected stencil
along with the pinned versions and the latest-published version. The UI
surfaces this:

- The **catalog browse** view marks every affected stencil with a
  `badge-warning` (`pinned vX (latest vY)`) so the operator sees the
  problem at-a-glance before clicking export.
- The **Export as ZIP** button runs a precheck endpoint
  (`GET /tenants/{tenantId}/catalogs/{catalogId}/export-check`) that
  either returns `HX-Redirect` to the real download URL (no conflicts) or
  opens a modal listing the affected stencils with remediation guidance
  (republish the templates against the latest stencil version).

To fix a conflict, open the affected templates and republish them with the
stencil instance upgraded to the latest published version. No content needs
to be re-typed — the upgrade is structural.

The exception is also thrown by `ExportCatalogZip` itself, so REST or MCP
callers that hit `/api/tenants/{tenantId}/catalogs/{catalogId}/export`
directly (without going through the UI precheck) still get the same hard
block as a JSON 400. The cheap conflict check runs before any ZIP bytes
are assembled.

### ZIP Import

`ImportCatalogZip` imports a catalog from a ZIP archive:

- If the catalog slug already exists and is AUTHORED: resources are updated in place
- If the catalog slug already exists and is SUBSCRIBED: the import is rejected
- If the catalog slug is new: a new catalog is created with the specified type

Available via:

- UI: `POST /tenants/{tenantId}/catalogs/import` (multipart form)
- REST API: `POST /api/tenants/{tenantId}/catalogs/import` (multipart, API key auth)

The REST API endpoint is used by the Valtimo plugin for catalog sync on startup.

### Stencil version conflict handling

`ImportStencil` installs each stencil at the version number carried on the
wire (`StencilResource.version`), not at `MAX(target.version) + 1`. This
keeps templates from the same ZIP that pinned that version still resolving
in target. Pre-`0.6.0` ZIPs lack the field, fail Jackson deserialisation,
and abort the import explicitly — they must be re-exported before they can
be imported. There is no compatibility shim.

For each `(slug, version)` pair carried in the ZIP, the importer checks
target for a row with the same key:

| Target state                                    | Behaviour                                                                                                    |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Missing                                         | Install at the carried version. `InstallStatus.INSTALLED` (or `UPDATED` if the stencil row already existed). |
| Present, **byte-identical** content (JSONB `=`) | No-op; idempotent. `InstallStatus.SKIPPED`. A re-import of an unchanged ZIP never churns.                    |
| Present, **different** content                  | **Conflict** — resolution depends on `onStencilConflict` (see below).                                        |

Conflicts are collected in one read-only pre-scan **before any mutation** so
the operator sees every affected stencil at once rather than failing on the
first one.

#### Resolution policy: `OnStencilConflict`

`ImportCatalogZip` takes an `onStencilConflict: OnStencilConflict`
parameter that controls what happens when a conflict is detected. The
default is `FAIL`. The `RENUMBER` mode is opt-in and **only valid for
AUTHORED MERGE** imports; the orchestrator rejects it for SUBSCRIBED and
AUTHORED REPLACE up front with a clear `IllegalArgumentException`. Those
modes are mirror semantics — letting the target diverge from source would
contradict their import contract.

##### `FAIL` (default)

The whole import aborts before any mutation. The catalog is left exactly
as it was. The operator receives a `StencilVersionImportConflictsException`
listing every conflicting stencil:

```text
Cannot import catalog 'shared': 2 stencil version-conflict(s) —
  'company-header' v3
  'letter-shell' v2
```

The web UI renders this inline in the Import dialog as a structured alert
that names every affected stencil. To resolve, the operator either:

1. Fixes the divergence at source (republishing the source catalog so the
   conflicting versions match target's, then re-exporting), or
2. Re-uploads the ZIP with **Allow renumber on stencil version conflict**
   enabled (see below).

##### `RENUMBER` (opt-in, AUTHORED MERGE only)

For every conflicting stencil, the importer assigns
`MAX(target.stencil_versions.id) + 1` and writes the source content at that
new version. `ImportStencil` reports the assignment back; the orchestrator
collects a `Map<StencilKey, StencilRenumber>` across the install pass and
hands it to `ImportTemplates`.

`ImportTemplates` then walks every imported template document's nodes and
rewrites `props.version` for any stencil node whose `stencilId` is in the
map **and** whose source pin equals the recorded `sourceVersion`. The
templates from the same ZIP now point at the freshly-renumbered target
version. Cross-catalog stencil refs (those with `props.catalogKey !=
ownCatalog`) and templates that were already in target are deliberately not
touched — they continue to resolve their own pre-renumber version.

Worked example. Target has `stencil-a` at `v1` with content `X`. The ZIP
brings `stencil-a` at `v1` with content `Y`, plus a template `T` whose
stencil node pins `stencil-a@v1`:

- Without renumber (FAIL): import aborts, catalog unchanged.
- With renumber: target now has `stencil-a@v1` (still content `X`) and
  `stencil-a@v2` (content `Y`). Template `T` is imported with its pin
  rewritten to `stencil-a@v2`. Any pre-existing template in target that
  pinned `stencil-a@v1` keeps that pin — it still renders content `X`.

##### UI flow

The Import dialog exposes the policy via a checkbox: **Allow renumber on
stencil version conflict** (default off). When the checkbox is off and a
conflict happens, the dialog stays open and the response renders inline
inside `#import-error` as an `alert-error` structured list. The user can
then either close the dialog and fix the source, or re-tick the checkbox
and re-upload the ZIP for a one-shot renumber-and-retry. There is no
server-side stash of the upload bytes; a single-click retry would need one
and is out of scope for this iteration.

For non-UI callers (REST / MCP), the behaviour is identical: the command
takes the same `onStencilConflict` parameter, `FAIL` raises
`StencilVersionImportConflictsException`, and `RENUMBER` performs the
renumber + template rewrite. SUBSCRIBED and AUTHORED REPLACE imports
reject `RENUMBER` with `IllegalArgumentException`.

## UI

### Catalog List (`/tenants/{tenantId}/catalogs`)

- Create authored catalog (dialog)
- Subscribe to remote catalog (dialog with URL, auth type, credential)
- Browse catalog resources
- Export catalog as ZIP (all catalogs)
- Delete catalog (all except default, with confirmation)

### Catalog Browse (`/tenants/{tenantId}/catalogs/{catalogId}/browse`)

- List all resources with type badges
- Install individual resources or all resources
- Dependency resolution preview (shows auto-included dependencies)

### Resource Pages

All resource list pages (templates, themes, stencils, attributes, assets) have:

- Catalog filter dropdown
- Catalog column in tables
- Catalog selector in create forms
- `/{catalogId}/{resourceId}` URL patterns for detail pages

Resources in subscribed catalogs show as read-only with disabled edit/delete controls.
