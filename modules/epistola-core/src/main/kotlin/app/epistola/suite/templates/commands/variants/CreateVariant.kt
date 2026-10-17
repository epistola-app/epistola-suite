// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.createDefaultTemplateModel
import app.epistola.suite.templates.services.TemplateDocumentPreparation
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class CreateVariant(
    val id: VariantId,
    val title: String,
    val description: String?,
    val attributes: Map<String, String> = emptyMap(),
) : Command<TemplateVariant?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        validate("title", title.isNotBlank()) { "Title is required" }
        validate("title", title.length <= MAX_NAME_LENGTH) { "Title must be $MAX_NAME_LENGTH characters or less" }
    }
}

@Component
class CreateVariantHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val templateDocumentPreparation: TemplateDocumentPreparation,
) : CommandHandler<CreateVariant, TemplateVariant?> {
    override fun handle(command: CreateVariant): TemplateVariant? {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)

        val auditUser = currentUserIdOrNull()?.value

        // Validate attributes against the tenant's attribute definitions
        validateAttributes(command.id.tenantId, command.attributes)

        return executeOrThrowDuplicate("variant", command.id.key.value) {
            jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
                // Verify the template belongs to the tenant.
                val templateExists = handle.createQuery(
                    """
                SELECT 1
                FROM document_templates
                WHERE id = :templateId AND tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")}
                """,
                )
                    .bind("templateId", command.id.templateKey)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .mapTo(Int::class.java)
                    .findOne()
                    .isPresent
                if (!templateExists) return@inTransaction null

                val attributesJson = objectMapper.writeValueAsString(command.attributes)
                requireDistinctAttributeSet(handle, command.id, command.attributes, attributesJson)

                // Auto-default: first variant for a template becomes the default
                val existingCount = handle.createQuery(
                    """
                SELECT COUNT(*) FROM template_variants
                WHERE tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")} AND template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
                """,
                )
                    .bind("tenantId", command.id.tenantKey)
                    .bind("templateId", command.id.templateKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .mapTo<Long>()
                    .one()
                val isDefault = existingCount == 0L

                // A new variant starts with the empty document.
                val prepared = templateDocumentPreparation.prepare(createDefaultTemplateModel(command.id.key), command.id.catalogKey)
                val variant = handle.createQuery(
                    """
                INSERT INTO template_variants (id, tenant_key, version_id, template_resource_id, title, description, attributes, is_default, template_model, referenced_paths,
                                               created_at, updated_at, created_by, updated_by)
                VALUES (:id, :tenantId, ${versionAt("tenantId", "catalogKey")}, ${templateAtAddress("tenantId", "catalogKey", "templateId")}, :title, :description, :attributes::jsonb, :isDefault,
                        :templateModel::jsonb, :referencedPaths::jsonb, NOW(), NOW(), :createdBy, :updatedBy)
                RETURNING *, CAST(:templateId AS TEXT) AS template_key
                """,
                )
                    .bind("id", command.id.key)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("templateId", command.id.templateKey)
                    .bind("title", command.title)
                    .bind("description", command.description)
                    .bind("attributes", attributesJson)
                    .bind("isDefault", isDefault)
                    .bind("templateModel", prepared.templateModelJson)
                    .bind("referencedPaths", prepared.referencedPathsJson)
                    .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                    .mapTo<TemplateVariant>()
                    .one()

                variant
            }
        }
    }
}
