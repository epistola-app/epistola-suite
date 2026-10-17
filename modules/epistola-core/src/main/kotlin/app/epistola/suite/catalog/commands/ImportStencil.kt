// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import app.epistola.suite.validation.FieldLimits.MAX_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Installs one stencil carried in a catalog archive as the stencil's working copy.
 *
 * A stencil has no versions (contract ADR 0002, ADR 0006): importing replaces its content,
 * parameter schema and whether it reads the template's data. An import whose content and schema
 * equal what is stored is `SKIPPED`; otherwise the stencil is `INSTALLED` or `UPDATED`.
 */
data class ImportStencil(
    val tenantId: TenantId,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val slug: String,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val content: TemplateDocument,
    val parameterSchema: Map<String, Any?>? = null,
    val readsTemplateData: Boolean = false,
) : Command<InstallStatus>,
    RequiresPermission {
    override val permission get() = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        // Column ceiling (#692): stencils.name is VARCHAR(255).
        validate("name", name.length <= MAX_NAME_COLUMN_LENGTH) { "Name must be $MAX_NAME_COLUMN_LENGTH characters or less" }
    }
}

@Component
class ImportStencilHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val templateDocumentValidator: TemplateDocumentValidator,
) : CommandHandler<ImportStencil, InstallStatus> {

    override fun handle(command: ImportStencil): InstallStatus {
        templateDocumentValidator.validateStencil(command.content, command.slug, command.catalogKey.value)
        // Wire content is relative to the catalog it lands in, so qualify against the target. The
        // same JSON backs the comparison and the write, so a re-import still compares equal.
        val contentJson = objectMapper.valueToTree<JsonNode>(command.content)
            .also { ResourceReferenceSites.qualifyRelative(it, command.catalogKey.value) }
            .let(objectMapper::writeValueAsString)
        val parameterSchemaJson = command.parameterSchema?.let { objectMapper.writeValueAsString(it) }
        val auditUser = currentUserIdOrNull()?.value

        return jdbi.inTransaction<InstallStatus, Exception> { handle ->
            // Compared by value: Postgres normalises JSONB whitespace and key order.
            val unchanged = handle.createQuery(
                """
                SELECT content = :content::jsonb
                       AND parameter_schema IS NOT DISTINCT FROM :parameterSchema::jsonb
                       AND reads_template_data = :readsTemplateData
                FROM stencils
                WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")} AND id = :id
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("id", StencilKey.of(command.slug))
                .bind("content", contentJson)
                .bind("parameterSchema", parameterSchemaJson)
                .bind("readsTemplateData", command.readsTemplateData)
                .mapTo(Boolean::class.java)
                .findOne()
                .orElse(null)

            handle.createUpdate(
                """
                INSERT INTO stencils (id, tenant_key, catalog_id, version_id, name, description, tags, content, parameter_schema, reads_template_data,
                                      created_at, updated_at, created_by, updated_by)
                VALUES (:id, :tenantKey, ${catalogAt("tenantKey", "catalogKey")}, ${versionAt("tenantKey", "catalogKey")}, :name, :description, :tags::jsonb, :content::jsonb,
                        :parameterSchema::jsonb, :readsTemplateData, NOW(), NOW(), :createdBy, :updatedBy)
                ON CONFLICT (tenant_key, version_id, id) DO UPDATE
                SET name = :name, description = :description, tags = :tags::jsonb, content = :content::jsonb,
                    parameter_schema = :parameterSchema::jsonb, reads_template_data = :readsTemplateData,
                    updated_at = NOW(), updated_by = :updatedBy
                """,
            )
                .bind("id", StencilKey.of(command.slug))
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("name", command.name)
                .bind("description", command.description)
                .bind("tags", objectMapper.writeValueAsString(command.tags))
                .bind("content", contentJson)
                .bind("parameterSchema", parameterSchemaJson)
                .bind("readsTemplateData", command.readsTemplateData)
                .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                .execute()

            when (unchanged) {
                null -> InstallStatus.INSTALLED
                true -> InstallStatus.SKIPPED
                false -> InstallStatus.UPDATED
            }
        }
    }
}
