// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.queries

import app.epistola.suite.assets.AssetUsage
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.inNextVersion
import app.epistola.suite.catalog.inVersionOrNext
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.addressedVersion
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Finds the template variants of each catalog's next version, and the catalog presentations, that
 * reference a given asset.
 */
data class FindAssetUsages(
    val tenantId: TenantKey,
    val assetId: AssetKey,
    /** The version whose uses are looked for in its own catalog; every other catalog's next version. Null: every catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : CatalogReleaseScoped,
    Query<List<AssetUsage>>,
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
                    FROM version_template_variants tv
                    JOIN version_templates dt ON dt.tenant_key = tv.tenant_key
                        AND dt.version_id = tv.version_id
                        AND dt.resource_id = tv.template_resource_id
                    CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
                    WHERE tv.tenant_key = :tenantId AND ${inVersionOrNext("tv", "version")}
                      AND n.value -> 'props' ->> 'imageKey' = :assetId

                    UNION ALL

                    SELECT 'Catalog: ' || c.name, NULL
                    FROM catalogs c
                    WHERE c.tenant_key = :tenantId
                      AND (
                          c.catalog_metadata #>> '{presentation,iconImageKey}' = :assetId
                          OR jsonb_exists(
                              COALESCE(c.catalog_metadata #> '{presentation,imageKeys}', '[]'::jsonb),
                              :assetId
                          )
                      )
                ) usages
                ORDER BY template_name, variant_title
                """,
        )
            .bind("tenantId", query.tenantId)
            .bindVersion("version", handle.addressedVersion(query))
            .bind("assetId", query.assetId.value)
            .map { rs, _ ->
                AssetUsage(
                    templateName = rs.getString("template_name"),
                    variantTitle = rs.getString("variant_title"),
                )
            }
            .list()
    }
}
