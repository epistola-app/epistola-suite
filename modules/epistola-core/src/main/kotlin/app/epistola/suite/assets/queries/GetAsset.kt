// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.queries

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<Asset?>,
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
            SELECT a.id, a.tenant_key, c.catalog_key AS catalog_key, c.namespace AS namespace, a.name, a.media_type, a.size_bytes, a.width, a.height, a.created_at, a.created_by
            FROM assets a
            JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id
            WHERE a.id = :assetId
              AND a.tenant_key = :tenantId
              AND ${inCurrentVersion("a")}
            """,
        )
            .bind("assetId", query.assetId.value)
            .bind("tenantId", query.tenantId)
            .map { rs, _ ->
                Asset(
                    id = AssetKey(rs.getString("id")),
                    tenantKey = TenantKey(rs.getString("tenant_key")),
                    catalogKey = CatalogKey.of(rs.getString("catalog_key")),
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
            .findOne()
            .orElse(null)
    }
}
