// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.identity.requireAddressAvailable
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.createDefaultTemplateModel
import app.epistola.suite.templates.services.TemplateDocumentPreparation
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Title given to the default variant a new template is created with. Mirrors the title the
 * bundled catalogs give theirs, so a template authored in the UI and one imported from a
 * catalog read the same.
 */
const val DEFAULT_VARIANT_TITLE = "Default"

data class CreateDocumentTemplate(
    val id: TemplateId,
    val name: String,
) : Command<DocumentTemplate>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
    }
}

@Component
class CreateDocumentTemplateHandler(
    private val jdbi: Jdbi,
    private val templateDocumentPreparation: TemplateDocumentPreparation,
) : CommandHandler<CreateDocumentTemplate, DocumentTemplate> {
    override fun handle(command: CreateDocumentTemplate): DocumentTemplate {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)

        val auditUser = currentUserIdOrNull()?.value

        return executeOrThrowDuplicate("template", command.id.key.value) {
            jdbi.inTransaction<DocumentTemplate, Exception> { handle ->
                requireAddressAvailable(
                    handle,
                    command.id.tenantKey,
                    ResourceAddress(CatalogResourceType.TEMPLATE, command.id.catalogKey.value, command.id.key.value),
                )
                // 1. Create the template
                val template = handle.createQuery(
                    """
                INSERT INTO document_templates (id, tenant_key, catalog_key, name, theme_key, pdfa_enabled, created_at, updated_at, created_by, updated_by)
                VALUES (:id, :tenantId, :catalogKey, :name, NULL, TRUE, NOW(), NOW(), :createdBy, :updatedBy)
                RETURNING id, tenant_key, catalog_key, name, theme_key, pdfa_enabled, created_at, updated_at, created_by, updated_by
                """,
                )
                    .bind("id", command.id.key)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("name", command.name)
                    .bind("createdBy", auditUser)
                    .bind("updatedBy", auditUser)
                    .mapTo<DocumentTemplate>()
                    .one()

                // 2. Create the default variant with the fixed VariantKey.INITIAL id — a
                // role-neutral provenance slug, not the mutable is_default role. See its KDoc.
                // Title is required (#631). A variant title discriminates between variants of
                // one template ("Default"/"US English"/"Dutch"), so it is deliberately not the
                // template's name: that is constant across every variant and would distinguish
                // nothing, duplicate the parent in the UI, and go stale on rename. Matches the
                // title the bundled catalogs give their own default variants.
                val variantId = VariantKey.INITIAL
                handle.createUpdate(
                    """
                INSERT INTO template_variants (id, tenant_key, catalog_key, template_key, title, attributes, is_default, created_at, updated_at, created_by, updated_by)
                VALUES (:id, :tenantId, :catalogKey, :templateId, :title, '{}'::jsonb, TRUE, NOW(), NOW(), :createdBy, :updatedBy)
                """,
                )
                    .bind("id", variantId)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("templateId", template.id)
                    .bind("title", DEFAULT_VARIANT_TITLE)
                    .bind("createdBy", auditUser)
                    .bind("updatedBy", auditUser)
                    .execute()

                // 3. Create empty draft contract version (v1)
                val contractVersionId = VersionKey.of(1)
                handle.createUpdate(
                    """
                INSERT INTO contract_versions (id, tenant_key, catalog_key, template_key, data_examples, status, created_at, created_by)
                VALUES (:id, :tenantId, :catalogKey, :templateId, '[]'::jsonb, 'draft', NOW(), :createdBy)
                """,
                )
                    .bind("id", contractVersionId)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("templateId", template.id)
                    .bind("createdBy", auditUser)
                    .execute()

                // 4. Create draft version with default TemplateDocument (version ID = 1)
                val prepared = templateDocumentPreparation.prepare(createDefaultTemplateModel(variantId), command.id.tenantKey, command.id.catalogKey)
                val versionId = VersionKey.of(1) // First version is always 1

                handle.createUpdate(
                    """
                INSERT INTO template_versions (id, tenant_key, catalog_key, template_key, variant_key, template_model, status, contract_version, referenced_paths, created_at, created_by)
                VALUES (:id, :tenantId, :catalogKey, :templateId, :variantId, :templateModel::jsonb, 'draft', :contractVersion, :referencedPaths::jsonb, NOW(), :createdBy)
                """,
                )
                    .bind("id", versionId)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("templateId", command.id.key)
                    .bind("variantId", variantId)
                    .bind("templateModel", prepared.templateModelJson)
                    .bind("contractVersion", contractVersionId)
                    .bind("referencedPaths", prepared.referencedPathsJson)
                    .bind("createdBy", auditUser)
                    .execute()

                template
            }
        }
    }
}
