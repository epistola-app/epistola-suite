package app.epistola.suite.assets.queries

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
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
    val searchTerm: String? = null,
    val catalogKey: CatalogKey? = null,
) : Query<List<Asset>>,
    RequiresPermission {
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
            SELECT a.id, a.tenant_key, a.catalog_key, c.type AS catalog_type, a.name, a.media_type, a.size_bytes, a.width, a.height, a.created_at
            FROM assets a
            JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.id = a.catalog_key
            WHERE a.tenant_key = :tenantId
            """,
        )

        if (query.catalogKey != null) {
            sql.append(" AND a.catalog_key = :catalogKey")
        }
        if (!query.searchTerm.isNullOrBlank()) {
            sql.append(" AND a.name ILIKE :searchTerm")
        }

        sql.append(" ORDER BY a.created_at DESC")

        val q = handle.createQuery(sql.toString())
            .bind("tenantId", query.tenantId)

        if (query.catalogKey != null) {
            q.bind("catalogKey", query.catalogKey)
        }
        if (!query.searchTerm.isNullOrBlank()) {
            q.bind("searchTerm", "%${query.searchTerm}%")
        }

        q.map { rs, _ ->
            Asset(
                id = AssetKey(rs.getObject("id", UUID::class.java)),
                tenantKey = TenantKey(rs.getString("tenant_key")),
                catalogKey = CatalogKey(rs.getString("catalog_key")),
                catalogType = CatalogType.valueOf(rs.getString("catalog_type")),
                name = rs.getString("name"),
                mediaType = AssetMediaType.fromMimeType(rs.getString("media_type")),
                sizeBytes = rs.getLong("size_bytes"),
                width = rs.getObject("width", Integer::class.java)?.toInt(),
                height = rs.getObject("height", Integer::class.java)?.toInt(),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            )
        }
            .list()
    }
}
