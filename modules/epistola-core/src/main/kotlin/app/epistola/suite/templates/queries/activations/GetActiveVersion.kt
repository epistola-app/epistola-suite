package app.epistola.suite.templates.queries.activations

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
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
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    val environmentId: EnvironmentKey,
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
                    ver.tenant_key,
                    ver.variant_key,
                    ver.template_model,
                    ver.status,
                    ver.created_at,
                    ver.published_at,
                    ver.archived_at
                FROM environment_activations ea
                JOIN template_versions ver ON ver.tenant_key = ea.tenant_key AND ver.variant_key = ea.variant_key AND ver.id = ea.version_key
                JOIN template_variants tv ON tv.tenant_key = ea.tenant_key AND tv.id = ea.variant_key
                WHERE ea.environment_key = :environmentId
                  AND ea.variant_key = :variantId
                  AND ea.tenant_key = :tenantId
                  AND tv.template_key = :templateId
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
