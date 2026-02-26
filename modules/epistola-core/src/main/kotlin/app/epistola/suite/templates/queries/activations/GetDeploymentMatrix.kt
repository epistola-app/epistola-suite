package app.epistola.suite.templates.queries.activations

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Returns the deployment matrix for a template: all active environment activations
 * across all variants, joined with variant and environment info.
 */
data class GetDeploymentMatrix(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
) : Query<List<DeploymentMatrixCell>>

data class DeploymentMatrixCell(
    val variantId: VariantKey,
    val environmentId: EnvironmentKey,
    val versionId: VersionKey,
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
                    ea.variant_id,
                    ea.environment_id,
                    ea.version_id,
                    ea.activated_at
                FROM environment_activations ea
                JOIN template_variants tv ON tv.tenant_id = ea.tenant_id AND tv.id = ea.variant_id
                WHERE tv.template_id = :templateId
                  AND ea.tenant_id = :tenantId
                ORDER BY tv.created_at ASC, ea.environment_id ASC
                """,
        )
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<DeploymentMatrixCell>()
            .list()
    }
}
