package app.epistola.suite.templates.queries.contracts

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.ContractVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Gets the latest published contract version for a template.
 * This is the "current" contract that downstream systems should use.
 * Returns null if no published contract exists.
 */
data class GetLatestPublishedContractVersion(
    val templateId: TemplateId,
) : Query<ContractVersion?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

@Component
class GetLatestPublishedContractVersionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetLatestPublishedContractVersion, ContractVersion?> {
    override fun handle(query: GetLatestPublishedContractVersion): ContractVersion? = jdbi.withHandle<ContractVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'published'
                ORDER BY id DESC
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
