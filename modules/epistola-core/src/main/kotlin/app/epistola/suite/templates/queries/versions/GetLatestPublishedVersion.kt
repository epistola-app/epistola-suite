package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Gets the latest published version for a variant, regardless of environment activations.
 * Used as a fallback when neither versionId nor environmentId is specified.
 */
data class GetLatestPublishedVersion(
    val variantId: VariantId,
) : Query<TemplateVersion?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class GetLatestPublishedVersionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetLatestPublishedVersion, TemplateVersion?> {
    override fun handle(query: GetLatestPublishedVersion): TemplateVersion? = jdbi.withHandle<TemplateVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT
                    id,
                    tenant_key,
                    variant_key,
                    template_model,
                    status,
                    created_at,
                    published_at,
                    archived_at,
                    rendering_defaults_version,
                    resolved_theme
                FROM template_versions
                WHERE tenant_key = :tenantId
                  AND catalog_key = :catalogKey
                  AND template_key = :templateId
                  AND variant_key = :variantId
                  AND status = 'published'
                ORDER BY id DESC
                LIMIT 1
                """,
        )
            .bind("tenantId", query.variantId.tenantKey)
            .bind("catalogKey", query.variantId.catalogKey)
            .bind("templateId", query.variantId.templateKey)
            .bind("variantId", query.variantId.key)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)
    }
}
