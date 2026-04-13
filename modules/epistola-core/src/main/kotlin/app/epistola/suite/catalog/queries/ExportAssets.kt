package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.protocol.AssetResource
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class ExportAssets(
    override val tenantKey: TenantKey,
    val names: List<String>? = null,
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
                if (query.names != null) append(" AND name IN (<names>)")
            }
            val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
            if (query.names != null) q.bindList("names", query.names)
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
            AssetResource(
                slug = row.name,
                name = row.name,
                mediaType = row.mediaType,
                width = row.width,
                height = row.height,
                contentUrl = "./resources/assets/${row.id}",
            )
        }
    }
}
