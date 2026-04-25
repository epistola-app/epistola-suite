package app.epistola.suite.templates.queries.contracts

import app.epistola.suite.common.ids.ContractVersionId
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
 * Gets a specific contract version by template + version number.
 */
data class GetContractVersion(
    val id: ContractVersionId,
) : Query<ContractVersion?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class GetContractVersionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetContractVersion, ContractVersion?> {
    override fun handle(query: GetContractVersion): ContractVersion? = jdbi.withHandle<ContractVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND id = :versionId
                """,
        )
            .bind("tenantKey", query.id.tenantKey)
            .bind("catalogKey", query.id.catalogKey)
            .bind("templateKey", query.id.templateKey)
            .bind("versionId", query.id.key.value)
            .mapTo<ContractVersion>()
            .findOne()
            .orElse(null)
    }
}
