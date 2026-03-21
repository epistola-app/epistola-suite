package app.epistola.suite.assets.queries

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.common.ids.AssetKey
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
 * Query to get a single asset's metadata.
 *
 * @property tenantId Tenant that owns the asset
 * @property assetId The asset ID
 */
data class GetAsset(
    val tenantId: TenantKey,
    val assetId: AssetKey,
) : Query<Asset?>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetAssetHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetAsset, Asset?> {

    override fun handle(query: GetAsset): Asset? = jdbi.withHandle<Asset?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_key, name, media_type, size_bytes, width, height, created_at
            FROM assets
            WHERE id = :assetId
              AND tenant_key = :tenantId
            """,
        )
            .bind("assetId", query.assetId.value)
            .bind("tenantId", query.tenantId)
            .map { rs, _ ->
                Asset(
                    id = AssetKey(rs.getObject("id", UUID::class.java)),
                    tenantKey = TenantKey(rs.getString("tenant_key")),
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
