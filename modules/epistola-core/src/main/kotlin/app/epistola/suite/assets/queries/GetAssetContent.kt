// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.queries

import app.epistola.suite.assets.AssetContent
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.storage.AssetContentStore
import app.epistola.suite.validation.validate
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
    /** The asset's catalog, by its address (ADR 0034, section 9); null for whichever has it. */
    val catalog: CatalogId? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<AssetContent?>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantId) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetAssetContentHandler(
    private val jdbi: Jdbi,
    private val assetContentStore: AssetContentStore,
) : QueryHandler<GetAssetContent, AssetContent?> {

    override fun handle(query: GetAssetContent): AssetContent? {
        // 1. The image, with the hash its bytes are stored under.
        val metadata = jdbi.withHandle<AssetMeta?, Exception> { handle ->
            handle.loadAssetMeta(query.tenantId, query.assetId, query.catalog)
        } ?: return null

        // 2. Its bytes, by that hash. An image without one had no bytes to keep (V20261025134500).
        val hash = metadata.contentHash ?: return null
        val bytes = assetContentStore.get(hash)?.content?.readAllBytes() ?: return null

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
)

private fun Handle.loadAssetMeta(tenantId: TenantKey, assetId: AssetKey, catalog: app.epistola.suite.common.ids.CatalogId?): AssetMeta? = createQuery(
    """
    SELECT a.id, a.tenant_key, (SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id) AS catalog_key,
           a.media_type, a.content_hash
    FROM current_images a
    WHERE a.id = :assetId
      AND a.tenant_key = :tenantId
      ${if (catalog != null) "AND a.catalog_id = ${catalogAt("tenantId", "catalogKey")}" else ""}
    LIMIT 1
    """,
)
    .bind("assetId", assetId.value)
    .bind("tenantId", tenantId)
    .apply { catalog?.let { bindCatalog("catalogKey", it) } }
    .map { rs, _ ->
        AssetMeta(
            id = AssetKey(rs.getString("id")),
            tenantId = TenantKey(rs.getString("tenant_key")),
            catalogKey = CatalogKey.of(rs.getString("catalog_key")),
            mediaType = AssetMediaType.fromMimeType(rs.getString("media_type")),
            contentHash = rs.getString("content_hash"),
        )
    }
    .findOne()
    .orElse(null)
