package app.epistola.suite.attributes.queries

import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetAttributeDefinition(
    val id: AttributeId,
    val tenantId: TenantId,
) : Query<VariantAttributeDefinition?>

@Component
class GetAttributeDefinitionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetAttributeDefinition, VariantAttributeDefinition?> {
    override fun handle(query: GetAttributeDefinition): VariantAttributeDefinition? = jdbi.withHandle<VariantAttributeDefinition?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_id, display_name, allowed_values, created_at, last_modified
                FROM variant_attribute_definitions
                WHERE id = :id AND tenant_id = :tenantId
                """,
        )
            .bind("id", query.id)
            .bind("tenantId", query.tenantId)
            .mapTo<VariantAttributeDefinition>()
            .findOne()
            .orElse(null)
    }
}
