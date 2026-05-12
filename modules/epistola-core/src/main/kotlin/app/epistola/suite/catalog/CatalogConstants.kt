package app.epistola.suite.catalog

/**
 * Installation order for catalog resources — dependencies must be installed
 * before dependents. `codeList` precedes `attribute` because an attribute can
 * bind to a code list (`AttributeResource.codeListBinding`), and the bound
 * list's row must exist when the FK is enforced by `attr_code_list_fk`.
 */
val RESOURCE_INSTALL_ORDER: Map<String, Int> = mapOf(
    "asset" to 0,
    "codeList" to 1,
    "attribute" to 2,
    "theme" to 3,
    "stencil" to 4,
    "template" to 5,
)
