package app.epistola.suite.catalog

class CatalogInUseException(
    val catalogKey: CatalogKey,
    val references: List<String>,
) : RuntimeException(
    "Cannot delete catalog '${catalogKey.value}': referenced by ${references.joinToString(", ")}",
)
