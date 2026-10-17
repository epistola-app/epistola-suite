// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.queries

import app.epistola.suite.assets.AssetContent
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.assetContentScope
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.storage.AssetContentStore
import app.epistola.suite.storage.backfill.LegacyBlobFallback
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Query to get an asset's binary content.
 * Used for serving images and PDF generation.
 *
 * Reads metadata from the `assets` table and binary content from [ContentStore].
 *
 * @property tenantId Tenant that owns the asset
 * @property assetId The asset ID
 * @property catalogKey Optional owning catalog. Assets are keyed by
 *   `(tenant, catalog, id)`, so a cross-catalog reference qualifies the lookup
 *   with its catalog. When null, the lookup falls back to assetId-only.
 */
data class GetAssetContent(
    val tenantId: TenantKey,
    val assetId: AssetKey,
    val catalogKey: CatalogKey? = null,
) : Query<AssetContent?>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetAssetContentHandler(
    private val jdbi: Jdbi,
    private val assetContentStore: AssetContentStore,
    // Transitional (#742): serves assets not yet migrated to asset_content (content_hash NULL).
    private val legacyBlobFallback: LegacyBlobFallback,
) : QueryHandler<GetAssetContent, AssetContent?> {

    override fun handle(query: GetAssetContent): AssetContent? {
        // 1. Get metadata (including the content-addressable pointer) from DB
        val metadata = jdbi.withHandle<AssetMeta?, Exception> { handle ->
            handle.loadAssetMeta(query.tenantId, query.assetId, query.catalogKey)
        } ?: return null

        // 2. Read content: from the content-addressable store by (scope, hash), or —
        // for assets not yet backfilled — from the legacy identity-keyed store.
        val bytes = if (metadata.contentHash != null) {
            val scope = assetContentScope(metadata.sensitive, metadata.tenantId)
            assetContentStore.get(scope, metadata.contentHash)?.content?.readAllBytes()
        } else {
            legacyBlobFallback.assetBytes(metadata.tenantId, metadata.id) // transitional (#742)
        } ?: return null

        return AssetContent(
            id = metadata.id,
            tenantKey = metadata.tenantId,
            catalogKey = metadata.catalogKey,
            mediaType = metadata.mediaType,
            content = bytes,
        )
    }
}

private data class AssetMeta(
    val id: AssetKey,
    val tenantId: TenantKey,
    val catalogKey: CatalogKey,
    val mediaType: AssetMediaType,
    val contentHash: String?,
    val sensitive: Boolean,
)

private fun Handle.loadAssetMeta(tenantId: TenantKey, assetId: AssetKey, catalogKey: CatalogKey?): AssetMeta? = createQuery(
    """
    SELECT a.id, a.tenant_key, c.catalog_key AS catalog_key, a.media_type, a.content_hash, a.sensitive
    FROM assets a
    JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id
    WHERE a.id = :assetId
      AND a.tenant_key = :tenantId
      ${if (catalogKey != null) "AND a.version_id = ${versionAt("tenantId", "catalogKey")}" else "AND ${inCurrentVersion("a")}"}
    """,
)
    .bind("assetId", assetId.value)
    .bind("tenantId", tenantId)
    .apply { catalogKey?.let { bind("catalogKey", it.value) } }
    .map { rs, _ ->
        AssetMeta(
            id = AssetKey(rs.getString("id")),
            tenantId = TenantKey(rs.getString("tenant_key")),
            catalogKey = CatalogKey.of(rs.getString("catalog_key")),
            mediaType = AssetMediaType.fromMimeType(rs.getString("media_type")),
            contentHash = rs.getString("content_hash"),
            sensitive = rs.getBoolean("sensitive"),
        )
    }
    .findOne()
    .orElse(null)
