package app.epistola.suite.templates.queries.contracts

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.analysis.FieldIncompatibility
import app.epistola.suite.templates.analysis.IncompatibilityReason
import app.epistola.suite.templates.analysis.SchemaPathNavigator
import app.epistola.suite.templates.analysis.TemplateCompatibilityResult
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
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
    private val navigator: SchemaPathNavigator,
) : QueryHandler<CheckTemplateVersionCompatibility, TemplateCompatibilityResult> {

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
        val referencedPaths: Set<String> = try {
            objectMapper.readValue<List<String>>(
                pathsJson,
                objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java),
            ).toSet()
        } catch (_: Exception) {
            emptySet()
        }

        if (referencedPaths.isEmpty()) {
            return TemplateCompatibilityResult(compatible = true, incompatibilities = emptyList())
        }

        // Parse old schema from contract
        val oldSchema: ObjectNode? = row["contract_data_model"]?.let { raw ->
            try {
                objectMapper.readValue(raw.toString(), ObjectNode::class.java)
            } catch (_: Exception) {
                null
            }
        }

        val newSchema = query.newSchema

        // Schema removed entirely
        if (oldSchema != null && newSchema == null) {
            return TemplateCompatibilityResult(
                compatible = false,
                incompatibilities = referencedPaths.map {
                    FieldIncompatibility(it, IncompatibilityReason.FIELD_REMOVED, "Schema removed entirely")
                },
            )
        }

        if (newSchema == null) {
            return TemplateCompatibilityResult(compatible = true, incompatibilities = emptyList())
        }

        // Check each referenced path against old and new schemas
        val incompatibilities = mutableListOf<FieldIncompatibility>()

        for (path in referencedPaths) {
            val newField = navigator.resolve(newSchema, path)

            if (!newField.found) {
                incompatibilities.add(FieldIncompatibility(path, IncompatibilityReason.FIELD_REMOVED, "\"$path\" not found in new schema"))
                continue
            }

            if (oldSchema != null) {
                val oldField = navigator.resolve(oldSchema, path)
                if (oldField.found && oldField.type != newField.type) {
                    incompatibilities.add(FieldIncompatibility(path, IncompatibilityReason.TYPE_CHANGED, "\"$path\" type changed from ${oldField.type} to ${newField.type}"))
                }
            }
        }

        return TemplateCompatibilityResult(
            compatible = incompatibilities.isEmpty(),
            incompatibilities = incompatibilities,
        )
    }
}
