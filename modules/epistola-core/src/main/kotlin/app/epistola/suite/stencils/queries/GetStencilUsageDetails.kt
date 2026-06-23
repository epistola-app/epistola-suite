package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilUsageDetail
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Finds all template versions that contain any instance of a given stencil (any version).
 * Returns detailed info including version status and instance count per template version.
 * Used by the bulk upgrade UI.
 */
data class GetStencilUsageDetails(
    val stencilId: StencilId,
) : Query<List<StencilUsageDetail>>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = stencilId.tenantKey
}

@Component
class GetStencilUsageDetailsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetStencilUsageDetails, List<StencilUsageDetail>> {
    override fun handle(query: GetStencilUsageDetails): List<StencilUsageDetail> = jdbi.withHandle<List<StencilUsageDetail>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT tv.template_key, tv.catalog_key, c.type as catalog_type, dt.name as template_name,
                       tv.variant_key, tv.id as version_id, tv.status as version_status,
                       COALESCE((node.value -> 'props' ->> 'version')::int, 0) as stencil_version,
                       COUNT(*) as instance_count
                FROM template_versions tv
                JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key AND dt.id = tv.template_key
                JOIN catalogs c ON c.tenant_key = tv.tenant_key AND c.id = tv.catalog_key
                CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS node(key, value)
                WHERE tv.tenant_key = :tenantId
                  AND node.value ->> 'type' = 'stencil'
                  AND node.value -> 'props' ->> 'stencilId' = :stencilId
                GROUP BY tv.template_key, tv.catalog_key, c.type, dt.name, tv.variant_key, tv.id, tv.status,
                         COALESCE((node.value -> 'props' ->> 'version')::int, 0)
                ORDER BY (CASE WHEN c.type = 'AUTHORED' THEN 0 ELSE 1 END), tv.catalog_key, dt.name, tv.variant_key, tv.id DESC
                """,
        )
            .bind("tenantId", query.stencilId.tenantKey)
            .bind("stencilId", query.stencilId.key.value)
            .map { rs, _ ->
                StencilUsageDetail(
                    templateId = TemplateKey.of(rs.getString("template_key")),
                    catalogKey = app.epistola.suite.common.ids.CatalogKey.of(rs.getString("catalog_key")),
                    catalogType = app.epistola.suite.catalog.CatalogType.valueOf(rs.getString("catalog_type")),
                    templateName = rs.getString("template_name"),
                    variantId = VariantKey.of(rs.getString("variant_key")),
                    versionId = VersionKey.of(rs.getInt("version_id")),
                    versionStatus = rs.getString("version_status"),
                    stencilVersion = rs.getInt("stencil_version"),
                    instanceCount = rs.getInt("instance_count"),
                )
            }
            .list()
            .let(::markUpgradable)
    }

    /**
     * Flags exactly one row per variant as the bulk-upgrade target and, for every
     * other row, why it is blocked. The upgrade always lands in the variant's draft
     * (created from the latest published version when none exists), so only one row
     * per variant is actionable: its draft if it has one, otherwise its latest
     * published version. Subscribed catalogs are never upgradable.
     */
    private fun markUpgradable(rows: List<StencilUsageDetail>): List<StencilUsageDetail> {
        val authoredByVariant = rows
            .filter { it.catalogType == app.epistola.suite.catalog.CatalogType.AUTHORED }
            .groupBy { Triple(it.catalogKey, it.templateId, it.variantId) }
        val targetByVariant = authoredByVariant.mapValues { (_, variantRows) ->
            val draft = variantRows.filter { it.versionStatus == "draft" }.maxByOrNull { it.versionId.value }
            val published = variantRows.filter { it.versionStatus == "published" }.maxByOrNull { it.versionId.value }
            (draft ?: published)?.versionId
        }
        return rows.map { row ->
            val key = Triple(row.catalogKey, row.templateId, row.variantId)
            val target = targetByVariant[key]
            val upgradable = target != null && row.versionId == target
            val reason = when {
                upgradable -> null
                row.catalogType != app.epistola.suite.catalog.CatalogType.AUTHORED ->
                    StencilUsageDetail.UpgradeBlockReason.SUBSCRIBED
                authoredByVariant[key]?.any { it.versionStatus == "draft" } == true ->
                    StencilUsageDetail.UpgradeBlockReason.HAS_DRAFT
                else -> StencilUsageDetail.UpgradeBlockReason.SUPERSEDED
            }
            row.copy(upgradable = upgradable, upgradeBlockReason = reason)
        }
    }
}
