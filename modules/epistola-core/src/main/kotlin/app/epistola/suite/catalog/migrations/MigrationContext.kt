package app.epistola.suite.catalog.migrations

import tools.jackson.databind.node.ObjectNode

/**
 * Context handed to each [CatalogSchemaMigration] step.
 *
 * [sourceVersion] / [targetVersion] are the endpoints of the **part's** chain
 * being run (the payload's original version for that part and the part's
 * current), not the individual step's `from`/`to` — useful for logging or
 * version-conditional logic. [manifest] is reserved for cross-part steps that
 * need catalog-level data while migrating a resource detail. The migrator does
 * not thread it through yet, so it is currently always `null`; wire it up
 * alongside the first migration that actually needs it.
 */
data class MigrationContext(
    val sourceVersion: Int,
    val targetVersion: Int,
    val manifest: ObjectNode? = null,
)
