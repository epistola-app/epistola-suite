// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

internal fun mimeTypeToExtension(mimeType: String): String = when (mimeType) {
    "image/png" -> ".png"
    "image/jpeg" -> ".jpg"
    "image/webp" -> ".webp"
    "image/svg+xml" -> ".svg"
    else -> ""
}

data class ExportAssets(
    override val tenantKey: TenantKey,
    val assetIds: List<String>? = null,
    val catalogKey: CatalogKey? = null,
) : Query<List<ImageResource>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ExportAssetsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ExportAssets, List<ImageResource>> {

    override fun handle(query: ExportAssets): List<ImageResource> {
        data class AssetRow(
            val id: String,
            val name: String,
            val mediaType: String,
            val width: Int?,
            val height: Int?,
            val contentHash: String?,
        )

        val rows = jdbi.withHandle<List<AssetRow>, Exception> { handle ->
            val sql = buildString {
                // Images only. From wire v7 an image is a catalog resource and a binary is not, so
                // the font-face binaries sharing this table are not exported as resources -- a face
                // carries its own bytes inside its font. Matches `ListImagePage`, which the
                // `/images` surface uses for the same reason.
                append("SELECT id::text, name, media_type, width, height, content_hash FROM assets WHERE tenant_key = :tenantKey")
                append(" AND media_type LIKE 'image/%'")
                if (query.catalogKey != null) append(" AND catalog_key = :catalogKey")
                if (query.assetIds != null) append(" AND id::text IN (<assetIds>)")
            }
            val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
            if (query.catalogKey != null) q.bind("catalogKey", query.catalogKey)
            if (query.assetIds != null) q.bindList("assetIds", query.assetIds)
            q.map { rs, _ ->
                AssetRow(
                    id = rs.getString("id"),
                    name = rs.getString("name"),
                    mediaType = rs.getString("media_type"),
                    width = rs.getObject("width") as? Int,
                    height = rs.getObject("height") as? Int,
                    contentHash = rs.getString("content_hash"),
                )
            }.list()
        }

        return rows.map { row ->
            ImageResource(
                slug = row.id,
                name = row.name,
                mediaType = row.mediaType,
                width = row.width,
                height = row.height,
                // No contentUrl: a catalog written at wire v7 files every binary where its hash
                // says, so identical bytes are one file and there is no path convention to get
                // wrong. The digest is already stored -- the asset store is keyed by it.
                contentHash = requireNotNull(row.contentHash) {
                    "asset ${row.id} has no content hash; the content backfill has not run for it"
                },
            )
        }
    }
}
