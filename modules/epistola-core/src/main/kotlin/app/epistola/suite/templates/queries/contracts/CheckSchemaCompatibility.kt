package app.epistola.suite.templates.queries.contracts

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.validation.SchemaCompatibilityChecker
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Checks whether a new schema is backwards compatible with the latest published contract.
 * Used by the UI to show the breaking changes banner when editing the contract.
 */
data class CheckSchemaCompatibility(
    val templateId: TemplateId,
    val newSchema: ObjectNode?,
) : Query<SchemaCompatibilityChecker.CompatibilityResult>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

@Component
class CheckSchemaCompatibilityHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<CheckSchemaCompatibility, SchemaCompatibilityChecker.CompatibilityResult> {

    private val checker = SchemaCompatibilityChecker()

    override fun handle(query: CheckSchemaCompatibility): SchemaCompatibilityChecker.CompatibilityResult {
        val publishedSchema = jdbi.withHandle<ObjectNode?, Exception> { handle ->
            val raw = handle.createQuery(
                """
                SELECT data_model FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'published'
                ORDER BY id DESC LIMIT 1
                """,
            )
                .bind("tenantKey", query.templateId.tenantKey)
                .bind("catalogKey", query.templateId.catalogKey)
                .bind("templateKey", query.templateId.key)
                .mapTo(String::class.java)
                .findOne()
                .orElse(null)

            raw?.let { objectMapper.readValue(it, ObjectNode::class.java) }
        }

        return checker.checkCompatibility(publishedSchema, query.newSchema)
    }
}
