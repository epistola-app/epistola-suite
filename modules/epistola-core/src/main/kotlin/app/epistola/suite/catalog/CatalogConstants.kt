package app.epistola.suite.catalog

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
