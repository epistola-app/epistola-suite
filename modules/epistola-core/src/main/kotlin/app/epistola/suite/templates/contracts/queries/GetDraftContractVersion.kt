// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.contracts.queries

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.REQUESTED_TEMPLATE_ADDRESS
import app.epistola.suite.templates.contracts.model.ContractVersion
import app.epistola.suite.templates.templateAtAddress
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetDraftContractVersion(
    val templateId: TemplateId,
) : Query<ContractVersion?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

@Component
class GetDraftContractVersionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDraftContractVersion, ContractVersion?> {
    override fun handle(query: GetDraftContractVersion): ContractVersion? = jdbi.withHandle<ContractVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_key, $REQUESTED_TEMPLATE_ADDRESS, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey
                  AND template_resource_id = ${templateAtAddress("tenantKey", "catalogKey", "templateKey")} AND status = 'draft'
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
