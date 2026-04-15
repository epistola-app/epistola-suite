package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.protocol.AssetResource
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

private fun mimeTypeToExtension(mimeType: String): String = when (mimeType) {
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
) : Query<List<AssetResource>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ExportAssetsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ExportAssets, List<AssetResource>> {

    override fun handle(query: ExportAssets): List<AssetResource> {
        data class AssetRow(
            val id: String,
            val name: String,
            val mediaType: String,
            val width: Int?,
            val height: Int?,
        )

        val rows = jdbi.withHandle<List<AssetRow>, Exception> { handle ->
            val sql = buildString {
                append("SELECT id::text, name, media_type, width, height FROM assets WHERE tenant_key = :tenantKey")
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
                )
            }.list()
        }

        return rows.map { row ->
            val ext = mimeTypeToExtension(row.mediaType)
            AssetResource(
                slug = row.id,
                name = row.name,
                mediaType = row.mediaType,
                width = row.width,
                height = row.height,
                contentUrl = "./resources/assets/${row.id}$ext",
            )
        }
    }
}
