package app.epistola.suite.catalog.migrations

/**
 * Context handed to each [CatalogSchemaMigration] step.
 *
 * [sourceVersion] / [targetVersion] are the endpoints of the **part's** chain
 * being run (the payload's original version for that part and the part's
 * current), not the individual step's `from`/`to` — useful for logging or
 * version-conditional logic.
 */
data class MigrationContext(
    val sourceVersion: Int,
    val targetVersion: Int,
)
