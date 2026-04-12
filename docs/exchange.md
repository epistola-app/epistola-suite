# Catalogs & Resource Exchange

Catalogs are a first-class entity in Epistola for organizing, versioning, and sharing templates. A catalog groups templates under a common identity and supports releasing versioned snapshots of its contents. Catalogs can be **local** (created within the instance) or **imported** (sourced from a remote URL). Local catalogs can optionally be made public, exposing them via a `.well-known` endpoint for other Epistola instances to consume.

## Concepts

### Catalog

A catalog is a tenant-scoped container that owns a set of templates. Every template belongs to at most one catalog — or to none (standalone templates).

Catalogs have two types:

- **Local**: Created within the Epistola instance. Templates are authored and managed here. Can be released as versioned snapshots and optionally published via `.well-known`.
- **Imported**: Sourced from a remote URL. Templates are installed from the remote catalog and can be upgraded when new releases are published upstream.

### Catalog Release

A release is a versioned, immutable snapshot of a catalog's contents at a point in time. When a catalog owner creates a release, Epistola captures the current state of all templates (including their variants and the latest published version of each) and assigns it a version label.

Releases serve two purposes:

- **For local catalogs**: a release is what gets published. The `.well-known` endpoint and exported catalog files reflect the latest release, not the live state of the templates.
- **For imported catalogs**: a release represents a version available from the upstream source. The installed release version is stored locally so Epistola can detect when a newer release is available.

### Template Ownership

A template belongs to at most one catalog (via the `catalog_templates` join table) or to none (standalone). Templates in a catalog are managed together — they are released, exported, and upgraded as a unit.

When importing a catalog, all templates from that catalog are created under the imported catalog entity. This keeps a clear separation between locally-authored templates and imported ones.

### Source Tracking

Imported catalogs store metadata about their remote source:

- The remote catalog URL
- The installed release version
- When the last install or upgrade happened

This enables detecting available upgrades by comparing the local release version with the remote catalog's latest release.

## Module Architecture

The catalog feature is implemented as an independent module (`modules/epistola-catalog/`) that depends on `epistola-core` but does not modify it. Core has no awareness of catalogs.

```
modules/
  epistola-core/       # Existing — unchanged
  epistola-catalog/    # New — all catalog logic
    src/main/kotlin/
      app/epistola/suite/catalog/
        Catalog.kt                  # Entity
        CatalogRelease.kt           # Entity
        CatalogTemplate.kt          # Entity (join table)
        CatalogClient.kt            # HTTP client for remote catalogs
        commands/                    # CreateCatalog, CreateRelease, ImportCatalog, UpgradeCatalog, ...
        queries/                     # ListCatalogs, GetCatalogDiff, ...
    src/main/resources/
      db/migration/catalog/         # Own Flyway migration path
```

The module interacts with core through:

- **`ImportTemplates` command**: used to upsert templates during catalog install/upgrade.
- **`ExportTemplates` query**: used to build release snapshots from live templates.
- **Domain types**: `TemplateKey`, `TenantKey`, `VariantKey`, etc.
- **JDBI**: joins against `document_templates` for read queries, but never modifies its schema.

The `.well-known` controller and UI handlers live in `apps/epistola` (web layer), calling into the catalog module's commands and queries.

## Data Model

All catalog tables are owned by the catalog module. The core `document_templates` table is **not modified** — the relationship is maintained through a separate join table.

### `catalogs`

| Field                       | Type                  | Description                                                                    |
| --------------------------- | --------------------- | ------------------------------------------------------------------------------ |
| `id`                        | CatalogKey (slug)     | URL-safe slug identifier (3-50 chars). Unique per tenant.                      |
| `tenant_key`                | TenantKey             | Owning tenant. FK to `tenants(id)`.                                            |
| `name`                      | varchar(255)          | Human-readable display name.                                                   |
| `description`               | text, nullable        | Optional description.                                                          |
| `type`                      | varchar(20)           | `LOCAL` or `IMPORTED`.                                                         |
| `source_url`                | text, nullable        | Remote catalog URL. Required for `IMPORTED`, null for `LOCAL`.                 |
| `source_auth_type`          | varchar(20), nullable | `NONE`, `API_KEY`, or `BEARER`. For imported catalogs.                         |
| `source_auth_credential`    | text, nullable        | Encrypted credential for authenticated remote catalogs.                        |
| `public`                    | boolean               | Whether this catalog is exposed via `.well-known`. Only applicable to `LOCAL`. |
| `installed_release_version` | varchar(50), nullable | Version label of the currently installed release. For `IMPORTED` only.         |
| `installed_at`              | timestamptz, nullable | When the catalog was last installed or upgraded. For `IMPORTED` only.          |
| `created_at`                | timestamptz           | Creation timestamp.                                                            |
| `last_modified`             | timestamptz           | Last modification timestamp.                                                   |

Primary key: `(tenant_key, id)`.

### `catalog_releases`

| Field           | Type            | Description                                                 |
| --------------- | --------------- | ----------------------------------------------------------- |
| `id`            | UUID            | Unique identifier.                                          |
| `tenant_key`    | TenantKey       | Owning tenant.                                              |
| `catalog_key`   | CatalogKey      | Parent catalog. FK to `catalogs(tenant_key, id)`.           |
| `version`       | varchar(50)     | Version label. Unique per catalog.                          |
| `snapshot`      | JSONB           | Serialized catalog manifest with embedded resource details. |
| `compatibility` | JSONB, nullable | `{ "epistolaVersions": ">=0.12.0" }` constraint.            |
| `released_at`   | timestamptz     | When this release was created.                              |

Primary key: `(tenant_key, catalog_key, version)`.

The `snapshot` field contains the complete catalog state at release time — all templates, variants, and their template models. This makes releases fully self-contained and immutable.

### `catalog_templates`

This join table links templates to catalogs. It is the only table that references `document_templates`, and it does so via a foreign key — core's schema is never modified.

| Field                   | Type            | Description                                                                                             |
| ----------------------- | --------------- | ------------------------------------------------------------------------------------------------------- |
| `tenant_key`            | TenantKey       | Owning tenant.                                                                                          |
| `catalog_key`           | CatalogKey      | Parent catalog. FK to `catalogs(tenant_key, id)`.                                                       |
| `template_key`          | TemplateKey     | The template. FK to `document_templates(tenant_key, id)`.                                               |
| `catalog_resource_slug` | text            | The slug of this template in the origin catalog. May differ from the local slug if remapped on install. |
| `installed_snapshot`    | JSONB, nullable | Snapshot of the resource payload at install/upgrade time. Used for local change detection.              |

Primary key: `(tenant_key, catalog_key, template_key)`.
Unique constraint: `(tenant_key, template_key)` — a template can belong to at most one catalog.

#### Origin Link

Every template in a catalog maintains a link back to its catalog resource via `catalog_resource_slug`. This link enables:

- **Finding the upstream resource**: given a template, look up the corresponding resource in the catalog's latest release by slug.
- **Slug remapping**: the local template slug can differ from the catalog resource slug. The origin link uses the catalog slug.
- **Detecting removals**: if the catalog resource slug no longer exists in a new release, the template is flagged as "no longer in upstream".

#### Installed Snapshot

The `installed_snapshot` column stores the resource payload (template model, variants, data model, data examples) exactly as it was when the template was installed or last upgraded from the catalog. This enables detecting local changes by comparing the current template state against the snapshot.

This comparison happens within a single Epistola instance — both the snapshot and the current state are serialized by the same system, so no cross-instance canonicalization is needed. For local catalogs, `installed_snapshot` is null (there's no "installed from" state to compare against).

## Protocol Specification

The catalog exchange protocol defines the wire format for sharing catalogs between Epistola instances. It is used both for the `.well-known` endpoint (served by Epistola) and for static catalog files hosted externally.

The catalog JSON schema is published as part of the [epistola-contract](https://github.com/epistola-app/epistola-contract) repository.

### Catalog Manifest

The catalog manifest is the top-level JSON document describing a catalog and its available resources.

#### URL Convention

```
https://example.com/.well-known/epistola/v1/tenants/{tenantId}/catalogs/{catalogId}.json
```

For Epistola-served catalogs, this URL is generated automatically. For externally hosted catalogs, any URL that returns a valid manifest is accepted.

#### Schema

```json
{
  "schemaVersion": 1,
  "catalog": {
    "slug": "acme-templates",
    "name": "Acme Corp Templates",
    "description": "Official templates for Acme Corp documents."
  },
  "publisher": {
    "name": "Acme Corp",
    "url": "https://example.com"
  },
  "release": {
    "version": "2.1",
    "releasedAt": "2026-04-01T10:00:00Z"
  },
  "compatibility": {
    "epistolaVersions": ">=0.12.0"
  },
  "includes": [
    {
      "url": "https://community.example.com/.well-known/epistola/v1/catalog.json",
      "description": "Community base templates"
    }
  ],
  "resources": [
    {
      "type": "template",
      "slug": "invoice-standard",
      "name": "Standard Invoice",
      "description": "A clean, professional invoice template with line items, totals, and payment details.",
      "updatedAt": "2026-04-01T10:00:00Z",
      "detailUrl": "./resources/templates/invoice-standard.json",
      "compatibility": {
        "epistolaVersions": ">=0.12.0"
      }
    }
  ]
}
```

| Field                                        | Type    | Required | Description                                                                           |
| -------------------------------------------- | ------- | -------- | ------------------------------------------------------------------------------------- |
| `schemaVersion`                              | integer | yes      | Protocol version. Currently `1`.                                                      |
| `catalog.slug`                               | string  | yes      | Catalog identifier (3-50 chars, URL-safe slug).                                       |
| `catalog.name`                               | string  | yes      | Human-readable catalog name.                                                          |
| `catalog.description`                        | string  | no       | Catalog description.                                                                  |
| `publisher.name`                             | string  | yes      | Human-readable publisher name.                                                        |
| `publisher.url`                              | string  | no       | Publisher website URL.                                                                |
| `release.version`                            | string  | yes      | Release version label (max 50 chars). Drives upgrade detection.                       |
| `release.releasedAt`                         | string  | yes      | ISO 8601 timestamp of when this release was created.                                  |
| `compatibility`                              | object  | no       | Catalog-level compatibility constraints. Applied to all resources unless overridden.  |
| `compatibility.epistolaVersions`             | string  | no       | Semver range of compatible Epistola versions (e.g. `>=0.12.0`).                       |
| `includes`                                   | array   | no       | List of other catalog URLs to include. See [Catalog Includes](#catalog-includes).     |
| `includes[].url`                             | string  | yes      | Absolute URL to another catalog manifest.                                             |
| `includes[].description`                     | string  | no       | Human-readable description of the included catalog.                                   |
| `resources`                                  | array   | yes      | List of available resources. May be empty if the catalog only aggregates includes.    |
| `resources[].type`                           | string  | yes      | Resource type: `template`, `theme`, `stencil`, `attribute`, or `asset`.               |
| `resources[].slug`                           | string  | yes      | Unique identifier within the catalog (3-50 chars, URL-safe slug).                     |
| `resources[].name`                           | string  | yes      | Human-readable display name.                                                          |
| `resources[].description`                    | string  | no       | Short description of the resource.                                                    |
| `resources[].updatedAt`                      | string  | no       | ISO 8601 timestamp of last update to this resource.                                   |
| `resources[].detailUrl`                      | string  | yes      | URL to the full resource payload. Relative URLs are resolved against the catalog URL. |
| `resources[].compatibility`                  | object  | no       | Resource-level compatibility. Overrides catalog-level if present.                     |
| `resources[].compatibility.epistolaVersions` | string  | no       | Semver range for this specific resource.                                              |

Note that versioning is at the **catalog level** (`release.version`), not per-resource. All resources in a release are part of the same version. The `updatedAt` on individual resources is informational only.

#### Detail URLs

Detail URLs can be relative or absolute:

- **Relative**: `./resources/templates/invoice-standard.json` — resolved against the catalog manifest URL.
- **Absolute**: `https://cdn.example.com/epistola/invoice-standard.json` — fetched as-is.

### Catalog Includes

A catalog can include other catalogs via the `includes` array. This enables composition — an organization can maintain a curated catalog that aggregates resources from multiple upstream sources.

When Epistola resolves a catalog with includes:

1. Fetch the root catalog manifest.
2. For each entry in `includes`, recursively fetch and resolve that catalog.
3. Merge all resources into a flat list. If two catalogs define a resource with the same slug and type, the resource from the **root catalog** takes precedence, followed by includes in declaration order.
4. Apply a maximum include depth of 3 to prevent infinite loops and excessive fetching.

Included catalogs inherit the **authentication** of the root catalog unless they require different credentials. If an included catalog needs separate auth, it must also be registered as its own imported catalog in Epistola.

### Resource Detail: Template

The template resource detail contains everything needed to install a template.

```json
{
  "schemaVersion": 1,
  "resource": {
    "type": "template",
    "slug": "invoice-standard",
    "name": "Standard Invoice",
    "dataModel": {
      "type": "object",
      "properties": {
        "invoiceNumber": { "type": "string" },
        "lineItems": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "description": { "type": "string" },
              "amount": { "type": "number" }
            }
          }
        }
      }
    },
    "dataExamples": [
      {
        "name": "Sample Invoice",
        "data": {
          "invoiceNumber": "INV-2026-001",
          "lineItems": [{ "description": "Consulting", "amount": 1500.0 }]
        }
      }
    ],
    "templateModel": { "...ProseMirror document JSON..." },
    "variants": [
      {
        "id": "default",
        "title": "Default",
        "attributes": {},
        "templateModel": null,
        "isDefault": true
      }
    ]
  }
}
```

The `resource` object matches the shape of Epistola's existing `ImportTemplateInput` (minus `publishTo` and `version`, which are catalog-level concerns). It is wrapped in a `schemaVersion` envelope for protocol versioning.

| Field                               | Type    | Required | Description                                                               |
| ----------------------------------- | ------- | -------- | ------------------------------------------------------------------------- |
| `schemaVersion`                     | integer | yes      | Protocol version. Must match the catalog's `schemaVersion`.               |
| `resource.type`                     | string  | yes      | Resource type (`template`).                                               |
| `resource.slug`                     | string  | yes      | Template slug. Used as the template ID when installing.                   |
| `resource.name`                     | string  | yes      | Template display name.                                                    |
| `resource.dataModel`                | object  | no       | JSON Schema describing the template's data contract.                      |
| `resource.dataExamples`             | array   | no       | Sample data objects for preview and testing.                              |
| `resource.templateModel`            | object  | yes      | ProseMirror document JSON (the template content).                         |
| `resource.variants`                 | array   | yes      | At least one variant. Exactly one must have `isDefault: true`.            |
| `resource.variants[].id`            | string  | yes      | Variant slug (3-50 chars).                                                |
| `resource.variants[].title`         | string  | no       | Display title.                                                            |
| `resource.variants[].attributes`    | object  | no       | Key-value attribute pairs.                                                |
| `resource.variants[].templateModel` | object  | no       | Variant-specific content. `null` means use the top-level `templateModel`. |
| `resource.variants[].isDefault`     | boolean | yes      | Whether this is the default variant.                                      |

### Resource Detail: Theme

```json
{
  "schemaVersion": 2,
  "resource": {
    "type": "theme",
    "slug": "corporate",
    "name": "Corporate Theme",
    "description": "A clean corporate theme.",
    "documentStyles": {
      "fontFamily": "Arial, sans-serif",
      "fontSize": "11pt",
      "color": "#333333"
    },
    "pageSettings": {
      "format": "A4",
      "orientation": "portrait",
      "margins": { "top": 25, "right": 20, "bottom": 25, "left": 20 }
    },
    "blockStylePresets": {},
    "spacingUnit": 4
  }
}
```

| Field                              | Type    | Required | Description                                                            |
| ---------------------------------- | ------- | -------- | ---------------------------------------------------------------------- |
| `resource.type`                    | string  | yes      | `theme`                                                                |
| `resource.slug`                    | string  | yes      | Theme ID (3-20 chars, URL-safe slug).                                  |
| `resource.name`                    | string  | yes      | Display name.                                                          |
| `resource.description`             | string  | no       | Description.                                                           |
| `resource.documentStyles`          | object  | no       | Document-level CSS defaults (fontFamily, fontSize, color, etc.).       |
| `resource.pageSettings`            | object  | no       | Page format, orientation, margins.                                     |
| `resource.blockStylePresets`       | object  | no       | Named block style presets (like CSS classes).                          |
| `resource.spacingUnit`             | number  | no       | Base spacing unit in points (1-16, default 4).                         |

### Resource Detail: Stencil

```json
{
  "schemaVersion": 2,
  "resource": {
    "type": "stencil",
    "slug": "company-header",
    "name": "Company Header",
    "description": "Reusable company header block.",
    "tags": ["header", "branding"],
    "content": { "...TemplateDocument (node/slot graph)..." }
  }
}
```

| Field                   | Type    | Required | Description                                                              |
| ----------------------- | ------- | -------- | ------------------------------------------------------------------------ |
| `resource.type`         | string  | yes      | `stencil`                                                                |
| `resource.slug`         | string  | yes      | Stencil ID (3-50 chars, URL-safe slug).                                  |
| `resource.name`         | string  | yes      | Display name.                                                            |
| `resource.description`  | string  | no       | Description.                                                             |
| `resource.tags`         | array   | no       | Tags for categorization (string array).                                  |
| `resource.content`      | object  | yes      | TemplateDocument (node/slot graph model). Same format as template model. |

### Resource Detail: Attribute

```json
{
  "schemaVersion": 2,
  "resource": {
    "type": "attribute",
    "slug": "language",
    "name": "Language",
    "allowedValues": ["nl", "en", "de", "fr"]
  }
}
```

| Field                       | Type    | Required | Description                                              |
| --------------------------- | ------- | -------- | -------------------------------------------------------- |
| `resource.type`             | string  | yes      | `attribute`                                              |
| `resource.slug`             | string  | yes      | Attribute key (1-50 chars).                              |
| `resource.name`             | string  | yes      | Display name.                                            |
| `resource.allowedValues`    | array   | no       | Allowed values (string array). Empty means any value.    |

### Resource Detail: Asset

```json
{
  "schemaVersion": 2,
  "resource": {
    "type": "asset",
    "slug": "01966a00-0000-7000-8000-000000000001",
    "name": "Company Logo",
    "mediaType": "image/png",
    "width": 120,
    "height": 40,
    "contentUrl": "./binaries/company-logo.png"
  }
}
```

| Field                   | Type    | Required | Description                                                                    |
| ----------------------- | ------- | -------- | ------------------------------------------------------------------------------ |
| `resource.type`         | string  | yes      | `asset`                                                                        |
| `resource.slug`         | string  | yes      | Asset UUID. Must match the UUID used in template image node `assetId` refs.    |
| `resource.name`         | string  | yes      | Display name.                                                                  |
| `resource.mediaType`    | string  | yes      | MIME type: `image/png`, `image/jpeg`, `image/svg+xml`, or `image/webp`.        |
| `resource.width`        | integer | no       | Image width in pixels (null for SVG).                                          |
| `resource.height`       | integer | no       | Image height in pixels (null for SVG).                                         |
| `resource.contentUrl`   | string  | yes      | URL to the binary content. Relative URLs resolved against the manifest URL.    |

Assets are immutable — reinstalling a catalog skips assets whose UUID already exists. The `contentUrl` points to a separate binary file (not inline base64), keeping the protocol uniform while supporting files up to 5MB.

### Dependency Resolution

When installing resources selectively (e.g., a single template), the installer scans template and stencil content for references and auto-includes dependencies from the catalog:

- **Theme references**: `themeRef.type == "override"` → includes the referenced theme
- **Stencil references**: nodes with `type == "stencil"` → includes the referenced stencil
- **Asset references**: nodes with `type == "image"` → includes the referenced asset
- **Attribute references**: variant `attributes` keys → includes the referenced attribute definitions

Scanning is recursive: auto-included stencils are scanned for their own dependencies (e.g., stencil → asset).

If any dependency is referenced but not present in the catalog manifest, installation is rejected with a clear error listing the missing resources.

### Authentication

Remote catalogs may optionally require authentication:

| Mode      | Header                          | Description                                       |
| --------- | ------------------------------- | ------------------------------------------------- |
| `NONE`    | —                               | Public catalog, no authentication needed.         |
| `API_KEY` | `X-API-Key: <key>`              | API key authentication.                           |
| `BEARER`  | `Authorization: Bearer <token>` | Bearer token (e.g. GitHub PAT for private repos). |

### Version Compatibility

Compatibility constraints ensure resources are only installed on Epistola instances that support them. Declared using **semver ranges** (e.g. `>=0.12.0`, `^0.12.0`):

- **Catalog-level** (`compatibility.epistolaVersions`): baseline for all resources.
- **Resource-level** (`resources[].compatibility.epistolaVersions`): override for a specific resource.

Incompatible resources are shown but marked as incompatible. Installation is blocked.

## Local Catalogs

### Creating a Catalog

A local catalog is created within a tenant:

```
POST /api/v1/tenants/{tenantId}/catalogs
{
  "slug": "acme-templates",
  "name": "Acme Corp Templates",
  "description": "Official templates for Acme Corp documents."
}
```

### Adding Templates

Templates are added to a catalog by creating an entry in `catalog_templates`. Existing standalone templates can be moved into a catalog. A template can only belong to one catalog at a time (enforced by unique constraint).

### Creating a Release

When the catalog owner is ready to publish a version:

```
POST /api/v1/tenants/{tenantId}/catalogs/{catalogId}/releases
{
  "version": "1.0"
}
```

This snapshots the current state of all templates in the catalog (using their latest published version, falling back to draft). The snapshot is stored as an immutable release record. Version labels must be unique within a catalog.

### Publishing via .well-known

A local catalog can be made public:

```
PATCH /api/v1/tenants/{tenantId}/catalogs/{catalogId}
{
  "public": true
}
```

When public, the catalog's **latest release** is served at:

```
GET /.well-known/epistola/v1/tenants/{tenantId}/catalogs/{catalogId}.json
```

The `.well-known` endpoint requires its own security filter chain entry. Public catalogs are accessible without authentication. If the Epistola instance requires auth for the `.well-known` endpoint, the existing API key or bearer token mechanisms apply.

Only released snapshots are served — the live state of templates is never exposed directly. If no release exists, the endpoint returns 404.

### Exporting as Static Files

For hosting catalogs externally (GitHub Pages, S3, etc.), the catalog can be exported as a ZIP archive:

**REST API**:

```
POST /api/v1/tenants/{tenantId}/catalogs/{catalogId}/export
{
  "releaseVersion": "1.0"
}
```

Returns a ZIP file containing the catalog structure:

```
acme-templates/
  catalog.json
  resources/
    templates/
      invoice-standard.json
      letter-formal.json
```

**UI**: An "Export" action on the catalog detail page. The user selects a release and downloads the archive.

## Imported Catalogs

### Registering a Remote Catalog

A tenant can import a catalog from a remote URL:

```
POST /api/v1/tenants/{tenantId}/catalogs/import
{
  "sourceUrl": "https://example.com/.well-known/epistola/v1/tenants/acme/catalogs/acme-templates.json",
  "authType": "NONE"
}
```

Epistola fetches the remote catalog manifest, creates a local catalog entity of type `IMPORTED`, and presents the available resources to the user. The catalog slug and name are taken from the remote manifest's `catalog.slug` and `catalog.name` fields. If the slug conflicts with an existing local catalog, the user can provide an override.

### Installing

After registering an imported catalog, the user can install its templates:

```
POST /api/v1/tenants/{tenantId}/catalogs/{catalogId}/install
```

This installs all templates from the catalog's current release:

1. Fetch each resource detail from its `detailUrl`.
2. Check version compatibility against the Epistola instance version.
3. Create each template and register it in `catalog_templates` (with `catalog_resource_slug` and `installed_snapshot`).
4. Use the existing `ImportTemplates` logic for upserting templates, variants, and versions.
5. Record the installed release version and timestamp on the catalog entity.

Templates are installed as drafts. The user decides when to publish them to environments.

#### Selective Install

The user can also install individual templates from the catalog rather than the full set:

```
POST /api/v1/tenants/{tenantId}/catalogs/{catalogId}/install
{
  "templates": ["invoice-standard"]
}
```

#### Slug and Name Remapping

When installing, the user can override the local slug and/or name of individual templates:

```
POST /api/v1/tenants/{tenantId}/catalogs/{catalogId}/install
{
  "templates": [
    {
      "slug": "invoice-standard",
      "localSlug": "acme-invoice",
      "localName": "Acme Invoice"
    }
  ]
}
```

The original catalog slug is still tracked on the template for upgrade purposes.

#### Theme Resolution on Install

If a template references a theme by slug (`themeKey`):

- **Theme exists locally**: the reference is kept.
- **Theme does not exist**: `themeKey` is set to `null` (falls back to tenant default). The install result includes a warning.

#### Asset References

Templates with image asset references (by UUID) will have **broken images** after install, since assets are instance-local. The install result warns about this. See [Asset Bundling](#asset-bundling) for future plans.

### Checking for Updates

When viewing an imported catalog, Epistola fetches the remote manifest and compares `release.version` with `installedReleaseVersion`:

- **Same version**: up to date.
- **Different version**: a newer release is available. The UI shows the new version label, release date, and a list of changed resources.

### Upgrading

When the user initiates an upgrade to a newer release, Epistola computes a diff between the installed state and the new release before applying any changes. This gives the user full visibility and control.

#### Upgrade Diff

For each template in the catalog, Epistola determines its status by comparing three states:

- **Installed snapshot** (`installed_snapshot`): what was originally installed from the catalog.
- **Current local state**: the template as it exists now (may include local edits).
- **New upstream state**: the resource in the new catalog release.

This produces five possible statuses per template:

| Status        | Condition                                                                      | Default action             |
| ------------- | ------------------------------------------------------------------------------ | -------------------------- |
| **Unchanged** | Same in upstream and locally, no changes anywhere.                             | Skip (no action needed).   |
| **Updated**   | Changed upstream, no local changes (local matches installed snapshot).         | Auto-update.               |
| **Conflict**  | Changed upstream AND locally modified (local differs from installed snapshot). | Show to user for decision. |
| **Added**     | New resource in upstream, doesn't exist locally.                               | Install.                   |
| **Removed**   | Resource no longer in upstream, exists locally.                                | Show to user for decision. |

The "local changes" check compares the current template state against the `installed_snapshot` using Epistola's own serialization. Since both are produced by the same system, no canonicalization is needed.

#### Upgrade Flow

1. Fetch the new release from the remote catalog.
2. Check version compatibility.
3. Compute the upgrade diff (as described above).
4. Present the diff summary to the user:
   - **Unchanged**: listed but no action needed.
   - **Updated**: will be auto-updated (snapshot current draft first).
   - **Conflict**: user chooses per template — **accept upstream** (snapshot + overwrite) or **keep local** (skip this template).
   - **Added**: will be installed as new templates.
   - **Removed**: user chooses — **keep** (template stays, flagged as "detached from catalog") or **delete**.
5. Apply the confirmed changes:
   - For updates and accepted conflicts: publish the current draft (preserving it in version history), then import the new version. Update `installed_snapshot`.
   - For additions: create new templates under the catalog.
   - For removals (if user chose delete): delete the template.
   - For removals (if user chose keep): remove the `catalog_templates` entry (template becomes standalone).
6. Update `installedReleaseVersion` and `installedAt` on the catalog.

No content is ever lost without explicit user confirmation. Updated templates always have their previous state preserved in version history. Conflict resolution defaults to showing the user, never silently overwriting local work.

## Example: End-to-End Flow

### Publishing a catalog

1. Tenant "acme" creates a local catalog `acme-templates`.
2. Assigns templates `invoice-standard` and `letter-formal` to the catalog.
3. Publishes versions of the templates (so they have published content).
4. Creates release `"1.0"` — Epistola snapshots both templates.
5. Marks the catalog as `public: true`.
6. The catalog is now available at `/.well-known/epistola/v1/tenants/acme/catalogs/acme-templates.json`.

### Consuming a catalog

1. Tenant "beta-corp" on a different Epistola instance registers the catalog URL.
2. Browses the catalog — sees `invoice-standard` and `letter-formal`.
3. Installs both templates (remapping `invoice-standard` to `beta-invoice`).
4. Templates appear as drafts under the imported catalog. Reviews and publishes to staging.

### Upgrading

1. "acme" updates `invoice-standard`, adds `receipt-simple`, and creates release `"1.1"`.
2. "beta-corp" opens the imported catalog — sees "Update available: 1.0 → 1.1".
3. Clicks upgrade. Epistola shows the diff:
   - `beta-invoice` (was `invoice-standard`): **Conflict** — changed upstream and locally modified.
   - `letter-formal`: **Unchanged**.
   - `receipt-simple`: **Added**.
4. User chooses "accept upstream" for `beta-invoice` (local changes are preserved in version history).
5. Upgrade applies. User reviews the imported changes and publishes to staging.

## Hosting Catalogs Externally

While Epistola can serve catalogs via `.well-known`, catalogs can also be exported and hosted as static files. This is useful for:

- Publishing without exposing the Epistola instance to the internet.
- Using CDNs or static hosting (GitHub Pages, S3, GitLab Pages).
- Distributing catalogs through package registries or git repositories.

| Platform           | Setup                                                                       |
| ------------------ | --------------------------------------------------------------------------- |
| **GitHub Pages**   | Export catalog, commit to repo with Pages enabled. Free, versioned via git. |
| **GitLab Pages**   | Same approach as GitHub Pages.                                              |
| **S3 / GCS**       | Upload to a bucket with static website hosting or direct object URLs.       |
| **Any web server** | Serve the catalog directory as static files.                                |

For private external catalogs, configure authentication on the imported catalog (API key or bearer token).

### Authoring Catalogs by Hand

Since the catalog format is plain JSON, catalogs can be authored manually without an Epistola instance. The required structure is:

1. A `catalog.json` manifest with `schemaVersion`, `catalog`, `publisher`, `release`, and `resources` array.
2. One JSON file per resource, containing the `schemaVersion` and `resource` payload.

## Known Limitations (v1)

### Network Resilience

v1 fetches remote catalogs synchronously. No caching, retry logic, or background polling.

- **Timeouts**: 5 seconds connect, 15 seconds read.
- **No retry**: On failure, show the error and let the user retry manually.
- **No caching**: Every browse fetches fresh. Acceptable because browsing is an explicit user action.
- **Large catalogs**: No pagination. Catalogs are expected to contain tens to low hundreds of resources.

### Catalog Include Authentication

Included catalogs inherit the root catalog's authentication. If an included catalog requires different credentials, it must be registered separately as its own imported catalog.

### Concurrent Upgrades

The upgrade flow (publish current draft, then import) is not protected against concurrent execution. If two users upgrade simultaneously, one user's snapshot could be lost. Low-probability edge case for v1.

### Catalog URL Changes

If a remote catalog moves to a new URL, the imported catalog's `source_url` must be updated manually. Templates already installed retain their catalog association (via `catalog_templates`), so only the URL needs updating.

### Removed Templates on Upgrade

When upgrading to a new release that no longer contains a template that was previously installed, the local template is left in place. It remains in the imported catalog but is flagged as "no longer in upstream". The user can choose to keep or delete it. In v1, this is a manual process.

## Implementation Phases

The full design described above is the target architecture. Implementation is broken into incremental phases, each delivering a usable feature on its own.

### Phase 1: Import from URL

**Goal**: A user can register a catalog URL, browse its contents, and install templates.

**Scope**:

- Module skeleton: `modules/epistola-catalog/` with Gradle setup, dependency on `epistola-core`.
- DB tables: `catalogs` (IMPORTED type only, no LOCAL yet) and `catalog_templates` (without `installed_snapshot` — not needed until phase 2).
- `CatalogClient`: HTTP client that fetches a catalog manifest and resource details. Supports `NONE` and `BEARER` auth. Timeouts (5s connect, 15s read).
- Commands: `RegisterCatalog` (fetch manifest, create IMPORTED catalog entity), `InstallFromCatalog` (fetch resource details, call `ImportTemplates`, create `catalog_templates` entries).
- Queries: `ListCatalogs`, `GetCatalog`, `BrowseCatalog` (fetch manifest, annotate with install status).
- UI: Settings page to register a catalog URL. Browse page showing available templates with "Install" buttons.
- No releases, no versioning, no upgrade flow. The catalog manifest's `release.version` is stored on the catalog but not compared yet.
- No `includes` resolution — only the root catalog's resources.
- No compatibility checking.
- No slug remapping — templates are installed with the catalog slug as-is.

**What this enables**: Loading templates from any static catalog (GitHub Pages, etc.) into an Epistola instance.

### Phase 2: Upgrade Detection & Sync

**Goal**: Detect when a catalog has a newer release and upgrade installed templates with conflict awareness.

**Scope**:

- Add `installed_snapshot` column to `catalog_templates`. Populated on install (backfill existing entries).
- Add `installed_release_version` and `installed_at` to `catalogs`. Set on install.
- Query: `GetUpgradeDiff` — fetch remote manifest, compare `release.version` with `installed_release_version`. For each template, compute status (unchanged/updated/conflict/added/removed) by comparing installed snapshot, current state, and new upstream state.
- Command: `UpgradeCatalog` — apply the upgrade with snapshot-before-overwrite for changed templates.
- UI: "Update available" badge on imported catalogs. Upgrade review screen showing the diff summary. Per-template decisions for conflicts and removals.

**What this enables**: Keeping imported templates in sync with upstream catalogs. Safe upgrades that preserve local changes in version history.

### Phase 3: Local Catalogs

**Goal**: Create catalogs within Epistola, assign templates, and create releases.

**Scope**:

- Support `LOCAL` type in `catalogs` table.
- DB table: `catalog_releases`.
- Commands: `CreateCatalog` (LOCAL type), `AddTemplateToCatalog`, `RemoveTemplateFromCatalog`, `CreateRelease` (snapshot all templates in the catalog).
- Queries: `ListReleases`, `GetRelease`.
- UI: Catalog management page. Create catalog, add/remove templates, create releases.

**What this enables**: Organizing templates into versioned, distributable groups.

### Phase 4: Publishing & .well-known

**Goal**: Expose local catalogs for other instances to consume.

**Scope**:

- `public` flag on `catalogs`.
- `.well-known` controller: `GET /.well-known/epistola/v1/tenants/{tenantId}/catalogs/{catalogId}.json` — serves the latest release's snapshot as a catalog manifest.
- Resource detail endpoints: `GET /.well-known/epistola/v1/tenants/{tenantId}/catalogs/{catalogId}/resources/templates/{slug}.json`.
- Security filter chain entry for `.well-known` (public access, or API key/bearer if configured).

**What this enables**: One Epistola instance can serve catalogs that other instances import. Full round-trip: create → release → publish → import.

### Phase 5: Export & Static Hosting

**Goal**: Export catalogs as static files for hosting on GitHub Pages, S3, etc.

**Scope**:

- Command: `ExportCatalog` — generate ZIP archive from a release.
- REST endpoint: `POST /api/v1/tenants/{tenantId}/catalogs/{catalogId}/export`.
- UI: Export button on catalog detail page.

**What this enables**: Publishing catalogs without exposing the Epistola instance to the internet.

### Phase 6: Advanced Features

**Goal**: Remaining features from the full design.

**Scope** (can be done independently):

- **Slug remapping**: Override local slug/name on install.
- **Compatibility checking**: Parse semver ranges, block incompatible installs.
- **Catalog includes**: Recursive resolution of `includes` array.
- **Selective install**: Install individual templates instead of full catalog.
- **REST API**: Full catalog CRUD via the external API (for automation).

## Future Considerations

### Merge-Based Upgrades

The v1 upgrade strategy (overwrite with snapshot) requires manual re-application of customizations. A future version could explore ProseMirror-aware diffing and merging for upgrades that preserve local changes.

### Automatic Update Notifications

A future version could add periodic background polling of imported catalogs with in-app notifications when new releases are available.

### Catalog Search and Categories

Future versions could add categories, tags, and search to the catalog manifest for discoverability in large catalogs.

### Release Notes

Future versions could add a `releaseNotes` field to releases, describing what changed between versions. This would be shown in the upgrade UI.

## Catalog-Based Resource Model

This section describes the target architecture for catalogs in production. Every resource belongs to a catalog. Catalogs are the organizational unit, namespace, and exchange boundary.

### Core Principles

1. **Every resource belongs to exactly one catalog** — no standalone/orphan resources
2. **The catalog slug IS the namespace** — resource identity: `{catalog}/{slug}`
3. **All references are always fully qualified** — `acme-themes/corporate`, never bare `corporate`
4. **Cross-catalog references are allowed** — a template can use themes from another catalog
5. **Catalog slugs are universal and fixed** — publisher chooses the slug, everyone uses it

### Catalog Types

| Type | Editable? | Source |
|---|---|---|
| **Authored** | Yes | Created locally. User manages resources. Can publish releases. |
| **Subscribed** | No (read-only) | Installed from external URL. Can receive upgrades. |

### Default Catalog

- Every tenant gets a default authored catalog on creation
- Users can create additional authored catalogs (e.g., `permit-templates`, `hr-templates`)
- All catalogs are equal in the UI — the default is not special

### Resource Identity

All resources are identified by `{catalog}/{slug}`:

| Example | Meaning |
|---|---|
| `acme-themes/corporate` | Theme `corporate` in catalog `acme-themes` |
| `my-templates/invoice` | Template `invoice` in authored catalog `my-templates` |
| `default/letterhead` | Stencil `letterhead` in the default catalog |

**Database PK**: `(tenant_key, catalog_key, id)` for each resource type table.

### References

**All refs are always fully qualified everywhere** — in the database, in the manifest, in the API. No bare slugs, no implicit resolution, no install-time rewriting.

Examples in template model JSON:
```json
{
  "themeRef": { "type": "override", "themeId": "acme-themes/corporate" },
  "nodes": {
    "n-stencil": {
      "type": "stencil",
      "props": { "stencilId": "shared-stencils/company-header" }
    },
    "n-logo": {
      "type": "image",
      "props": { "assetId": "acme-themes/01966a00-0000-7000-8000-000000000001" }
    }
  }
}
```

**Cross-catalog refs are allowed.** A template in `my-templates` can reference `acme-themes/corporate`. The referenced catalog must be installed.

**If a catalog is renamed**, refs are updated via tooling (bulk rename across template models).

### Catalog Slug Identity

- Catalog slug is chosen by the publisher and is fixed
- On install, if the slug conflicts with an existing catalog on this tenant, the install is rejected
- No local aliasing (simplifies everything — refs always match the slug)
- Slugs are short and descriptive: kebab-case, max 50 chars

### Reference Resolution (Generation)

When generating a document from template `my-templates/invoice`:

1. Template model contains fully qualified refs: `acme-themes/corporate`, `my-templates/header-stencil`
2. Parse `{catalog}/{slug}`, look up in `(tenant_key, catalog_key, resource_slug)`
3. If referenced catalog is not installed → error
4. If resource not found in catalog → error
5. No guessing, no fallback, no ambiguity

### Lifecycle Flows

#### Subscribe to a catalog

1. User provides a catalog URL
2. System fetches manifest, shows catalog slug + available resources
3. If slug conflicts with existing catalog → reject (user must uninstall the conflicting one first)
4. User confirms installation (dependency preview)
5. Resources installed as read-only, under the catalog's slug namespace
6. Records: source URL, installed version, timestamp

#### Upgrade a subscription

1. System detects new release version at source URL
2. "Check for updates" → shows diff (new / changed / removed)
3. User confirms → resources updated in place (read-only)

#### Copy resources between catalogs

- User can copy any resource to any authored catalog
- Two modes:
  1. **Copy with original refs** — the copy keeps cross-catalog refs (e.g., still references `acme-themes/corporate`)
  2. **Copy with dependencies** — also copies referenced resources into the target, refs updated to target catalog
- The original stays in its catalog (it's a copy, not a move)
- Not needed for v1 — can be added later

#### Uninstall a subscription

User chooses:
- **Remove all** — deletes catalog and all its resources
- **Copy to authored first** — copy resources to an authored catalog, then remove subscription

#### Author a catalog

1. Create authored catalog with name and slug
2. Create resources within it (or copy from other catalogs)
3. Create releases (versioned snapshots)
4. Publish via `.well-known` or export as static files

### Publishing

- **Self-contained catalogs**: all refs point within the catalog. Simplest for consumers — install one catalog and everything works.
- **Catalogs with dependencies**: refs to other catalogs are allowed. The manifest declares which catalogs are required. Consumer must install those too (like npm peer dependencies).

### Database Design

**Resource tables** (templates, themes, stencils, attributes, assets):
- Add `catalog_key VARCHAR(50) NOT NULL`
- PK changes from `(tenant_key, id)` to `(tenant_key, catalog_key, id)`
- All FKs cascade accordingly

**Catalogs table**:
- Type: `AUTHORED` or `SUBSCRIBED`
- For subscribed: `source_url`, `installed_release_version`, auth config
- For authored: no source URL

**`catalog_resources` table**:
- Becomes redundant — resources carry `catalog_key` directly
- Can be dropped

### URL Design

**UI**: `/tenants/{tenantId}/catalogs/{catalogId}/templates/{id}`

**API**: Separate `catalog` and resource `id` parameters:
```
POST /api/tenants/{tenantId}/documents/generate
{ "catalog": "my-templates", "templateId": "invoice", "data": {...} }
```

### Not Yet Covered

- Copy workflow (later convenience feature)
- Catalog dependency declarations in the manifest format
- Upgrade diff and conflict resolution
- Catalog publishing (releases, .well-known, static export)
- Editor integration (picking resources from other catalogs)
- Catalog discovery / registry
- Access control per catalog
