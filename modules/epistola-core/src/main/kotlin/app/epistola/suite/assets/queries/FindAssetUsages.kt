// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
 * Finds template versions (draft or published) and catalog presentations that
 * reference a given asset.
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
                SELECT DISTINCT usage_name AS template_name, variant_title
                FROM (
                    SELECT dt.name AS usage_name, tv.title AS variant_title
                    FROM template_versions ver
                    JOIN template_variants tv ON tv.tenant_key = ver.tenant_key AND tv.catalog_key = ver.catalog_key
                        AND tv.template_key = ver.template_key AND tv.id = ver.variant_key
                    JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key
                        AND dt.id = tv.template_key
                    CROSS JOIN LATERAL jsonb_each(ver.template_model -> 'nodes') AS n(key, value)
                    WHERE ver.tenant_key = :tenantId
                      AND ver.status IN ('draft', 'published')
                      AND n.value -> 'props' ->> 'assetId' = :assetId

                    UNION ALL

                    SELECT 'Catalog: ' || c.name, NULL
                    FROM catalogs c
                    WHERE c.tenant_key = :tenantId
                      AND (
                          c.portable_metadata #>> '{presentation,iconAssetSlug}' = :assetId
                          OR jsonb_exists(
                              COALESCE(c.portable_metadata #> '{presentation,imageAssetSlugs}', '[]'::jsonb),
                              :assetId
                          )
                      )
                ) usages
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
