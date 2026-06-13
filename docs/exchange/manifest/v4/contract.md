# Manifest contract — v4

> Part of [catalog import/export](../../README.md). This is the **current** manifest version. [All manifest versions](../).

**Role:** the top-level `catalog.json` — the entry point of every catalog. It carries the catalog identity, the release stamp (version + fingerprint), and a flat index of the resources, each pointing at its own [resource detail file](../../README.md#parts--contract-versions). The manifest is **one versioned part among the others** — its `schemaVersion` versions the manifest's own shape, not the whole catalog (each resource type carries its own version; see [ADR 0006](../../../adr/0006-catalog-wire-format-migrations.md)).

**DTO:** `app.epistola.catalog.protocol.CatalogManifest` (external `epistola-model`).
**Produced by:** [`CatalogContentBuilder.toManifest()`](../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogContentBuilder.kt) — stamps `schemaVersion = CATALOG_MANIFEST_SCHEMA_VERSION`.
**Consumed by:** [`CatalogClient.fetchManifest()`](../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogClient.kt) → [`CatalogSchemaMigrator`](../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/migrations/CatalogSchemaMigrator.kt) (version gate + migration chain).
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

| Field                       | Type              | Required    | Description                                                                                                                                                                                                                                                                               |
| --------------------------- | ----------------- | ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `schemaVersion`             | integer           | yes         | **The manifest part's contract version** (currently `4`) — versions the manifest's own shape; each resource type carries its own version ([ADR 0006](../../../adr/0006-catalog-wire-format-migrations.md)). Read first, gated by `CatalogSchemaMigrator` before binding to a typed model. |
| `catalog.slug`              | string            | yes         | Catalog identifier (URL-safe slug).                                                                                                                                                                                                                                                       |
| `catalog.name`              | string            | yes         | Display name.                                                                                                                                                                                                                                                                             |
| `catalog.description`       | string            | no          | Free text.                                                                                                                                                                                                                                                                                |
| `publisher.name`            | string            | yes         | Publisher name.                                                                                                                                                                                                                                                                           |
| `publisher.url`             | string            | no          | Publisher URL.                                                                                                                                                                                                                                                                            |
| `release.version`           | string            | yes         | Release label. SemVer (`MAJOR.MINOR.PATCH`) for versioned catalogs; `-dev`-suffixed when exported from a drifted/never-released working copy. See [`catalog-versioning.md`](../../../catalog-versioning.md).                                                                              |
| `release.releasedAt`        | string (ISO-8601) | no          | Release timestamp. Null/absent for an unreleased working copy. **Excluded from the fingerprint.**                                                                                                                                                                                         |
| `release.fingerprint`       | string            | no          | Lowercase hex SHA-256 of the catalog's canonical content. Drives content-based change detection. Computed by `CatalogCanonicalizer`; **excludes** `schemaVersion`, `release.*`, all `updatedAt`, and `detailUrl`.                                                                         |
| `resources[]`               | array             | yes         | Flat index; one entry per resource.                                                                                                                                                                                                                                                       |
| `resources[].type`          | string            | yes         | One of `asset`, `codeList`, `font`, `attribute`, `theme`, `stencil`, `template` (install order — see [`CatalogConstants.RESOURCE_INSTALL_ORDER`](../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogConstants.kt)).                                      |
| `resources[].slug`          | string            | yes         | Unique within the catalog (asset slugs are UUIDs).                                                                                                                                                                                                                                        |
| `resources[].name`          | string            | yes         | Display name.                                                                                                                                                                                                                                                                             |
| `resources[].description`   | string            | no          | Free text.                                                                                                                                                                                                                                                                                |
| `resources[].updatedAt`     | string            | no          | Last-modified hint. **Excluded from the fingerprint.**                                                                                                                                                                                                                                    |
| `resources[].detailUrl`     | string            | yes         | Relative URL to the [resource detail file](../../README.md#parts--contract-versions). Resolved against the manifest URL; `..` traversal is rejected.                                                                                                                                      |
| `dependencies[]`            | array             | no          | Cross-catalog references the importer must already have. Import is blocked if any are missing.                                                                                                                                                                                            |
| `dependencies[].type`       | string            | yes         | `theme`, `stencil`, or `asset`.                                                                                                                                                                                                                                                           |
| `dependencies[].catalogKey` | string            | conditional | Source catalog slug. Required for `theme`/`stencil`; absent for `asset` (tenant-global).                                                                                                                                                                                                  |
| `dependencies[].slug`       | string            | yes         | Resource identifier in the source catalog (or asset UUID).                                                                                                                                                                                                                                |

## Validation / behaviour

- **Version gate** (`CatalogSchemaMigrator`): payload `schemaVersion` greater than this instance's `CATALOG_MANIFEST_SCHEMA_VERSION` → `CatalogSchemaTooNewException`; below `CATALOG_MANIFEST_BASELINE_SCHEMA_VERSION` (once migrations exist) → `CatalogSchemaTooOldException`; missing/non-integer → `CatalogSchemaUnknownException`. All map to HTTP 400. See [ADR 0006](../../../adr/0006-catalog-wire-format-migrations.md).
- The **manifest is authoritative** for the version — resource-detail `schemaVersion` stamps are not trusted by the gate.

## Changed in v4

- Baseline documented version. v4 introduced catalog versioning: the `release` block gained `fingerprint`, enabling content-based change detection. (See `CatalogConstants.kt` header note.)

## Related parts

- Each `resources[].type` → its [resource detail contract](../../README.md#parts--contract-versions).
- Fingerprint & SemVer model: [`catalog-versioning.md`](../../../catalog-versioning.md).
- Wire-version migrations: [ADR 0006](../../../adr/0006-catalog-wire-format-migrations.md).
