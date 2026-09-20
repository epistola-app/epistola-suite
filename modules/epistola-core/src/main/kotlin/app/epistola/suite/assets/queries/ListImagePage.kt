// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.queries

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.CatalogType
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

/** One page of images, and how many there are in total. */
data class ImagePage(
    val items: List<Asset>,
    val totalElements: Long,
)

/**
 * One page of a catalog's images, ordered newest first.
 *
 * Paged in the database rather than by loading every row and slicing in Kotlin: a tenant's image
 * count is unbounded, and the page a caller asked for should cost the page, not the catalog. The
 * total rides along on each row through a window function, so the count and the page come from one
 * scan and cannot disagree with each other.
 *
 * Images only. A font face's binary lives in the same table but is not something anyone browses --
 * it means something as a face of a family, which the fonts API describes.
 */
data class ListImagePage(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val searchTerm: String? = null,
    val limit: Int,
    val offset: Int,
) : Query<ImagePage>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ListImagePageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListImagePage, ImagePage> {

    override fun handle(query: ListImagePage): ImagePage = jdbi.withHandle<ImagePage, Exception> { handle ->
        val rows = handle.createQuery(
            """
            SELECT a.id, a.tenant_key, a.catalog_key, c.type AS catalog_type, a.name, a.media_type,
                   a.size_bytes, a.width, a.height, a.created_at, a.created_by,
                   COUNT(*) OVER()::bigint AS total_count
            FROM assets a
            JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.id = a.catalog_key
            WHERE a.tenant_key = :tenantKey
              AND a.catalog_key = :catalogKey
              AND a.media_type LIKE 'image/%'
              AND (CAST(:searchTerm AS text) IS NULL OR a.name ILIKE :searchTerm)
            ORDER BY a.created_at DESC, a.id
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .bind("searchTerm", query.searchTerm?.let { "%$it%" })
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .map { rs, _ ->
                Asset(
                    id = AssetKey.of(rs.getString("id")),
                    tenantKey = TenantKey.of(rs.getString("tenant_key")),
                    catalogKey = CatalogKey.of(rs.getString("catalog_key")),
                    catalogType = CatalogType.valueOf(rs.getString("catalog_type")),
                    name = rs.getString("name"),
                    mediaType = AssetMediaType.fromMimeType(rs.getString("media_type")),
                    sizeBytes = rs.getLong("size_bytes"),
                    width = rs.getObject("width") as? Int,
                    height = rs.getObject("height") as? Int,
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                    createdBy = rs.getObject("created_by", UUID::class.java)?.let { UserKey(it) },
                ) to rs.getLong("total_count")
            }
            .list()

        ImagePage(items = rows.map { it.first }, totalElements = rows.firstOrNull()?.second ?: 0L)
    }
}
