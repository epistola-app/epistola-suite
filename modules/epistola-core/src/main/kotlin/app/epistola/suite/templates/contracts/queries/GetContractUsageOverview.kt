package app.epistola.suite.templates.contracts.queries

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Returns an overview of which template versions use which contract version.
 * Used by the data contract tab to show incompatible/outdated versions.
 */
data class GetContractUsageOverview(
    val templateId: TemplateId,
) : Query<ContractUsageOverview>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class ContractUsageOverview(
    val versions: List<TemplateVersionContractInfo>,
)

data class TemplateVersionContractInfo(
    val variantKey: String,
    val versionId: Int,
    val status: String,
    val contractVersion: Int?,
    val activeEnvironments: List<String>,
)

@Component
class GetContractUsageOverviewHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetContractUsageOverview, ContractUsageOverview> {
    override fun handle(query: GetContractUsageOverview): ContractUsageOverview {
        val rows = jdbi.withHandle<List<Map<String, Any?>>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT tv.variant_key, tv.id as version_id, tv.status, tv.contract_version,
                       COALESCE(
                           (SELECT jsonb_agg(ea.environment_key ORDER BY ea.environment_key)
                            FROM environment_activations ea
                            WHERE ea.tenant_key = tv.tenant_key AND ea.catalog_key = tv.catalog_key
                              AND ea.template_key = tv.template_key AND ea.variant_key = tv.variant_key
                              AND ea.version_key = tv.id),
                           '[]'::jsonb
                       )::text as active_environments
                FROM template_versions tv
                WHERE tv.tenant_key = :tenantKey AND tv.catalog_key = :catalogKey
                  AND tv.template_key = :templateKey
                  AND tv.status IN ('published', 'draft')
                ORDER BY tv.variant_key, tv.id DESC
                """,
            )
                .bind("tenantKey", query.templateId.tenantKey)
                .bind("catalogKey", query.templateId.catalogKey)
                .bind("templateKey", query.templateId.key)
                .mapToMap()
                .list()
        }

        val versions = rows.map { row ->
            val envJson = row["active_environments"]?.toString() ?: "[]"
            val envs: List<String> = try {
                objectMapper.readValue(envJson, objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java))
            } catch (_: Exception) {
                emptyList()
            }
            TemplateVersionContractInfo(
                variantKey = row["variant_key"] as String,
                versionId = row["version_id"] as Int,
                status = row["status"] as String,
                contractVersion = row["contract_version"] as? Int,
                activeEnvironments = envs,
            )
        }

        return ContractUsageOverview(versions = versions)
    }
}
