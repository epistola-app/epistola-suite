package app.epistola.suite.attributes.queries

import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListAttributeDefinitions(
    val tenantId: TenantId,
) : Query<List<VariantAttributeDefinition>>

@Component
class ListAttributeDefinitionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListAttributeDefinitions, List<VariantAttributeDefinition>> {
    override fun handle(query: ListAttributeDefinitions): List<VariantAttributeDefinition> = jdbi.withHandle<List<VariantAttributeDefinition>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_id, display_name, allowed_values, created_at, last_modified
                FROM variant_attribute_definitions
                WHERE tenant_id = :tenantId
                ORDER BY display_name ASC
                """,
        )
            .bind("tenantId", query.tenantId)
            .mapTo<VariantAttributeDefinition>()
            .list()
    }
}
