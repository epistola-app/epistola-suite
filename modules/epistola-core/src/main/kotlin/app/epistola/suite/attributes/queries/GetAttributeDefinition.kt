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
                SELECT a.id, a.tenant_key, a.catalog_key, c.type AS catalog_type,
                       a.display_name, a.allowed_values,
                       a.code_list_catalog_key, a.code_list_slug,
                       a.created_at, a.updated_at
                FROM variant_attribute_definitions a
                JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.id = a.catalog_key
                WHERE a.id = :id AND a.tenant_key = :tenantId AND a.catalog_key = :catalogKey
                """,
        )
            .bind("id", query.id.key)
            .bind("tenantId", query.id.tenantKey)
            .bind("catalogKey", query.id.catalogKey)
            .mapTo<VariantAttributeDefinition>()
            .findOne()
            .orElse(null)
    }
}
