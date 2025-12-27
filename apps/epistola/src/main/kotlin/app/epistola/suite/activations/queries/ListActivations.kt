package app.epistola.suite.activations.queries

import app.epistola.suite.activations.ActivationDetails
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Lists all activations for a variant across all environments.
 */
data class ListActivations(
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
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
                    ver.version_number,
                    ea.activated_at
                FROM environment_activations ea
                JOIN environments e ON ea.environment_id = e.id
                JOIN template_versions ver ON ea.version_id = ver.id
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
