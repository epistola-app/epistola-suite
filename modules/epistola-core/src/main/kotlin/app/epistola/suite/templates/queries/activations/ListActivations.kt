package app.epistola.suite.templates.queries.activations

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.ActivationDetails
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Lists all activations for a variant across all environments.
 */
data class ListActivations(
    val variantId: VariantId,
) : Query<List<ActivationDetails>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

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
                JOIN template_variants tv ON tv.tenant_key = ea.tenant_key AND tv.catalog_key = ea.catalog_key AND tv.template_key = ea.template_key AND tv.id = ea.variant_key
                WHERE ea.variant_key = :variantId
                  AND ea.tenant_key = :tenantId
                  AND ea.catalog_key = :catalogKey
                  AND ea.template_key = :templateId
                ORDER BY e.name ASC
                """,
        )
            .bind("variantId", query.variantId.key)
            .bind("templateId", query.variantId.templateKey)
            .bind("tenantId", query.variantId.tenantKey)
            .bind("catalogKey", query.variantId.catalogKey)
            .mapTo<ActivationDetails>()
            .list()
    }
}
