// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.commands

import app.epistola.suite.assets.AssetInUseException
import app.epistola.suite.assets.queries.FindAssetUsages
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Command to delete an asset.
 *
 * @property tenantId Tenant that owns the asset
 * @property assetId The asset ID to delete
 * @property force If true, skip usage check and delete even if in use
 */
data class DeleteAsset(
    val tenantId: TenantKey,
    val assetId: AssetKey,
    val force: Boolean = false,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey get() = tenantId
}

@Component
class DeleteAssetHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteAsset, Boolean> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: DeleteAsset): Boolean {
        // Check catalog editability before proceeding
        val catalogKey = jdbi.withHandle<CatalogKey, Exception> { handle ->
            handle.createQuery("SELECT catalog_key FROM assets WHERE tenant_key = :tenantId AND id = :assetId")
                .bind("tenantId", command.tenantId)
                .bind("assetId", command.assetId)
                .map { rs, _ -> CatalogKey(rs.getString("catalog_key")) }
                .findOne()
                .orElse(null)
        } ?: return false
        requireCatalogEditable(command.tenantId, catalogKey)

        if (!command.force) {
            val usages = FindAssetUsages(command.tenantId, command.assetId).query()
            if (usages.isNotEmpty()) {
                throw AssetInUseException(command.assetId, usages)
            }
        }

        logger.info("Deleting asset {} for tenant {}", command.assetId, command.tenantId)

        val deleted = jdbi.withHandle<Boolean, Exception> { handle ->
            val rowsDeleted = handle.createUpdate(
                """
                DELETE FROM assets
                WHERE id = :assetId
                  AND tenant_key = :tenantId
                """,
            )
                .bind("assetId", command.assetId.value)
                .bind("tenantId", command.tenantId)
                .execute()

            rowsDeleted > 0
        }

        if (deleted) {
            // The blob is NOT deleted here: under the content-addressable store a blob
            // may back many assets. The content reaper mark-and-sweeps it once no
            // `assets` row references its (scope, content_hash). (#738)
            logger.info("Deleted asset {}", command.assetId)
        } else {
            logger.warn("Asset {} not found for tenant {}", command.assetId, command.tenantId)
        }

        return deleted
    }
}
