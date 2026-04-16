package app.epistola.suite.templates

import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.handlers.AuthContext
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.DefaultVariantDeletionException
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.templates.commands.variants.SetDefaultVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.validation.DuplicateIdException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Handles variant-related routes for document templates.
 * Extracted from DocumentTemplateHandler for better separation of concerns.
 */
@Component
class VariantRouteHandler {

    fun createVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val slug = request.params().getFirst("slug")?.trim()
        val variantKey = if (slug != null) VariantKey.validateOrNull(slug) else null
        if (variantKey == null) {
            return ServerResponse.badRequest().build()
        }

        val title = request.params().getFirst("title")?.trim()?.takeIf { it.isNotEmpty() }
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }
        val attributes = readAttributesFromForm(request, tenantId)

        try {
            CreateVariant(
                id = VariantId(variantKey, templateId),
                title = title,
                description = description,
                attributes = attributes,
            ).execute()
        } catch (e: DuplicateIdException) {
            return renderVariantsSection(request, tenantId, templateId, "A variant with this ID already exists")
        }

        return renderVariantsSection(request, tenantId, templateId)
    }

    fun editVariantForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        val variant = GetVariant(variantId = variantId).query()
            ?: return ServerResponse.notFound().build()

        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId).query()

        return request.htmx {
            fragment("templates/detail", "edit-variant-form") {
                "tenantId" to tenantId.key.value
                "catalogId" to catalogId.value
                "templateId" to templateId.key
                "variant" to variant
                "attributeDefinitions" to attributeDefinitions
            }
        }
    }

    fun updateVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        val title = request.params().getFirst("title")?.trim()?.takeIf { it.isNotEmpty() }
        val attributes = readAttributesFromForm(request, tenantId)

        UpdateVariant(
            variantId = variantId,
            title = title,
            attributes = attributes,
        ).execute()

        val variants = GetVariantSummaries(templateId = templateId).query()
        val template = GetDocumentTemplate(id = templateId).query()
            ?: return ServerResponse.notFound().build()
        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId).query()
        val editable = app.epistola.suite.catalog.queries.IsCatalogEditable(tenantId.key, catalogId).query()
        val auth = AuthContext.from(SecurityContext.current(), tenantId.key)

        return request.htmx {
            fragment("templates/detail/variants", "variants-section") {
                "tenantId" to tenantId.key.value
                "catalogId" to catalogId.value
                "template" to template
                "variants" to variants
                "attributeDefinitions" to attributeDefinitions
                "editable" to editable
                "auth" to auth
            }
            trigger("closeDialog")
            onNonHtmx { redirect("/tenants/${tenantId.key.value}/templates/$catalogId/${templateId.key}") }
        }
    }

    fun setDefaultVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        SetDefaultVariant(variantId = variantId).execute()

        return renderVariantsSection(request, tenantId, templateId)
    }

    fun deleteVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteVariant(variantId = variantId).execute()
        } catch (_: DefaultVariantDeletionException) {
            return renderVariantsSection(request, tenantId, templateId, "Cannot delete the default variant")
        }

        return renderVariantsSection(request, tenantId, templateId)
    }

    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    private fun renderVariantsSection(
        request: ServerRequest,
        tenantId: TenantId,
        templateId: TemplateId,
        errorMessage: String? = null,
    ): ServerResponse {
        val catalogId = request.catalogId()
        val variants = GetVariantSummaries(templateId = templateId).query()
        val template = GetDocumentTemplate(id = templateId).query()
            ?: return ServerResponse.notFound().build()
        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId).query()
        val editable = app.epistola.suite.catalog.queries.IsCatalogEditable(tenantId.key, catalogId).query()
        logger.info("renderVariantsSection: variants={}, editable={}, catalogId={}, isHtmx={}", variants.size, editable, catalogId, request.isHtmx)
        val auth = AuthContext.from(SecurityContext.current(), tenantId.key)

        return request.htmx {
            fragment("templates/detail/variants", "variants-section") {
                "tenantId" to tenantId.key.value
                "catalogId" to catalogId.value
                "template" to template
                "variants" to variants
                "attributeDefinitions" to attributeDefinitions
                "editable" to editable
                "auth" to auth
                if (errorMessage != null) {
                    "error" to errorMessage
                }
            }
            trigger("closeDialog")
            onNonHtmx { redirect("/tenants/${tenantId.key.value}/templates/$catalogId/${templateId.key}") }
        }
    }

    /**
     * Reads variant attributes from form parameters.
     * Each attribute definition is submitted as `attr_{key}` form parameter.
     * Empty values are excluded (not set).
     */
    private fun readAttributesFromForm(request: ServerRequest, tenantId: TenantId): Map<String, String> {
        val definitions = ListAttributeDefinitions(tenantId).query()
        val attributes = mutableMapOf<String, String>()
        for (def in definitions) {
            val value = request.params().getFirst("attr_${def.id.value}")?.trim()
            if (!value.isNullOrEmpty()) {
                attributes[def.id.value] = value
            }
        }
        return attributes
    }
}
