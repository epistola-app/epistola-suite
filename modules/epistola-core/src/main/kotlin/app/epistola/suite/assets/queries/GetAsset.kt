// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.queries

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.IMAGE_COLUMNS
import app.epistola.suite.assets.IMAGE_ROWS
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.inVersionOrNext
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.releases.addressedVersion
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Query to get a single asset's metadata.
 *
 * @property tenantId Tenant that owns the asset
 * @property assetId The asset ID
 */
data class GetAsset(
    val tenantId: TenantKey,
    val assetId: AssetKey,
    /** The catalog version this addresses (each other catalog is read in its next version); null for every catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : ReleaseReadable,
    Query<Asset?>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetAssetHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetAsset, Asset?> {

    override fun handle(query: GetAsset): Asset? = jdbi.withHandle<Asset?, Exception> { handle ->
        // An image key names one image of the version addressed and every other catalog's next version, whichever catalog has it.
        handle.createQuery("SELECT $IMAGE_COLUMNS FROM version_images a WHERE a.tenant_key = :tenantId AND a.id = :assetId AND ${inVersionOrNext("a", "version")} LIMIT 1")
            .bind("assetId", query.assetId.value)
            .bind("tenantId", query.tenantId)
            .bindVersion("version", handle.addressedVersion(query))
            .map(IMAGE_ROWS)
            .findOne()
            .orElse(null)
    }
}
