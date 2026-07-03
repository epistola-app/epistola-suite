package app.epistola.suite.templates.contracts.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.analysis.TemplateCompatibilityResult
import app.epistola.suite.templates.contracts.TemplateVersionCompatibilityEvaluator
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Checks whether a specific template version is compatible with a new contract schema.
 *
 * Loads the version's `referenced_paths` and its current contract's `data_model` from
 * the database, then compares each referenced path against the new schema.
 *
 * Reports FIELD_REMOVED (path not found in new schema) and TYPE_CHANGED (path exists
 * but type differs) incompatibilities.
 */
data class CheckTemplateVersionCompatibility(
    val versionId: VersionId,
    val newSchema: ObjectNode?,
) : Query<TemplateCompatibilityResult>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class CheckTemplateVersionCompatibilityHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<CheckTemplateVersionCompatibility, TemplateCompatibilityResult> {

    private val evaluator = TemplateVersionCompatibilityEvaluator()

    override fun handle(query: CheckTemplateVersionCompatibility): TemplateCompatibilityResult {
        // Load the version's referenced_paths and its contract's data_model in one query
        val row = jdbi.withHandle<Map<String, Any?>?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT tv.referenced_paths, cv.data_model as contract_data_model
                FROM template_versions tv
                LEFT JOIN contract_versions cv
                    ON cv.tenant_key = tv.tenant_key AND cv.catalog_key = tv.catalog_key
                       AND cv.template_key = tv.template_key AND cv.id = tv.contract_version
                WHERE tv.tenant_key = :tenantKey AND tv.catalog_key = :catalogKey
                  AND tv.template_key = :templateKey AND tv.variant_key = :variantKey
                  AND tv.id = :versionId
                """,
            )
                .bind("tenantKey", query.versionId.tenantKey)
                .bind("catalogKey", query.versionId.catalogKey)
                .bind("templateKey", query.versionId.templateKey)
                .bind("variantKey", query.versionId.variantKey)
                .bind("versionId", query.versionId.key.value)
                .mapToMap()
                .findOne()
                .orElse(null)
        } ?: return TemplateCompatibilityResult(compatible = true, incompatibilities = emptyList())

        // Parse referenced paths
        val pathsJson = row["referenced_paths"]?.toString() ?: "[]"
        val referencedPaths: Set<String> = objectMapper
            .readStringArrayColumn(pathsJson, "template_versions.referenced_paths for ${query.versionId}")
            .toSet()

        // Parse old schema from contract. A corrupt data_model must fail the check:
        // treating it as "no old schema" silently skips all TYPE_CHANGED detection.
        val oldSchema: ObjectNode? = row["contract_data_model"]?.let { raw ->
            try {
                objectMapper.readValue(raw.toString(), ObjectNode::class.java)
            } catch (e: JacksonException) {
                throw IllegalStateException(
                    "Corrupt database value: contract_versions.data_model for ${query.versionId} is not a JSON object",
                    e,
                )
            }
        }

        return evaluator.evaluate(referencedPaths, oldSchema, query.newSchema)
    }
}
