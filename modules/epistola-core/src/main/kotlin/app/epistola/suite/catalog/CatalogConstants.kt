package app.epistola.suite.catalog

/**
 * The catalog exchange wire format carries a single, **catalog-wide** schema
 * version. It is stamped on the manifest (`catalog.json`) and — so each file is
 * self-describing — on every resource detail, but there is **one** version for
 * the whole bundle: resources are not versioned independently. The manifest's
 * `schemaVersion` is authoritative for the catalog.
 *
 * A migration upgrades a whole catalog from an older version to
 * [CATALOG_SCHEMA_VERSION] (manifest + every resource detail move together). See
 * `docs/adr/0007-catalog-wire-format-migrations.md` and `docs/exchange/`.
 *
 * The chain currently spans `3 → 4 → 5`:
 * [app.epistola.suite.catalog.migrations.steps.CatalogV3ToV4ExampleMigration]
 * renames legacy `title`/`displayName` to `name`, and
 * [app.epistola.suite.catalog.migrations.steps.CatalogV4ToV5Migration] is a
 * **no-op version bump** (the catalog moves from `4` to `5` with no content
 * change). Copy either for the pattern.
 */

/** The wire schema version this instance emits, and upgrades older imports to. */
const val CATALOG_SCHEMA_VERSION: Int = 5

/** The oldest wire schema version this instance can still upgrade to current. */
const val CATALOG_BASELINE_SCHEMA_VERSION: Int = 3

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
