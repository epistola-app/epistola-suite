package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.generation.DocumentPreviewRenderer
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.json.Json
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Preview a draft or live template model from the editor.
 *
 * @property tenantId Tenant that owns the template
 * @property templateId Template to preview
 * @property variantId Variant whose draft to preview
 * @property data JSON data to populate the template
 * @property templateModel Optional live template model override; null fetches the saved draft
 */
data class PreviewDraft(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    val data: ObjectNode,
    val templateModel: TemplateDocument? = null,
) : Query<ByteArray>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId
}

private data class DraftRow(
    @Json val draftTemplateModel: TemplateDocument?,
)

@Component
class PreviewDraftHandler(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
    private val schemaValidator: JsonSchemaValidator,
    private val renderer: DocumentPreviewRenderer,
) : QueryHandler<PreviewDraft, ByteArray> {

    override fun handle(query: PreviewDraft): ByteArray {
        val tenantId = TenantId(query.tenantId)
        val templateId = TemplateId(query.templateId, CatalogId.default(tenantId))

        // 1. Resolve template model: live override or saved draft
        val templateModel = query.templateModel
            ?: fetchDraft(query.tenantId, query.templateId, query.variantId)
            ?: throw IllegalStateException("No draft found for variant ${query.variantId}")

        // 2. Fetch template and tenant for theme resolution
        val template = mediator.query(GetDocumentTemplate(templateId))
            ?: throw IllegalStateException("Template ${query.templateId} not found")
        val tenant = mediator.query(GetTenant(id = query.tenantId))
            ?: throw IllegalStateException("Tenant ${query.tenantId} not found")

        // 3. Validate data against schema
        if (template.dataModel != null) {
            val errors = schemaValidator.validate(template.dataModel, query.data)
            if (errors.isNotEmpty()) {
                val errorMessages = errors.joinToString("; ") { "${it.path}: ${it.message}" }
                throw IllegalArgumentException("Data validation failed: $errorMessages")
            }
        }

        // 4. Render — always live theme cascade for drafts (no snapshot)
        return renderer.render(
            tenantId = query.tenantId,
            templateModel = templateModel,
            version = null,
            template = template,
            tenant = tenant,
            data = query.data,
        )
    }

    private fun fetchDraft(tenantId: TenantKey, templateId: TemplateKey, variantKey: VariantKey): TemplateDocument? = jdbi.withHandle<TemplateDocument?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT ver.template_model as draft_template_model
                FROM template_versions ver
                WHERE ver.tenant_key = :tenantId
                  AND ver.template_key = :templateId
                  AND ver.variant_key = :variantId
                  AND ver.status = 'draft'
                """,
        )
            .bind("tenantId", tenantId)
            .bind("templateId", templateId)
            .bind("variantId", variantKey)
            .mapTo<DraftRow>()
            .findOne()
            .map { it.draftTemplateModel }
            .orElse(null)
    }
}
