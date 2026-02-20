package app.epistola.suite.assets.commands

import app.epistola.suite.assets.AssetInUseException
import app.epistola.suite.assets.queries.FindAssetUsagesHandler
import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Command to delete an asset.
 *
 * @property tenantId Tenant that owns the asset
 * @property assetId The asset ID to delete
 */
data class DeleteAsset(
    val tenantId: TenantId,
    val assetId: AssetId,
) : Command<Boolean>

@Component
class DeleteAssetHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteAsset, Boolean> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: DeleteAsset): Boolean {
        logger.info("Deleting asset {} for tenant {}", command.assetId, command.tenantId)

        return jdbi.inTransaction<Boolean, Exception> { handle ->
            val usages = FindAssetUsagesHandler.findAssetUsages(handle, command.tenantId, command.assetId)
            if (usages.isNotEmpty()) {
                throw AssetInUseException(command.assetId, usages)
            }

            val deleted = handle.createUpdate(
                """
                DELETE FROM assets
                WHERE id = :assetId
                  AND tenant_id = :tenantId
                """,
            )
                .bind("assetId", command.assetId.value)
                .bind("tenantId", command.tenantId)
                .execute()

            if (deleted > 0) {
                logger.info("Deleted asset {}", command.assetId)
                true
            } else {
                logger.warn("Asset {} not found for tenant {}", command.assetId, command.tenantId)
                false
            }
        }
    }
}
