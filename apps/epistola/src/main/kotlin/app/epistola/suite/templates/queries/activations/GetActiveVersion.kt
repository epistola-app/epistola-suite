package app.epistola.suite.templates.queries.activations

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
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
    val environmentId: Long,
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
                    ver.variant_id,
                    ver.version_number,
                    ver.template_model,
                    ver.status,
                    ver.created_at,
                    ver.published_at,
                    ver.archived_at
                FROM environment_activations ea
                JOIN template_versions ver ON ea.version_id = ver.id
                JOIN environments e ON ea.environment_id = e.id
                JOIN template_variants tv ON ea.variant_id = tv.id
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE ea.environment_id = :environmentId
                  AND ea.variant_id = :variantId
                  AND tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
                  AND e.tenant_id = :tenantId
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
