package app.epistola.suite.templates.queries.activations

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
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
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
) : Query<List<ActivationDetails>>

@Component
class ListActivationsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListActivations, List<ActivationDetails>> {
    override fun handle(query: ListActivations): List<ActivationDetails> = jdbi.withHandle<List<ActivationDetails>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT
                    ea.environment_key,
                    e.name as environment_name,
                    ea.version_key,
                    ea.activated_at
                FROM environment_activations ea
                JOIN environments e ON e.tenant_key = ea.tenant_key AND e.id = ea.environment_key
                JOIN template_variants tv ON tv.tenant_key = ea.tenant_key AND tv.id = ea.variant_key
                WHERE ea.variant_key = :variantId
                  AND ea.tenant_key = :tenantId
                  AND tv.template_key = :templateId
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
