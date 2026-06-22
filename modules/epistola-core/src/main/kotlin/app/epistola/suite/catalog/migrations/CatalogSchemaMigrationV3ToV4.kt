package app.epistola.suite.catalog.migrations

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Catalog wire schema **v3 → v4**.
 *
 * The only non-additive change at this boundary: `StencilResource.version`
 * became a required `int` (epistola-model 0.6.0; see ADR 0003). A v3 stencil
 * detail lacks `version`, so it fails to bind to the current model. This step
 * injects `version = 1` — pre-0.6.0 exports carried a single stencil version, so
 * 1 is the faithful default.
 *
 * The manifest's v4 additions (`release.fingerprint`, 3-part SemVer
 * `release.version`) are nullable / free-form and bind from a v3 manifest as-is,
 * so [migrateManifest] stays identity. Stored content is unaffected — a stencil's
 * version is the relational `stencil_versions.id`, not part of the content blob —
 * so [migrateContentBlob] stays identity too.
 *
 * It also appends a visible **"migratie naar versie 4"** text block to every
 * template on import (see [injectTemplateNotice]) — a marker that the migration
 * ran.
 *
 * See `docs/adr/0007-at-rest-resource-migration.md`.
 */
@Component
class CatalogSchemaMigrationV3ToV4 : CatalogSchemaMigration {
    override val from = 3

    /** Wire/import path: inject the stencil version (if missing) and the notice. */
    override fun migrateResourceDetail(type: String, detail: ObjectNode, ctx: MigrationContext): ObjectNode {
        if (type == "stencil") {
            (detail.get("resource") as? ObjectNode)?.let { resource ->
                if (!resource.has("version")) resource.put("version", DEFAULT_STENCIL_VERSION)
            }
        }
        injectTemplateNotice(detail, nodeId = NOTICE_NODE_ID, text = NOTICE_TEXT)
        return detail
    }

    /**
     * At-rest path: inject the notice into a stored `template_model` blob. The
     * stencil version is the relational `stencil_versions.id` (always present at
     * rest), so there is nothing to do for stencil content here.
     */
    override fun migrateContentBlob(blobType: String, blob: JsonNode, ctx: MigrationContext): JsonNode {
        if (blobType == ContentBlobType.TEMPLATE_MODEL && blob is ObjectNode) {
            appendTextBlock(blob, nodeId = NOTICE_NODE_ID, text = NOTICE_TEXT)
        }
        return blob
    }

    private companion object {
        const val DEFAULT_STENCIL_VERSION = 1
        const val NOTICE_NODE_ID = "n-migration-notice-v4"
        const val NOTICE_TEXT = "migratie naar versie 4"
    }
}
