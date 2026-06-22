package app.epistola.suite.catalog

/**
 * Catalog exchange protocol version emitted by this instance. Bumped to 4 for
 * catalog versioning (`ReleaseInfo.fingerprint`). Exports always stamp this
 * current value; imports of an older payload are upgraded to it by the
 * `migrations/` chain (see [CATALOG_MANIFEST_BASELINE_SCHEMA_VERSION] and
 * [app.epistola.suite.catalog.migrations.CatalogSchemaMigrator]).
 */
const val CATALOG_MANIFEST_SCHEMA_VERSION: Int = 4

/**
 * Oldest catalog wire schema version this instance can still upgrade to
 * [CATALOG_MANIFEST_SCHEMA_VERSION] via the `migrations/` chain. A payload below
 * this floor is rejected on import (re-export from a current source). The chain
 * spans `[baseline, current]` — currently `2 → 3` (`CatalogSchemaMigrationV2ToV3`,
 * additive/identity) then `3 → 4` (`CatalogSchemaMigrationV3ToV4`) — see
 * [app.epistola.suite.catalog.migrations.CatalogSchemaMigrator],
 * `docs/adr/0006-catalog-wire-format-migrations.md`, and
 * `docs/adr/0007-at-rest-resource-migration.md`.
 */
const val CATALOG_MANIFEST_BASELINE_SCHEMA_VERSION: Int = 2

/**
 * Installation order for catalog resources — dependencies must be installed
 * before dependents. `codeList` precedes `attribute` because an attribute can
 * bind to a code list (`AttributeResource.codeListBinding`), and the bound
 * list's row must exist when the FK is enforced by `attr_code_list_fk`.
 * `font` follows `asset` because every asset-backed font variant FKs an
 * `assets` row in the same catalog (`font_variants.asset_key`).
 */
val RESOURCE_INSTALL_ORDER: Map<String, Int> = mapOf(
    "asset" to 0,
    "codeList" to 1,
    "font" to 2,
    "attribute" to 3,
    "theme" to 4,
    "stencil" to 5,
    "template" to 6,
)
