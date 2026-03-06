package app.epistola.suite.assets.queries

import app.epistola.suite.assets.AssetUsage
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Finds template versions (draft or published) that reference a given asset.
 */
data class FindAssetUsages(
    val tenantId: TenantKey,
    val assetId: AssetKey,
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
        fun findAssetUsages(handle: Handle, tenantId: TenantKey, assetId: AssetKey): List<AssetUsage> = handle.createQuery(
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
