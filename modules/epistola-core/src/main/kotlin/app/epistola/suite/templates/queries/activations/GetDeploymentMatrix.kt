// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries.activations

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.templateAtAddress
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Returns the deployment matrix for a template: all active environment activations
 * across all variants, joined with variant and environment info.
 */
data class GetDeploymentMatrix(
    val templateId: TemplateId,
) : Query<List<DeploymentMatrixCell>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class DeploymentMatrixCell(
    val variantKey: VariantKey,
    val environmentKey: EnvironmentKey,
    val versionKey: VersionKey,
    val activatedAt: OffsetDateTime,
)

@Component
class GetDeploymentMatrixHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDeploymentMatrix, List<DeploymentMatrixCell>> {
    override fun handle(query: GetDeploymentMatrix): List<DeploymentMatrixCell> = jdbi.withHandle<List<DeploymentMatrixCell>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT
                    ea.variant_key,
                    ea.environment_key,
                    ea.version_key,
                    ea.activated_at
                FROM environment_activations ea
                JOIN template_variants tv ON tv.tenant_key = ea.tenant_key AND tv.template_resource_id = ea.template_resource_id AND tv.id = ea.variant_key
                WHERE ea.tenant_key = :tenantId
                  AND ea.template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
                ORDER BY tv.created_at ASC, ea.environment_key ASC
                """,
        )
            .bind("templateId", query.templateId.key)
            .bind("tenantId", query.templateId.tenantKey)
            .bind("catalogKey", query.templateId.catalogKey)
            .mapTo<DeploymentMatrixCell>()
            .list()
    }
}
