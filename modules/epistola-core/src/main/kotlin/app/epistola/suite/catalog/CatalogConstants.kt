package app.epistola.suite.catalog

/**
 * A versioned **part** of the catalog exchange wire format: the `catalog.json`
 * manifest, or one resource type. Each part owns an independent `schemaVersion`
 * and its own migration chain (per-part versioning — see
 * `docs/adr/0006-catalog-wire-format-migrations.md` and `docs/exchange/`). The
 * manifest is not privileged; it is one part among the others.
 */
enum class CatalogPart(
    /** The resource `type` discriminator on the wire, or `null` for the manifest. */
    val wireType: String?,
) {
    MANIFEST(null),
    ASSET("asset"),
    CODE_LIST("codeList"),
    FONT("font"),
    ATTRIBUTE("attribute"),
    THEME("theme"),
    STENCIL("stencil"),
    TEMPLATE("template"),
    ;

    companion object {
        private val byWireType = entries.mapNotNull { p -> p.wireType?.let { it to p } }.toMap()

        /** The part for a resource `type` discriminator, or `null` if unrecognised. */
        fun ofResourceType(type: String): CatalogPart? = byWireType[type]
    }
}

/** The current and oldest-still-upgradable wire schema version of one [CatalogPart]. */
data class PartSchemaWindow(
    /** Oldest version this instance can upgrade to [current] via that part's chain. */
    val baseline: Int,
    /** Version this instance emits; imports below it are upgraded to it. */
    val current: Int,
)

/**
 * Per-part wire schema versions. Each part is versioned independently
 * (`docs/adr/0006-catalog-wire-format-migrations.md`): export stamps each part's
 * [PartSchemaWindow.current], and an import upgrades a payload from its own
 * version up to that part's current via the per-part migration chain. The
 * canonical record of each part's current shape is `docs/exchange/`.
 *
 * `baseline == current` for every part today — no migrations exist yet, so the
 * chains are empty and every payload binds as-is.
 */
val CATALOG_PART_SCHEMAS: Map<CatalogPart, PartSchemaWindow> = mapOf(
    CatalogPart.MANIFEST to PartSchemaWindow(baseline = 4, current = 4),
    CatalogPart.ASSET to PartSchemaWindow(baseline = 2, current = 2),
    CatalogPart.CODE_LIST to PartSchemaWindow(baseline = 3, current = 3),
    CatalogPart.FONT to PartSchemaWindow(baseline = 1, current = 1),
    CatalogPart.ATTRIBUTE to PartSchemaWindow(baseline = 3, current = 3),
    CatalogPart.THEME to PartSchemaWindow(baseline = 2, current = 2),
    // STENCIL: v1 = pre-`version` shape (epistola-model < 0.6.0); v2 added the
    // required published-version pin (ADR 0003). The v1→v2 chain upgrades an old
    // export — StencilV1ToV2RequireVersionMigration.
    CatalogPart.STENCIL to PartSchemaWindow(baseline = 1, current = 2),
    CatalogPart.TEMPLATE to PartSchemaWindow(baseline = 2, current = 2),
)

/** The current wire schema version of the **manifest** part (`catalog.json`). */
val CATALOG_MANIFEST_SCHEMA_VERSION: Int = CATALOG_PART_SCHEMAS.getValue(CatalogPart.MANIFEST).current

/** The oldest manifest wire schema version this instance can still upgrade. */
val CATALOG_MANIFEST_BASELINE_SCHEMA_VERSION: Int = CATALOG_PART_SCHEMAS.getValue(CatalogPart.MANIFEST).baseline

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
