package app.epistola.suite.catalog.migrations

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Catalog wire schema **v2 → v3**.
 *
 * The boundary itself was **purely additive** (epistola-model 0.4.0): v3
 * introduced `CodeListResource`, `DependencyRef.CodeList`, and the optional
 * `AttributeResource.codeListBinding`. Nothing was renamed, restructured, or made
 * required — a v2 attribute keeps its inline `allowedValues` and simply lacks
 * `codeListBinding`, which binds as `null` — so no content reshape is required to
 * bind a v2 payload. Converting inline `allowedValues` into a `codeListBinding`
 * would be data loss, not a migration, so it is not done.
 *
 * Beyond that, this step appends a visible **"migratie naar versie 3"** text
 * block to every template on import (see [injectTemplateNotice]) — a marker that
 * the migration ran. Non-template resources pass through unchanged.
 *
 * See `docs/adr/0006-catalog-wire-format-migrations.md`.
 */
@Component
class CatalogSchemaMigrationV2ToV3 : CatalogSchemaMigration {
    override val from = 2

    /** Wire/import path: inject the notice into every template's models. */
    override fun migrateResourceDetail(type: String, detail: ObjectNode, ctx: MigrationContext): ObjectNode {
        injectTemplateNotice(detail, nodeId = NOTICE_NODE_ID, text = NOTICE_TEXT)
        return detail
    }

    /** At-rest path: inject the notice into a stored `template_model` blob. */
    override fun migrateContentBlob(blobType: String, blob: JsonNode, ctx: MigrationContext): JsonNode {
        if (blobType == ContentBlobType.TEMPLATE_MODEL && blob is ObjectNode) {
            appendTextBlock(blob, nodeId = NOTICE_NODE_ID, text = NOTICE_TEXT)
        }
        return blob
    }

    private companion object {
        const val NOTICE_NODE_ID = "n-migration-notice-v3"
        const val NOTICE_TEXT = "migratie naar versie 3"
    }
}
