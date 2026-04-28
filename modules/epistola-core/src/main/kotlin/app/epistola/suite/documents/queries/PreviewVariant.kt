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
 * Preview the current version of a variant (draft → latest published).
 * Optionally overrides the template model with a live editor version.
 *
 * @property tenantId Tenant that owns the template
 * @property templateId Template to preview
 * @property variantId Variant to preview
 * @property data JSON data to populate the template
 * @property templateModel Optional live template model override; null fetches saved draft or latest published
 */
data class PreviewVariant(
    val tenantId: TenantKey,
    val catalogKey: app.epistola.suite.common.ids.CatalogKey,
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

private data class VariantContractRow(
    @Json val dataModel: ObjectNode?,
)

@Component
class PreviewVariantHandler(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
    private val schemaValidator: JsonSchemaValidator,
    private val renderer: DocumentPreviewRenderer,
) : QueryHandler<PreviewVariant, ByteArray> {

    override fun handle(query: PreviewVariant): ByteArray {
        val tenantId = TenantId(query.tenantId)
        val catalogId = CatalogId(query.catalogKey, tenantId)
        val templateId = TemplateId(query.templateId, catalogId)

        // 1. Resolve template model: live override → saved draft → latest published
        val templateModel = query.templateModel
            ?: fetchDraftOrLatestPublished(query.tenantId, query.catalogKey, query.templateId, query.variantId)
            ?: throw IllegalStateException("No draft or published version found for variant ${query.variantId}")

        // 2. Fetch template and tenant for theme resolution
        val template = mediator.query(GetDocumentTemplate(templateId))
            ?: throw IllegalStateException("Template ${query.templateId} not found")
        val tenant = mediator.query(GetTenant(id = query.tenantId))
            ?: throw IllegalStateException("Tenant ${query.tenantId} not found")

        // 3. Validate data against contract schema (latest draft or published contract)
        val dataModel: ObjectNode? = jdbi.withHandle<ObjectNode?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT data_model FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey
                  AND status IN ('draft', 'published')
                ORDER BY CASE status WHEN 'draft' THEN 0 ELSE 1 END, id DESC
                LIMIT 1
                """,
            )
                .bind("tenantKey", query.tenantId)
                .bind("catalogKey", query.catalogKey)
                .bind("templateKey", query.templateId)
                .mapTo<VariantContractRow>()
                .findOne()
                .map { it.dataModel }
                .orElse(null)
        }
        if (dataModel != null) {
            val errors = schemaValidator.validate(dataModel, query.data)
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

    private fun fetchDraftOrLatestPublished(tenantId: TenantKey, catalogKey: app.epistola.suite.common.ids.CatalogKey, templateId: TemplateKey, variantKey: VariantKey): TemplateDocument? = jdbi.withHandle<TemplateDocument?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT template_model as draft_template_model
                FROM template_versions
                WHERE tenant_key = :tenantId
                  AND catalog_key = :catalogKey
                  AND template_key = :templateId
                  AND variant_key = :variantId
                  AND status IN ('draft', 'published')
                ORDER BY CASE status WHEN 'draft' THEN 0 ELSE 1 END, id DESC
                LIMIT 1
                """,
        )
            .bind("tenantId", tenantId)
            .bind("catalogKey", catalogKey)
            .bind("templateId", templateId)
            .bind("variantId", variantKey)
            .mapTo<DraftRow>()
            .findOne()
            .map { it.draftTemplateModel }
            .orElse(null)
    }
}
