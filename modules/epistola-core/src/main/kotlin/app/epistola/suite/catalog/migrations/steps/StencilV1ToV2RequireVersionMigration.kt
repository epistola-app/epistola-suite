package app.epistola.suite.catalog.migrations.steps

import app.epistola.suite.catalog.CatalogPart
import app.epistola.suite.catalog.migrations.CatalogSchemaMigration
import app.epistola.suite.catalog.migrations.MigrationContext
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * **Stencil v1 → v2** — the first real catalog wire-format migration.
 *
 * History (ADR 0003, `epistola-model 0.6.0`): before stencil **v2** a stencil
 * rode the wire as its *latest published content only*, with **no version
 * number** — the importer assigned `MAX(target) + 1`. v2 made `version: Int`
 * **required** on `StencilResource`, so a v1 export (a stencil detail with no
 * `version`) cannot bind to the current typed model. Until this migration the
 * importer's answer was a hard failure ("missing version, please re-export");
 * now the v1 payload is mechanically upgraded instead.
 *
 * The transform: if `resource.version` is absent, stamp it [LEGACY_STENCIL_VERSION]
 * (`1`). A v1 wire carried exactly one published version's content, so
 * representing it as version 1 is the faithful single-version mapping. A stencil
 * that already carries a `version` is left untouched, so the step is idempotent
 * and safe on already-current content.
 *
 * Only the [CatalogPart.STENCIL] part changed; every other part is unaffected
 * (each is versioned independently — see
 * `docs/adr/0006-catalog-wire-format-migrations.md` and
 * `docs/exchange/resources/stencil/`).
 */
@Component
class StencilV1ToV2RequireVersionMigration : CatalogSchemaMigration {
    override val part = CatalogPart.STENCIL
    override val from = 1

    override fun migrate(node: ObjectNode, ctx: MigrationContext): ObjectNode {
        val resource = node.get("resource") as? ObjectNode ?: return node
        if (!resource.has("version")) {
            resource.put("version", LEGACY_STENCIL_VERSION)
        }
        return node
    }

    companion object {
        /**
         * The version assigned to a v1 stencil that carried no number. The old
         * wire expressed a single published version's content, mapped here to
         * version 1.
         */
        const val LEGACY_STENCIL_VERSION: Int = 1
    }
}
