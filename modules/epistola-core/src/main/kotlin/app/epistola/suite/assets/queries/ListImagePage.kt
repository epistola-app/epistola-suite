// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.queries

import app.epistola.suite.assets.Asset
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.IMAGE_COLUMNS
import app.epistola.suite.assets.IMAGE_ROWS
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
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
    val catalog: CatalogId,
    val searchTerm: String? = null,
    val limit: Int,
    val offset: Int,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<ImagePage>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ListImagePageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListImagePage, ImagePage> {

    override fun handle(query: ListImagePage): ImagePage = jdbi.withHandle<ImagePage, Exception> { handle ->
        val rows = handle.createQuery(
            """
            SELECT $IMAGE_COLUMNS, COUNT(*) OVER()::bigint AS total_count
            FROM current_images a
            WHERE a.tenant_key = :tenantKey
              AND a.catalog_id = ${catalogAt("tenantKey", "catalogKey")}
              AND a.media_type LIKE 'image/%'
              AND (CAST(:searchTerm AS text) IS NULL OR a.name ILIKE :searchTerm)
            ORDER BY a.created_at DESC, a.id
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bindCatalog("catalogKey", query.catalog)
            .bind("searchTerm", query.searchTerm?.let { "%$it%" })
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .map { rs, ctx -> IMAGE_ROWS.map(rs, ctx) to rs.getLong("total_count") }
            .list()

        ImagePage(items = rows.map { it.first }, totalElements = rows.firstOrNull()?.second ?: 0L)
    }
}
