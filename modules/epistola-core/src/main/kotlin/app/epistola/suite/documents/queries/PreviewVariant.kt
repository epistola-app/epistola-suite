// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.queries

import app.epistola.generation.pdf.ResolvedTheme
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.generation.DocumentPreviewRenderer
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Preview a variant's working model, or a live model from the editor instead.
 *
 * @property tenantId Tenant that owns the template
 * @property templateId Template to preview
 * @property variantId Variant to preview
 * @property data JSON data to populate the template
 * @property templateModel Optional live template model override; null renders the variant's working model
 */
data class PreviewVariant(
    val catalog: CatalogId,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    val data: ObjectNode,
    val templateModel: TemplateDocument? = null,
    val resolvedTheme: ResolvedTheme? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<ByteArray>,
    RequiresPermission {
    val tenantId: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId
}

@Component
class PreviewVariantHandler(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
    private val schemaValidator: JsonSchemaValidator,
    private val renderer: DocumentPreviewRenderer,
    private val localeResolver: TenantLocaleResolver,
    private val templateDocumentValidator: TemplateDocumentValidator,
) : QueryHandler<PreviewVariant, ByteArray> {

    override fun handle(query: PreviewVariant): ByteArray {
        val catalogId = query.catalog
        val templateId = TemplateId(query.templateId, catalogId)
        val variantId = app.epistola.suite.common.ids.VariantId(query.variantId, templateId)

        // 1. The live model from the editor, else the variant's working model
        val templateModel = query.templateModel
            ?: mediator.query(GetVariantContent(variantId))
            ?: throw IllegalStateException("Variant ${query.variantId} not found")
        if (query.templateModel != null) {
            templateDocumentValidator.validateTemplate(templateModel)
        }

        // 2. Fetch template and tenant for theme resolution
        val template = mediator.query(GetDocumentTemplate(templateId))
            ?: throw IllegalStateException("Template ${query.templateId} not found")
        val tenant = mediator.query(GetTenant(id = query.tenantId))
            ?: throw IllegalStateException("Tenant ${query.tenantId} not found")

        // 3. Validate data against the template's data contract as it is now
        val dataModel: ObjectNode? = mediator.query(GetDataContract(templateId))?.dataModel
        // A field the caller omitted falls back to its schema `default`, applied
        // before validation and rendering so a preview matches actual generation.
        val effectiveData = if (dataModel != null) schemaValidator.applyDefaults(dataModel, query.data) else query.data
        if (dataModel != null) {
            val errors = schemaValidator.validate(dataModel, effectiveData)
            if (errors.isNotEmpty()) {
                val errorMessages = errors.joinToString("; ") { "${it.path}: ${it.message}" }
                throw IllegalArgumentException("Data validation failed: $errorMessages")
            }
        }

        // 4. Resolve the effective formatting culture via variant attribute → tenant default → app default
        val culture = localeResolver.resolveCulture(tenant, variantId)

        // 5. Render — always live theme cascade for drafts (no snapshot)
        return renderer.render(
            templateModel = templateModel,
            template = template,
            tenant = tenant,
            data = effectiveData,
            culture = culture,
            resolvedThemeOverride = query.resolvedTheme,
        )
    }
}
