package app.epistola.suite.attributes.queries

import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListAttributeDefinitions(
    val tenantId: TenantId,
    val catalogKey: CatalogKey? = null,
) : Query<List<VariantAttributeDefinition>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class ListAttributeDefinitionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListAttributeDefinitions, List<VariantAttributeDefinition>> {
    override fun handle(query: ListAttributeDefinitions): List<VariantAttributeDefinition> = jdbi.withHandle<List<VariantAttributeDefinition>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT id, tenant_key, catalog_key, display_name, allowed_values, created_at, last_modified
                FROM variant_attribute_definitions
                WHERE tenant_key = :tenantId
                """,
            )
            if (query.catalogKey != null) {
                append(" AND catalog_key = :catalogKey")
            }
            append(" ORDER BY display_name ASC")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bind("catalogKey", query.catalogKey)
        }
        jdbiQuery
            .mapTo<VariantAttributeDefinition>()
            .list()
    }
}
