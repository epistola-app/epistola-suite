package app.epistola.suite.catalog.migrations

/**
 * Context handed to each [CatalogSchemaMigration] step.
 *
 * [sourceVersion] / [targetVersion] are the endpoints of the catalog's chain
 * being run (the payload's original catalog version and the current version),
 * not the individual step's `from`/`to` — useful for logging or
 * version-conditional logic.
 */
data class MigrationContext(
    val sourceVersion: Int,
    val targetVersion: Int,
)
