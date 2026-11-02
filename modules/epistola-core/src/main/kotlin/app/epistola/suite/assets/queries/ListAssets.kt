// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.queries

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.IMAGE_COLUMNS
import app.epistola.suite.assets.IMAGE_ROWS
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inVersionOrNext
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.releases.addressedVersion
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Query to list assets for a tenant.
 *
 * @property tenantId Tenant that owns the assets
 * @property searchTerm Optional search filter on asset name
 * @property catalogKey Optional catalog filter
 */
data class ListAssets(
    val tenantId: TenantKey,
    val catalog: CatalogId? = null,
    val searchTerm: String? = null,
    /** The catalog version this addresses (each other catalog is read in its next version); null for every catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : ReleaseReadable,
    Query<List<Asset>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantId) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class ListAssetsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListAssets, List<Asset>> {

    override fun handle(query: ListAssets): List<Asset> = jdbi.withHandle<List<Asset>, Exception> { handle ->
        val sql = StringBuilder("SELECT $IMAGE_COLUMNS FROM version_images a WHERE a.tenant_key = :tenantId AND ${inVersionOrNext("a", "version")}")
        if (query.catalogKey != null) sql.append(" AND a.catalog_id = ${catalogAt("tenantId", "catalogKey")}")
        if (!query.searchTerm.isNullOrBlank()) sql.append(" AND a.name ILIKE :searchTerm")
        sql.append(" ORDER BY a.created_at DESC")

        val q = handle.createQuery(sql.toString()).bind("tenantId", query.tenantId).bindVersion("version", handle.addressedVersion(query))
        if (query.catalogKey != null) q.bindCatalog("catalogKey", query.catalog)
        if (!query.searchTerm.isNullOrBlank()) q.bind("searchTerm", "%${query.searchTerm}%")
        q.map(IMAGE_ROWS).list()
    }
}
