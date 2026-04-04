package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilUsage
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Finds all templates that contain an embedded copy of a specific stencil version.
 * Searches the template_model JSONB for nodes with matching stencilRef.
 */
data class GetStencilUsage(
    val versionId: StencilVersionId,
) : Query<List<StencilUsage>>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class GetStencilUsageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetStencilUsage, List<StencilUsage>> {
    override fun handle(query: GetStencilUsage): List<StencilUsage> = jdbi.withHandle<List<StencilUsage>, Exception> { handle ->
        // Search for template versions that contain stencil component nodes matching this stencil.
        // A stencil node has type "stencil" with stencilId and version in props.
        // Uses the GIN index on template_model for efficient JSONB traversal.
        handle.createQuery(
            """
            SELECT tv.template_key, dt.name as template_name, tv.variant_key, tv.id as version_id,
                   (node.value -> 'props' ->> 'version')::int as stencil_version
            FROM template_versions tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.id = tv.template_key
            CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS node(key, value)
            WHERE tv.tenant_key = :tenantId
              AND node.value ->> 'type' = 'stencil'
              AND node.value -> 'props' ->> 'stencilId' = :stencilId
            ORDER BY dt.name, tv.variant_key, tv.id
            """,
        )
            .bind("tenantId", query.versionId.tenantKey)
            .bind("stencilId", query.versionId.stencilKey.value)
            .map { rs, _ ->
                StencilUsage(
                    templateId = TemplateKey.of(rs.getString("template_key")),
                    templateName = rs.getString("template_name"),
                    variantId = VariantKey.of(rs.getString("variant_key")),
                    versionId = VersionKey.of(rs.getInt("version_id")),
                    stencilVersion = rs.getInt("stencil_version"),
                )
            }
            .list()
    }
}
