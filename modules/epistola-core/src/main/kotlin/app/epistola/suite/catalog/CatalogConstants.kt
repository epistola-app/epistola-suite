package app.epistola.suite.catalog

/** Installation order for catalog resources — dependencies must be installed before dependents. */
val RESOURCE_INSTALL_ORDER: Map<String, Int> = mapOf(
    "asset" to 0,
    "attribute" to 1,
    "theme" to 2,
    "stencil" to 3,
    "template" to 4,
)
