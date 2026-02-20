package app.epistola.suite.assets.queries

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Query to list assets for a tenant.
 *
 * @property tenantId Tenant that owns the assets
 * @property searchTerm Optional search filter on asset name
 */
data class ListAssets(
    val tenantId: TenantId,
    val searchTerm: String? = null,
) : Query<List<Asset>>

@Component
class ListAssetsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListAssets, List<Asset>> {

    override fun handle(query: ListAssets): List<Asset> = jdbi.withHandle<List<Asset>, Exception> { handle ->
        val sql = StringBuilder(
            """
            SELECT id, tenant_id, name, media_type, size_bytes, width, height, created_at
            FROM assets
            WHERE tenant_id = :tenantId
            """,
        )

        if (!query.searchTerm.isNullOrBlank()) {
            sql.append(" AND name ILIKE :searchTerm")
        }

        sql.append(" ORDER BY created_at DESC")

        val q = handle.createQuery(sql.toString())
            .bind("tenantId", query.tenantId)

        if (!query.searchTerm.isNullOrBlank()) {
            q.bind("searchTerm", "%${query.searchTerm}%")
        }

        q.map { rs, _ ->
            Asset(
                id = AssetId(rs.getObject("id", UUID::class.java)),
                tenantId = TenantId(rs.getString("tenant_id")),
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
