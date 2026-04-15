package app.epistola.suite.templates.queries.activations

import app.epistola.suite.common.ids.EnvironmentId
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
 * Gets the active version for a variant in a specific environment.
 */
data class GetActiveVersion(
    val variantId: VariantId,
    val environmentId: EnvironmentId,
) : Query<TemplateVersion?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class GetActiveVersionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetActiveVersion, TemplateVersion?> {
    override fun handle(query: GetActiveVersion): TemplateVersion? = jdbi.withHandle<TemplateVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT
                    ver.id,
                    ver.tenant_key,
                    ver.variant_key,
                    ver.template_model,
                    ver.status,
                    ver.created_at,
                    ver.published_at,
                    ver.archived_at,
                    ver.rendering_defaults_version,
                    ver.resolved_theme
                FROM environment_activations ea
                JOIN template_versions ver ON ver.tenant_key = ea.tenant_key AND ver.catalog_key = ea.catalog_key AND ver.template_key = ea.template_key AND ver.variant_key = ea.variant_key AND ver.id = ea.version_key
                JOIN template_variants tv ON tv.tenant_key = ea.tenant_key AND tv.catalog_key = ea.catalog_key AND tv.template_key = ea.template_key AND tv.id = ea.variant_key
                WHERE ea.environment_key = :environmentId
                  AND ea.variant_key = :variantId
                  AND ea.tenant_key = :tenantId
                  AND ea.template_key = :templateId
                  AND ea.catalog_key = :catalogKey
                """,
        )
            .bind("environmentId", query.environmentId.key)
            .bind("variantId", query.variantId.key)
            .bind("templateId", query.variantId.templateKey)
            .bind("tenantId", query.variantId.tenantKey)
            .bind("catalogKey", query.variantId.catalogKey)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)
    }
}
