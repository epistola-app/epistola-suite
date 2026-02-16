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
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
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
        val tenantId = request.pathVariable("tenantId")
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
        val attributes = readAttributesFromForm(request, TenantId.of(tenantId))

        CreateVariant(
            id = variantId,
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            title = title,
            description = description,
            attributes = attributes,
        ).execute()

        val variants = GetVariantSummaries(templateId = templateId).query()
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = templateId).query()
            ?: return ServerResponse.notFound().build()
        val attributeDefinitions = ListAttributeDefinitions(tenantId = TenantId.of(tenantId)).query()

        return request.htmx {
            fragment("templates/detail", "variants-section") {
                "tenantId" to tenantId
                "template" to template
                "variants" to variants
                "attributeDefinitions" to attributeDefinitions
            }
            onNonHtmx { redirect("/tenants/$tenantId/templates/$templateId") }
        }
    }

    fun editVariantForm(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()

        val variant = GetVariant(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
        ).query() ?: return ServerResponse.notFound().build()

        val attributeDefinitions = ListAttributeDefinitions(tenantId = TenantId.of(tenantId)).query()

        return request.htmx {
            fragment("templates/detail", "edit-variant-form") {
                "tenantId" to tenantId
                "templateId" to templateId
                "variant" to variant
                "attributeDefinitions" to attributeDefinitions
            }
        }
    }

    fun updateVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()

        val title = request.params().getFirst("title")?.trim()?.takeIf { it.isNotEmpty() }
        val attributes = readAttributesFromForm(request, TenantId.of(tenantId))

        UpdateVariant(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
            title = title,
            attributes = attributes,
        ).execute()

        val variants = GetVariantSummaries(templateId = templateId).query()
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = templateId).query()
            ?: return ServerResponse.notFound().build()
        val attributeDefinitions = ListAttributeDefinitions(tenantId = TenantId.of(tenantId)).query()

        return request.htmx {
            fragment("templates/detail", "variants-section") {
                "tenantId" to tenantId
                "template" to template
                "variants" to variants
                "attributeDefinitions" to attributeDefinitions
            }
            onNonHtmx { redirect("/tenants/$tenantId/templates/$templateId") }
        }
    }

    fun deleteVariant(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()

        DeleteVariant(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
        ).execute()

        val variants = GetVariantSummaries(templateId = templateId).query()
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = templateId).query()
            ?: return ServerResponse.notFound().build()
        val attributeDefinitions = ListAttributeDefinitions(tenantId = TenantId.of(tenantId)).query()

        return request.htmx {
            fragment("templates/detail", "variants-section") {
                "tenantId" to tenantId
                "template" to template
                "variants" to variants
                "attributeDefinitions" to attributeDefinitions
            }
            onNonHtmx { redirect("/tenants/$tenantId/templates/$templateId") }
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
