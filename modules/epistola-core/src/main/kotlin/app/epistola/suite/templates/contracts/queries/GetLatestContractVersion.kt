package app.epistola.suite.templates.contracts.queries

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.contracts.model.ContractVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Gets the latest contract version for a template (draft preferred, then latest published).
 */
data class GetLatestContractVersion(
    val templateId: TemplateId,
) : Query<ContractVersion?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

@Component
class GetLatestContractVersionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetLatestContractVersion, ContractVersion?> {
    override fun handle(query: GetLatestContractVersion): ContractVersion? = jdbi.withHandle<ContractVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey
                ORDER BY CASE status WHEN 'draft' THEN 0 ELSE 1 END, id DESC
                LIMIT 1
                """,
        )
            .bind("tenantKey", query.templateId.tenantKey)
            .bind("catalogKey", query.templateId.catalogKey)
            .bind("templateKey", query.templateId.key)
            .mapTo<ContractVersion>()
            .findOne()
            .orElse(null)
    }
}
