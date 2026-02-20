package app.epistola.suite.assets.queries

import app.epistola.suite.assets.AssetContent
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Query to get an asset's binary content.
 * Used for serving images and PDF generation.
 *
 * @property tenantId Tenant that owns the asset
 * @property assetId The asset ID
 */
data class GetAssetContent(
    val tenantId: TenantId,
    val assetId: AssetId,
) : Query<AssetContent?>

@Component
class GetAssetContentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetAssetContent, AssetContent?> {

    override fun handle(query: GetAssetContent): AssetContent? = jdbi.withHandle<AssetContent?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_id, media_type, content
            FROM assets
            WHERE id = :assetId
              AND tenant_id = :tenantId
            """,
        )
            .bind("assetId", query.assetId.value)
            .bind("tenantId", query.tenantId)
            .map { rs, _ ->
                AssetContent(
                    id = AssetId(rs.getObject("id", UUID::class.java)),
                    tenantId = TenantId(rs.getString("tenant_id")),
                    mediaType = AssetMediaType.fromMimeType(rs.getString("media_type")),
                    content = rs.getBytes("content"),
                )
            }
            .findOne()
            .orElse(null)
    }
}
