package app.epistola.suite.fonts.commands

import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.fonts.model.FontInUseException
import app.epistola.suite.fonts.queries.FindFontUsages
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Deletes a font family and the uploaded binaries it owned.
 *
 * Mirrors `app.epistola.suite.assets.commands.DeleteAsset`:
 *  - `requireCatalogEditable` — SUBSCRIBED catalogs (e.g. `system`) reject the
 *    delete with `CatalogReadOnlyException`, the same exception every other
 *    catalog-mutating command throws.
 *  - Usage guard — unless [force], a font referenced by a theme or template
 *    version refuses with [FontInUseException] (mirrors `AssetInUseException`).
 *
 * Deletion order matters: `font_variants.asset_key → assets` is
 * `ON DELETE RESTRICT`. We (1) collect the ASSET-sourced asset keys, (2) delete
 * the `fonts` row (cascading `font_variants` away), then (3) delete each backing
 * asset via [DeleteAsset] (`force = true`) so the shared asset machinery removes
 * both the row and the content-store blob. CLASSPATH variants own no asset and
 * are left untouched.
 *
 * @property force If true, skip the usage check and delete even if in use.
 */
data class DeleteFont(
    val fontId: FontId,
    val force: Boolean = false,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = fontId.tenantKey
}

@Component
class DeleteFontHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteFont, Boolean> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: DeleteFont): Boolean {
        val tenantKey = command.fontId.tenantKey
        val catalogKey = command.fontId.catalogKey
        val slug = command.fontId.key

        // Catalog editability — SUBSCRIBED (e.g. `system`) rejects.
        requireCatalogEditable(tenantKey, catalogKey)

        val exists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(
                """
                SELECT COUNT(*) > 0
                FROM fonts
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND slug = :slug
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("catalogKey", catalogKey)
                .bind("slug", slug)
                .mapTo(Boolean::class.java)
                .one()
        }
        if (!exists) return false

        if (!command.force) {
            val usages = FindFontUsages(command.fontId).query()
            if (usages.isNotEmpty()) {
                throw FontInUseException(slug, usages)
            }
        }

        // Collect ASSET-sourced backing binaries before removing the rows.
        val assetKeys = jdbi.withHandle<List<AssetKey>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT asset_key
                FROM font_variants
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND font_slug = :slug
                  AND source = 'ASSET' AND asset_key IS NOT NULL
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("catalogKey", catalogKey)
                .bind("slug", slug)
                .map { rs, _ -> AssetKey.of(rs.getObject("asset_key", java.util.UUID::class.java)) }
                .list()
        }

        logger.info("Deleting font {} ({}/{}) for tenant {}", slug, catalogKey, slug, tenantKey)

        // Delete the family row — font_variants cascade away, releasing the
        // ON DELETE RESTRICT FK so the backing assets can now be removed.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                DELETE FROM fonts
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND slug = :slug
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("catalogKey", catalogKey)
                .bind("slug", slug)
                .execute()
        }

        // Reuse the asset machinery to drop the row + content-store blob.
        // force = true: the variant rows are already gone, so the asset is no
        // longer referenced; the usage check ran at the font level above.
        for (assetKey in assetKeys) {
            DeleteAsset(tenantId = tenantKey, assetId = assetKey, force = true).execute()
        }

        logger.info("Deleted font {} and {} backing asset(s)", slug, assetKeys.size)
        return true
    }
}
