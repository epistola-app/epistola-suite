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
                JOIN environments e ON e.tenant_id = ea.tenant_id AND e.id = ea.environment_id
                JOIN template_variants tv ON tv.tenant_id = ea.tenant_id AND tv.id = ea.variant_id
                WHERE ea.variant_id = :variantId
                  AND ea.tenant_id = :tenantId
                  AND tv.template_id = :templateId
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
