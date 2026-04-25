package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import app.epistola.template.model.ThemeRef
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class CreateDocumentTemplate(
    val id: TemplateId,
    val name: String,
) : Command<DocumentTemplate>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= 255) { "Name must be 255 characters or less" }
    }
}

@Component
class CreateDocumentTemplateHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateDocumentTemplate, DocumentTemplate> {
    override fun handle(command: CreateDocumentTemplate): DocumentTemplate {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)

        return executeOrThrowDuplicate("template", command.id.key.value) {
            jdbi.inTransaction<DocumentTemplate, Exception> { handle ->
                // 1. Create the template
                val template = handle.createQuery(
                    """
                INSERT INTO document_templates (id, tenant_key, catalog_key, name, theme_key, pdfa_enabled, created_at, last_modified)
                VALUES (:id, :tenantId, :catalogKey, :name, NULL, FALSE, NOW(), NOW())
                RETURNING id, tenant_key, catalog_key, name, theme_key, pdfa_enabled, created_at, last_modified
                """,
                )
                    .bind("id", command.id.key)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("name", command.name)
                    .mapTo<DocumentTemplate>()
                    .one()

                // 2. Create default variant with template-specific ID to avoid conflicts
                val variantId = VariantKey.of("${command.id.key}-default")
                handle.createUpdate(
                    """
                INSERT INTO template_variants (id, tenant_key, catalog_key, template_key, attributes, is_default, created_at, last_modified)
                VALUES (:id, :tenantId, :catalogKey, :templateId, '{}'::jsonb, TRUE, NOW(), NOW())
                """,
                )
                    .bind("id", variantId)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("templateId", template.id)
                    .execute()

                // 3. Create empty draft contract version (v1)
                val contractVersionId = VersionKey.of(1)
                handle.createUpdate(
                    """
                INSERT INTO contract_versions (id, tenant_key, catalog_key, template_key, data_examples, status, created_at)
                VALUES (:id, :tenantId, :catalogKey, :templateId, '[]'::jsonb, 'draft', NOW())
                """,
                )
                    .bind("id", contractVersionId)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("templateId", template.id)
                    .execute()

                // 4. Create draft version with default TemplateDocument (version ID = 1)
                val rootId = "root-${variantId.value}"
                val slotId = "slot-${variantId.value}"
                val templateModel = TemplateDocument(
                    modelVersion = 1,
                    root = rootId,
                    nodes = mapOf(
                        rootId to Node(
                            id = rootId,
                            type = "root",
                            slots = listOf(slotId),
                        ),
                    ),
                    slots = mapOf(
                        slotId to Slot(
                            id = slotId,
                            nodeId = rootId,
                            name = "children",
                            children = emptyList(),
                        ),
                    ),
                    themeRef = ThemeRef.Inherit,
                )
                val templateModelJson = objectMapper.writeValueAsString(templateModel)
                val versionId = VersionKey.of(1) // First version is always 1

                handle.createUpdate(
                    """
                INSERT INTO template_versions (id, tenant_key, catalog_key, template_key, variant_key, template_model, status, contract_version, created_at)
                VALUES (:id, :tenantId, :catalogKey, :templateId, :variantId, :templateModel::jsonb, 'draft', :contractVersion, NOW())
                """,
                )
                    .bind("id", versionId)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("templateId", command.id.key)
                    .bind("variantId", variantId)
                    .bind("templateModel", templateModelJson)
                    .bind("contractVersion", contractVersionId)
                    .execute()

                template
            }
        }
    }
}
