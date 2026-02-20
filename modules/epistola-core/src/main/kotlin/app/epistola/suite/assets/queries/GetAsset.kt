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
 * Query to get a single asset's metadata.
 *
 * @property tenantId Tenant that owns the asset
 * @property assetId The asset ID
 */
data class GetAsset(
    val tenantId: TenantId,
    val assetId: AssetId,
) : Query<Asset?>

@Component
class GetAssetHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetAsset, Asset?> {

    override fun handle(query: GetAsset): Asset? = jdbi.withHandle<Asset?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_id, name, media_type, size_bytes, width, height, created_at
            FROM assets
            WHERE id = :assetId
              AND tenant_id = :tenantId
            """,
        )
            .bind("assetId", query.assetId.value)
            .bind("tenantId", query.tenantId)
            .map { rs, _ ->
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
            .findOne()
            .orElse(null)
    }
}
