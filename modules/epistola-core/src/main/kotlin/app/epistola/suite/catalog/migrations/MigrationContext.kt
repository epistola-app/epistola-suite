package app.epistola.suite.catalog.migrations

import tools.jackson.databind.node.ObjectNode

/**
 * Context handed to each [CatalogSchemaMigration] step.
 *
 * [sourceVersion] / [targetVersion] are the endpoints of the **part's** chain
 * being run (the payload's original version for that part and the part's
 * current), not the individual step's `from`/`to` — useful for logging or
 * version-conditional logic. [manifest] is the already-migrated manifest tree,
 * available when migrating a resource detail so a cross-part step can read
 * catalog-level data; it is `null` while migrating the manifest itself.
 */
data class MigrationContext(
    val sourceVersion: Int,
    val targetVersion: Int,
    val manifest: ObjectNode? = null,
)
