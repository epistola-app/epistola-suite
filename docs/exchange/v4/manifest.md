# Manifest — catalog wire v4

> Part of the [catalog **v4** wire format](README.md) ([exchange overview](../README.md)).

**Role:** the top-level `catalog.json` — the entry point of every catalog. It carries the catalog identity, the release stamp (version + fingerprint), and a flat index of the resources, each pointing at its own [resource detail file](../README.md#parts--contract-versions). The manifest carries the **authoritative catalog-wide `schemaVersion`** — the single wire version for the whole bundle. Every resource detail echoes the same number, but there is no independent per-resource version; the whole catalog moves together (see [ADR 0007](../../adr/0007-catalog-wire-format-migrations.md)).

**DTO:** `app.epistola.catalog.protocol.CatalogManifest` (external `epistola-model`).
**Produced by:** [`CatalogContentBuilder.toManifest()`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogContentBuilder.kt) — stamps `schemaVersion = CATALOG_SCHEMA_VERSION`.
**Consumed by:** [`CatalogClient.fetchManifest()`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogClient.kt) → [`CatalogSchemaMigrator`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/migrations/CatalogSchemaMigrator.kt) (version gate + migration chain).
**Wire location:** `catalog.json` at the ZIP/URL root.

## Shape

```json
{
  "schemaVersion": 4,
  "catalog": {
    "slug": "acme-templates",
    "name": "Acme Corp Templates",
    "description": "Official templates for Acme Corp documents."
  },
  "publisher": {
    "name": "Acme Corp",
    "url": "https://acme.example"
  },
  "release": {
    "version": "1.0.0",
    "releasedAt": "2026-06-12T00:00:00Z",
    "fingerprint": "9f3a1c2b…"
  },
  "resources": [
    {
      "type": "template",
      "slug": "invoice-standard",
      "name": "Standard Invoice",
      "description": "…",
      "updatedAt": "2026-04-10T00:00:00Z",
      "detailUrl": "./resources/template/invoice-standard.json"
    }
  ],
  "dependencies": [
    { "type": "theme", "catalogKey": "shared-styles", "slug": "base-theme" },
    { "type": "asset", "slug": "01966a00-0000-7000-8000-000000000002" }
  ]
}
```

## Fields

| Field                       | Type              | Required    | Description                                                                                                                                                                                                                                                                                                             |
| --------------------------- | ----------------- | ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `schemaVersion`             | integer           | yes         | **The authoritative catalog-wide wire version** (currently `4`) — versions the whole exchange bundle, not just the manifest; every resource detail echoes the same number ([ADR 0007](../../adr/0007-catalog-wire-format-migrations.md)). Read first, gated by `CatalogSchemaMigrator` before binding to a typed model. |
| `catalog.slug`              | string            | yes         | Catalog identifier (URL-safe slug).                                                                                                                                                                                                                                                                                     |
| `catalog.name`              | string            | yes         | Display name.                                                                                                                                                                                                                                                                                                           |
| `catalog.description`       | string            | no          | Free text.                                                                                                                                                                                                                                                                                                              |
| `publisher.name`            | string            | yes         | Publisher name.                                                                                                                                                                                                                                                                                                         |
| `publisher.url`             | string            | no          | Publisher URL.                                                                                                                                                                                                                                                                                                          |
| `release.version`           | string            | yes         | Release label. SemVer (`MAJOR.MINOR.PATCH`) for versioned catalogs; `-dev`-suffixed when exported from a drifted/never-released working copy. See [`catalog-versioning.md`](../../catalog-versioning.md).                                                                                                               |
| `release.releasedAt`        | string (ISO-8601) | no          | Release timestamp. Null/absent for an unreleased working copy. **Excluded from the fingerprint.**                                                                                                                                                                                                                       |
| `release.fingerprint`       | string            | no          | Lowercase hex SHA-256 of the catalog's canonical content. Drives content-based change detection. Computed by `CatalogCanonicalizer`; **excludes** `schemaVersion`, `release.*`, all `updatedAt`, and `detailUrl`.                                                                                                       |
| `resources[]`               | array             | yes         | Flat index; one entry per resource.                                                                                                                                                                                                                                                                                     |
| `resources[].type`          | string            | yes         | One of `asset`, `codeList`, `font`, `attribute`, `theme`, `stencil`, `template` (install order — see [`CatalogConstants.RESOURCE_INSTALL_ORDER`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogConstants.kt)).                                                                       |
| `resources[].slug`          | string            | yes         | Unique within the catalog (asset slugs are UUIDs).                                                                                                                                                                                                                                                                      |
| `resources[].name`          | string            | yes         | Display name.                                                                                                                                                                                                                                                                                                           |
| `resources[].description`   | string            | no          | Free text.                                                                                                                                                                                                                                                                                                              |
| `resources[].updatedAt`     | string            | no          | Last-modified hint. **Excluded from the fingerprint.**                                                                                                                                                                                                                                                                  |
| `resources[].detailUrl`     | string            | yes         | Relative URL to the [resource detail file](../README.md#parts--contract-versions). Resolved against the manifest URL; `..` traversal is rejected.                                                                                                                                                                       |
| `dependencies[]`            | array             | no          | Cross-catalog references the importer must already have. Import is blocked if any are missing.                                                                                                                                                                                                                          |
| `dependencies[].type`       | string            | yes         | `theme`, `stencil`, `asset`, `codeList`, or `font`.                                                                                                                                                                                                                                                                     |
| `dependencies[].catalogKey` | string            | conditional | Source catalog slug. Required for `theme`/`stencil`/`codeList`/`font`; absent for `asset` (tenant-global).                                                                                                                                                                                                              |
| `dependencies[].slug`       | string            | yes         | Resource identifier in the source catalog (or asset UUID).                                                                                                                                                                                                                                                              |

## Validation / behaviour

- **Version gate** (`CatalogSchemaMigrator`): payload `schemaVersion` greater than this instance's `CATALOG_SCHEMA_VERSION` → `CatalogSchemaTooNewException`; below `CATALOG_BASELINE_SCHEMA_VERSION` (once migrations exist) → `CatalogSchemaTooOldException`; unparseable JSON or a missing/non-integer `schemaVersion` → `CatalogSchemaUnknownException`. All map to HTTP 400. See [ADR 0007](../../adr/0007-catalog-wire-format-migrations.md).
- **One catalog version**: the manifest is authoritative for the catalog-wide `schemaVersion`; every resource detail carries the same number. There is no independent per-resource version — the whole bundle is gated and migrated together.

## Shape history

- Catalog versioning landed the `release.fingerprint` field, enabling content-based change detection (see `CatalogConstants.kt` header note). This is the history of the manifest's shape; on the wire it always carries the catalog-wide `schemaVersion` (currently `4`), not an independent manifest version.

## Related parts

- Each `resources[].type` → its [resource detail contract](../README.md#parts--contract-versions).
- Fingerprint & SemVer model: [`catalog-versioning.md`](../../catalog-versioning.md).
- Wire-version migrations: [ADR 0007](../../adr/0007-catalog-wire-format-migrations.md).
