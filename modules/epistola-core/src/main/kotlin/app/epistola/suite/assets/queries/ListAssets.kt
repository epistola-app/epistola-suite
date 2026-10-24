// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.queries

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.versionAt
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
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
        val sql = StringBuilder(
            """
            SELECT a.id, a.tenant_key, c.catalog_key AS catalog_key, c.namespace AS namespace, a.name, a.media_type, a.size_bytes, a.width, a.height, a.created_at, a.created_by
            FROM assets a
            JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id
            WHERE a.tenant_key = :tenantId
              AND ${inCurrentVersion("a")}
            """,
        )

        if (query.catalogKey != null) {
            sql.append(" AND a.version_id = ${versionAt("tenantId", "catalogKey")}")
        }
        if (!query.searchTerm.isNullOrBlank()) {
            sql.append(" AND a.name ILIKE :searchTerm")
        }

        sql.append(" ORDER BY a.created_at DESC")

        val q = handle.createQuery(sql.toString())
            .bind("tenantId", query.tenantId)

        if (query.catalogKey != null) {
            q.bindCatalog("catalogKey", query.catalog)
        }
        if (!query.searchTerm.isNullOrBlank()) {
            q.bind("searchTerm", "%${query.searchTerm}%")
        }

        q.map { rs, _ ->
            Asset(
                id = AssetKey(rs.getString("id")),
                tenantKey = TenantKey(rs.getString("tenant_key")),
                catalogKey = CatalogKey(rs.getString("catalog_key")),
                namespace = rs.getString("namespace"),
                name = rs.getString("name"),
                mediaType = AssetMediaType.fromMimeType(rs.getString("media_type")),
                sizeBytes = rs.getLong("size_bytes"),
                width = rs.getObject("width", Int::class.javaObjectType),
                height = rs.getObject("height", Int::class.javaObjectType),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                createdBy = rs.getObject("created_by", UUID::class.java)?.let { UserKey(it) },
            )
        }
            .list()
    }
}
