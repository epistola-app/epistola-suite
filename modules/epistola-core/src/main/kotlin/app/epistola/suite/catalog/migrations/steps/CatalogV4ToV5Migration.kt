package app.epistola.suite.catalog.migrations.steps

import app.epistola.suite.catalog.migrations.CatalogSchemaMigration
import app.epistola.suite.catalog.migrations.MigrationContext
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * **Example** catalog wire-format migration: `v4 → v5`.
 *
 * Like [CatalogV3ToV4ExampleMigration], a content transform: when migrating a
 * `v4` catalog it appends a text block reading **"update naar versie 5"** to
 * every template — see [injectTemplateNotice]. Non-template resources and the
 * manifest pass through unchanged.
 */
@Component
class CatalogV4ToV5Migration : CatalogSchemaMigration {
    override val from: Int = 4

    override fun migrateResourceDetail(type: String, node: ObjectNode, ctx: MigrationContext): ObjectNode {
        injectTemplateNotice(node, nodeId = "n-migration-notice-v5", text = "update naar versie 5")
        return node
    }
}
