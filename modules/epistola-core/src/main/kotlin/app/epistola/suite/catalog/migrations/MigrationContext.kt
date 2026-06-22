package app.epistola.suite.catalog.migrations

import tools.jackson.databind.node.ObjectNode

/**
 * Context handed to each [CatalogSchemaMigration] step.
 *
 * [sourceVersion] / [targetVersion] are the endpoints of the catalog's chain
 * being run (the payload's original catalog version and the current version),
 * not the individual step's `from`/`to` — useful for logging or
 * version-conditional logic.
 *
 * [manifest] is the **migrated (current-shape) manifest tree**, available when
 * migrating a resource detail so a **cross-part** step can read catalog-level
 * data (e.g. lift a field out of the manifest into a detail, or vice versa). It
 * is `null` while migrating the manifest itself.
 */
data class MigrationContext(
    val sourceVersion: Int,
    val targetVersion: Int,
    val manifest: ObjectNode? = null,
)
