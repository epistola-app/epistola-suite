package app.epistola.suite.attributes.queries

import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetAttributeDefinition(
    val id: AttributeId,
) : Query<VariantAttributeDefinition?>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = id.tenantKey
}

@Component
class GetAttributeDefinitionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetAttributeDefinition, VariantAttributeDefinition?> {
    override fun handle(query: GetAttributeDefinition): VariantAttributeDefinition? = jdbi.withHandle<VariantAttributeDefinition?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_key, display_name, allowed_values, created_at, last_modified
                FROM variant_attribute_definitions
                WHERE id = :id AND tenant_key = :tenantId
                """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .mapTo<VariantAttributeDefinition>()
            .findOne()
            .orElse(null)
    }
}
