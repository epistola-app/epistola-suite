package app.epistola.suite.catalog.migrations

import tools.jackson.databind.node.ObjectNode

/**
 * Context handed to each [CatalogSchemaMigration] step.
 *
 * [sourceVersion] / [targetVersion] are the chain's overall endpoints (the
 * payload's original version and the current version), not the individual step's
 * `from`/`to` — useful for logging or version-conditional logic. [manifest] is
 * the already-migrated manifest tree, available when migrating a resource detail
 * so a step can lift catalog-level data into a detail (or vice versa); it is
 * `null` while migrating the manifest itself.
 */
data class MigrationContext(
    val sourceVersion: Int,
    val targetVersion: Int,
    val manifest: ObjectNode? = null,
)
