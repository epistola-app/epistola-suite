package app.epistola.suite.assets.queries

import app.epistola.suite.assets.AssetContent
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Query to get an asset's binary content.
 * Used for serving images and PDF generation.
 *
 * Reads metadata from the `assets` table and binary content from [ContentStore].
 *
 * @property tenantId Tenant that owns the asset
 * @property assetId The asset ID
 */
data class GetAssetContent(
    val tenantId: TenantKey,
    val assetId: AssetKey,
) : Query<AssetContent?>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetAssetContentHandler(
    private val jdbi: Jdbi,
    private val contentStore: ContentStore,
) : QueryHandler<GetAssetContent, AssetContent?> {

    override fun handle(query: GetAssetContent): AssetContent? {
        // 1. Get metadata from DB
        val metadata = jdbi.withHandle<AssetMeta?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, tenant_key, catalog_key, media_type
                FROM assets
                WHERE id = :assetId
                  AND tenant_key = :tenantId
                """,
            )
                .bind("assetId", query.assetId.value)
                .bind("tenantId", query.tenantId)
                .map { rs, _ ->
                    AssetMeta(
                        id = AssetKey(rs.getObject("id", UUID::class.java)),
                        tenantId = TenantKey(rs.getString("tenant_key")),
                        catalogKey = CatalogKey.of(rs.getString("catalog_key")),
                        mediaType = AssetMediaType.fromMimeType(rs.getString("media_type")),
                    )
                }
                .findOne()
                .orElse(null)
        } ?: return null

        // 2. Read content from ContentStore (assets are max 5MB, in-memory is fine)
        val stored = contentStore.get(ContentKey.asset(metadata.tenantId, metadata.id))
            ?: return null

        return AssetContent(
            id = metadata.id,
            tenantKey = metadata.tenantId,
            catalogKey = metadata.catalogKey,
            mediaType = metadata.mediaType,
            content = stored.content.readAllBytes(),
        )
    }

    private data class AssetMeta(
        val id: AssetKey,
        val tenantId: TenantKey,
        val catalogKey: CatalogKey,
        val mediaType: AssetMediaType,
    )
}
