package app.epistola.suite.assets.queries

import app.epistola.suite.assets.AssetUsage
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Finds template versions (draft or published) that reference a given asset.
 */
data class FindAssetUsages(
    val tenantId: TenantKey,
    val assetId: AssetKey,
) : Query<List<AssetUsage>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class FindAssetUsagesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindAssetUsages, List<AssetUsage>> {

    override fun handle(query: FindAssetUsages): List<AssetUsage> = jdbi.withHandle<List<AssetUsage>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT DISTINCT dt.name AS template_name, tv.title AS variant_title
                FROM template_versions ver
                JOIN template_variants tv ON tv.tenant_key = ver.tenant_key AND tv.template_key = ver.template_key AND tv.id = ver.variant_key
                JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.id = tv.template_key
                CROSS JOIN LATERAL jsonb_each(ver.template_model -> 'nodes') AS n(key, value)
                WHERE ver.tenant_key = :tenantId
                  AND ver.status IN ('draft', 'published')
                  AND n.value -> 'props' ->> 'assetId' = :assetId
                ORDER BY template_name, variant_title
                """,
        )
            .bind("tenantId", query.tenantId)
            .bind("assetId", query.assetId.value.toString())
            .map { rs, _ ->
                AssetUsage(
                    templateName = rs.getString("template_name"),
                    variantTitle = rs.getString("variant_title"),
                )
            }
            .list()
    }
}
