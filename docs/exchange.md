# Catalogs & Resource Exchange

Catalogs are first-class entities in Epistola for organizing, sharing, and importing resources. A catalog groups templates, themes, stencils, attributes, and assets under a common identity. Every resource belongs to exactly one catalog.

## Concepts

### Catalog Types

- **Authored**: Created within the Epistola instance. Resources are authored and managed here. Can be exported as ZIP archives.
- **Subscribed**: Sourced from a remote URL. Resources are installed from the remote catalog. The installed release version is tracked for upgrade detection.

Every tenant has a `default` catalog (authored) that cannot be deleted. Additional catalogs can be created or subscribed to.

### Resource Types

Catalogs contain five resource types, installed in dependency order:

1. **Assets** — Binary images (PNG, JPEG, WebP, SVG) used in templates
2. **Attributes** — Variant attribute definitions (e.g., language, region)
3. **Themes** — Document styles, page settings, block style presets
4. **Stencils** — Reusable content blocks with versioning
5. **Templates** — Document templates with variants, versions, and data contracts

### Catalog Scoping

All catalog-scoped tables use a composite primary key: `(tenant_key, catalog_key, id)`. This means the same slug can exist in multiple catalogs within a tenant. All queries filter by `catalog_key` to ensure isolation.

### Read-Only Enforcement

Resources in subscribed catalogs are read-only. All mutating command handlers call `requireCatalogEditable()` which checks `IsCatalogEditable` (verifying the catalog type is `AUTHORED`). The UI hides edit/delete controls and shows a "Read-only" badge for subscribed resources.

During catalog import, the `CatalogImportContext` ScopedValue bypasses editability checks so that `InstallFromCatalog` can write resources into subscribed catalogs.

## Architecture

All catalog logic lives in `modules/epistola-core` under `app.epistola.suite.catalog`:

```
catalog/
  Catalog.kt                      # Data model (CatalogType, AuthType)
  CatalogClient.kt                # HTTP/file/classpath fetching
  CatalogImportContext.kt          # ScopedValue for import bypass
  CatalogReadOnlyException.kt     # requireCatalogEditable()
  DependencyResolver.kt           # Transitive dependency resolution
  DependencyScanner.kt            # Scan template model for refs
  commands/
    CreateCatalog.kt               # Create authored catalog
    RegisterCatalog.kt             # Subscribe to remote catalog
    UnregisterCatalog.kt           # Remove catalog + all resources
    InstallFromCatalog.kt          # Import orchestrator
    ExportCatalog.kt               # Export by template dependencies
    ExportCatalogZip.kt            # Export entire catalog as ZIP
    Import*.kt                     # Per-type importers
  queries/
    BrowseCatalog.kt               # List resources in catalog
    ExportAssets.kt                # Query assets for export
    ExportResources.kt             # Query themes/stencils/attributes for export
    GetCatalog.kt, ListCatalogs.kt
    PreviewInstall.kt              # Dependency resolution preview
  protocol/
    CatalogManifest.kt            # Wire format
    ResourceDetail.kt             # Resource detail wire format
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
| `installed_release_version` | varchar(50)       | Installed release version. For `SUBSCRIBED` only. |
| `installed_at`              | timestamptz       | Last install/upgrade timestamp.                   |
| `created_at`                | timestamptz       | Creation timestamp.                               |
| `last_modified`             | timestamptz       | Last modification timestamp.                      |

Primary key: `(tenant_key, id)`.

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
  "schemaVersion": 2,
  "catalog": {
    "slug": "acme-templates",
    "name": "Acme Corp Templates",
    "description": "Official templates for Acme Corp documents."
  },
  "publisher": {
    "name": "Acme Corp"
  },
  "release": {
    "version": "1.0"
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

| Field                       | Type    | Required    | Description                                                                               |
| --------------------------- | ------- | ----------- | ----------------------------------------------------------------------------------------- |
| `schemaVersion`             | integer | yes         | Protocol version. Currently `2`.                                                          |
| `catalog.slug`              | string  | yes         | Catalog identifier (URL-safe slug).                                                       |
| `catalog.name`              | string  | yes         | Display name.                                                                             |
| `publisher.name`            | string  | yes         | Publisher name.                                                                           |
| `release.version`           | string  | yes         | Release version label.                                                                    |
| `resources`                 | array   | yes         | List of available resources.                                                              |
| `resources[].type`          | string  | yes         | `template`, `theme`, `stencil`, `attribute`, or `asset`.                                  |
| `resources[].slug`          | string  | yes         | Unique identifier within the catalog.                                                     |
| `resources[].name`          | string  | yes         | Display name.                                                                             |
| `resources[].detailUrl`     | string  | yes         | URL to the resource detail JSON. Relative to the manifest URL.                            |
| `dependencies`              | array   | no          | Cross-catalog dependencies. Import is blocked if these are missing.                       |
| `dependencies[].type`       | string  | yes         | `theme`, `stencil`, or `asset`.                                                           |
| `dependencies[].catalogKey` | string  | conditional | Source catalog slug. Required for themes and stencils. Absent for assets (tenant-global). |
| `dependencies[].slug`       | string  | yes         | Resource identifier in the source catalog (or asset UUID).                                |

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
- **StencilResource**: `content` (TemplateDocument), `tags`
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

Cross-catalog dependencies are automatically detected during export by scanning template models against the catalog's own resource list. Any external reference is added to the `dependencies` field in `catalog.json`.

Configurable size limits protect against oversized exports:

- `epistola.catalog.max-zip-size`: Maximum compressed ZIP size (default 10MB)
- `epistola.catalog.max-decompressed-size`: Maximum decompressed size (default 20MB)

### ZIP Import

`ImportCatalogZip` imports a catalog from a ZIP archive:

- If the catalog slug already exists and is AUTHORED: resources are updated in place
- If the catalog slug already exists and is SUBSCRIBED: the import is rejected
- If the catalog slug is new: a new catalog is created with the specified type

Available via:

- UI: `POST /tenants/{tenantId}/catalogs/import` (multipart form)
- REST API: `POST /api/tenants/{tenantId}/catalogs/import` (multipart, API key auth)

The REST API endpoint is used by the Valtimo plugin for catalog sync on startup.

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
