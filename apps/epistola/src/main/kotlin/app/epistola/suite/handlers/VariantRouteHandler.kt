package app.epistola.suite.templates

import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()

        val slug = request.params().getFirst("slug")?.trim()
        val variantId = if (slug != null) VariantId.validateOrNull(slug) else null
        if (variantId == null) {
            return ServerResponse.badRequest().build()
        }

        val title = request.params().getFirst("title")?.trim()?.takeIf { it.isNotEmpty() }
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }
        val attributes = readAttributesFromForm(request, tenantId)

        try {
            CreateVariant(
                id = variantId,
                tenantId = tenantId,
                templateId = templateId,
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
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()

        val variant = GetVariant(
            tenantId = tenantId,
            templateId = templateId,
            variantId = variantId,
        ).query() ?: return ServerResponse.notFound().build()

        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId).query()

        return request.htmx {
            fragment("templates/detail", "edit-variant-form") {
                "tenantId" to tenantId.value
                "templateId" to templateId
                "variant" to variant
                "attributeDefinitions" to attributeDefinitions
            }
        }
    }

    fun updateVariant(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()

        val title = request.params().getFirst("title")?.trim()?.takeIf { it.isNotEmpty() }
        val attributes = readAttributesFromForm(request, tenantId)

        UpdateVariant(
            tenantId = tenantId,
            templateId = templateId,
            variantId = variantId,
            title = title,
            attributes = attributes,
        ).execute()

        val variants = GetVariantSummaries(tenantId = tenantId, templateId = templateId).query()
        val template = GetDocumentTemplate(tenantId = tenantId, id = templateId).query()
            ?: return ServerResponse.notFound().build()
        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId).query()

        return request.htmx {
            fragment("templates/detail", "variants-section") {
                "tenantId" to tenantId.value
                "template" to template
                "variants" to variants
                "attributeDefinitions" to attributeDefinitions
            }
            onNonHtmx { redirect("/tenants/${tenantId.value}/templates/$templateId") }
        }
    }

    fun setDefaultVariant(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()

        SetDefaultVariant(
            tenantId = tenantId,
            templateId = templateId,
            variantId = variantId,
        ).execute()

        return renderVariantsSection(request, tenantId, templateId)
    }

    fun deleteVariant(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteVariant(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
            ).execute()
        } catch (_: DefaultVariantDeletionException) {
            return renderVariantsSection(request, tenantId, templateId, "Cannot delete the default variant")
        }

        return renderVariantsSection(request, tenantId, templateId)
    }

    private fun renderVariantsSection(
        request: ServerRequest,
        tenantId: TenantId,
        templateId: TemplateId,
        errorMessage: String? = null,
    ): ServerResponse {
        val variants = GetVariantSummaries(tenantId = tenantId, templateId = templateId).query()
        val template = GetDocumentTemplate(tenantId = tenantId, id = templateId).query()
            ?: return ServerResponse.notFound().build()
        val attributeDefinitions = ListAttributeDefinitions(tenantId = tenantId).query()

        return request.htmx {
            fragment("templates/detail", "variants-section") {
                "tenantId" to tenantId.value
                "template" to template
                "variants" to variants
                "attributeDefinitions" to attributeDefinitions
                if (errorMessage != null) {
                    "error" to errorMessage
                }
            }
            onNonHtmx { redirect("/tenants/${tenantId.value}/templates/$templateId") }
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
