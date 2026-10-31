// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.commands

import app.epistola.suite.assets.AssetInUseException
import app.epistola.suite.assets.ImageStore
import app.epistola.suite.assets.queries.FindAssetUsages
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
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
 * @property assetId The asset ID to delete; refused with [AssetInUseException] while anything uses it
 */
data class DeleteAsset(
    val tenantId: TenantKey,
    val assetId: AssetKey,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey get() = tenantId
}

@Component
class DeleteAssetHandler(
    private val jdbi: Jdbi,
    private val images: ImageStore,
) : CommandHandler<DeleteAsset, Boolean> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: DeleteAsset): Boolean {
        // The image's catalog: an image key names one image of the tenant's current versions.
        val catalog = jdbi.withHandle<app.epistola.suite.common.ids.CatalogId?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT c.catalog_key, c.namespace FROM current_images a
                JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id
                WHERE a.tenant_key = :tenantId AND a.id = :assetId
                LIMIT 1
                """,
            )
                .bind("tenantId", command.tenantId)
                .bind("assetId", command.assetId.value)
                .map { rs, _ -> app.epistola.suite.common.ids.CatalogId(rs.getString("namespace"), CatalogKey(rs.getString("catalog_key")), app.epistola.suite.common.ids.TenantId(command.tenantId)) }
                .findOne()
                .orElse(null)
        } ?: return false
        requireCatalogEditable(catalog)

        val usages = FindAssetUsages(command.tenantId, command.assetId).query()
        if (usages.isNotEmpty()) {
            throw AssetInUseException(command.assetId, usages)
        }

        logger.info("Deleting image {} for tenant {}", command.assetId, command.tenantId)
        // Its bytes go when no content names them any more (the content reaper, #738).
        return jdbi.withHandle<Boolean, Exception> { images.delete(it, catalog, command.assetId.value) }
    }
}
