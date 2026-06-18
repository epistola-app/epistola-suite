package app.epistola.suite.catalog.migrations.steps

import app.epistola.suite.catalog.migrations.CatalogSchemaMigration
import app.epistola.suite.catalog.migrations.MigrationContext
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * **Example** catalog wire-format migration: `v3 → v4`.
 *
 * Demonstrates a real content transform: when migrating a `v3` catalog it
 * appends a text block reading **"upgrade naar versie 4"** to every template
 * (the top-level model and each variant model) — see [injectTemplateNotice].
 * Non-template resources and the manifest pass through unchanged.
 *
 * To write your own migration: copy this shape — declare `from`, override
 * [migrateManifest] and/or [migrateResourceDetail], register as a `@Component`,
 * and bump `CATALOG_SCHEMA_VERSION`.
 */
@Component
class CatalogV3ToV4ExampleMigration : CatalogSchemaMigration {
    override val from: Int = 3

    override fun migrateResourceDetail(type: String, node: ObjectNode, ctx: MigrationContext): ObjectNode {
        injectTemplateNotice(node, nodeId = "n-migration-notice-v4", text = "upgrade naar versie 4")
        return node
    }
}
