package app.epistola.suite.templates.queries.activations

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Gets the active version for a variant in a specific environment.
 */
data class GetActiveVersion(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val environmentId: EnvironmentId,
) : Query<TemplateVersion?>

@Component
class GetActiveVersionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetActiveVersion, TemplateVersion?> {
    override fun handle(query: GetActiveVersion): TemplateVersion? = jdbi.withHandle<TemplateVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT
                    ver.id,
                    ver.tenant_id,
                    ver.variant_id,
                    ver.template_model,
                    ver.status,
                    ver.created_at,
                    ver.published_at,
                    ver.archived_at
                FROM environment_activations ea
                JOIN template_versions ver ON ver.tenant_id = ea.tenant_id AND ver.variant_id = ea.variant_id AND ver.id = ea.version_id
                JOIN template_variants tv ON tv.tenant_id = ea.tenant_id AND tv.id = ea.variant_id
                WHERE ea.environment_id = :environmentId
                  AND ea.variant_id = :variantId
                  AND ea.tenant_id = :tenantId
                  AND tv.template_id = :templateId
                """,
        )
            .bind("environmentId", query.environmentId)
            .bind("variantId", query.variantId)
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)
    }
}
