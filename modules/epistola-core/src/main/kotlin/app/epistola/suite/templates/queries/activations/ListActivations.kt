package app.epistola.suite.templates.queries.activations

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.ActivationDetails
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Lists all activations for a variant across all environments.
 */
data class ListActivations(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
) : Query<List<ActivationDetails>>

@Component
class ListActivationsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListActivations, List<ActivationDetails>> {
    override fun handle(query: ListActivations): List<ActivationDetails> = jdbi.withHandle<List<ActivationDetails>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT
                    ea.environment_id,
                    e.name as environment_name,
                    ea.version_id,
                    ea.activated_at
                FROM environment_activations ea
                JOIN environments e ON ea.environment_id = e.id
                JOIN template_versions ver ON ea.variant_id = ver.variant_id AND ea.version_id = ver.id
                JOIN template_variants tv ON ea.variant_id = tv.id
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE ea.variant_id = :variantId
                  AND tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
                ORDER BY e.name ASC
                """,
        )
            .bind("variantId", query.variantId)
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<ActivationDetails>()
            .list()
    }
}
