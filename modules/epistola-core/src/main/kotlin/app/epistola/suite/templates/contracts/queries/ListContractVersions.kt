package app.epistola.suite.templates.contracts.queries

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.contracts.model.ContractVersionSummary
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Lists all contract versions for a template, ordered by version number descending.
 */
data class ListContractVersions(
    val templateId: TemplateId,
) : Query<List<ContractVersionSummary>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

@Component
class ListContractVersionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListContractVersions, List<ContractVersionSummary>> {
    override fun handle(query: ListContractVersions): List<ContractVersionSummary> = jdbi.withHandle<List<ContractVersionSummary>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, status, created_at, published_at
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey
                ORDER BY id DESC
                """,
        )
            .bind("tenantKey", query.templateId.tenantKey)
            .bind("catalogKey", query.templateId.catalogKey)
            .bind("templateKey", query.templateId.key)
            .mapTo<ContractVersionSummary>()
            .list()
    }
}
