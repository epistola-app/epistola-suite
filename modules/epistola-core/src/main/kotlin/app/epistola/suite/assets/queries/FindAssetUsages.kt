package app.epistola.suite.assets.queries

import app.epistola.suite.assets.AssetUsage
import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Finds template versions (draft or published) that reference a given asset.
 */
data class FindAssetUsages(
    val tenantId: TenantId,
    val assetId: AssetId,
) : Query<List<AssetUsage>>

@Component
class FindAssetUsagesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindAssetUsages, List<AssetUsage>> {

    override fun handle(query: FindAssetUsages): List<AssetUsage> = jdbi.withHandle<List<AssetUsage>, Exception> { handle ->
        findAssetUsages(handle, query.tenantId, query.assetId)
    }

    companion object {
        /**
         * Reusable function for checking asset usages inside an existing transaction/handle.
         */
        fun findAssetUsages(handle: Handle, tenantId: TenantId, assetId: AssetId): List<AssetUsage> = handle.createQuery(
            """
                SELECT DISTINCT dt.name AS template_name, tv.title AS variant_title
                FROM template_versions ver
                JOIN template_variants tv ON tv.tenant_id = ver.tenant_id AND tv.id = ver.variant_id
                JOIN document_templates dt ON dt.tenant_id = tv.tenant_id AND dt.id = tv.template_id
                CROSS JOIN LATERAL jsonb_each(ver.template_model -> 'nodes') AS n(key, value)
                WHERE ver.tenant_id = :tenantId
                  AND ver.status IN ('draft', 'published')
                  AND n.value -> 'props' ->> 'assetId' = :assetId
                ORDER BY template_name, variant_title
                """,
        )
            .bind("tenantId", tenantId)
            .bind("assetId", assetId.value.toString())
            .map { rs, _ ->
                AssetUsage(
                    templateName = rs.getString("template_name"),
                    variantTitle = rs.getString("variant_title"),
                )
            }
            .list()
    }
}
