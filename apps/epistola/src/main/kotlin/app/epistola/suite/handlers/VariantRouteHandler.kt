package app.epistola.suite.templates

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
        val tagsInput = request.params().getFirst("tags")?.trim().orEmpty()
        val tags = parseTags(tagsInput)

        CreateVariant(
            id = variantId,
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            title = title,
            description = description,
            tags = tags,
        ).execute()

        val variants = GetVariantSummaries(templateId = templateId).query()
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = templateId).query()
            ?: return ServerResponse.notFound().build()

        return request.htmx {
            fragment("templates/detail", "variants-section") {
                "tenantId" to tenantId
                "template" to template
                "variants" to variants
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

        return request.htmx {
            fragment("templates/detail", "edit-variant-form") {
                "tenantId" to tenantId
                "templateId" to templateId
                "variant" to variant
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
        val tagsInput = request.params().getFirst("tags")?.trim().orEmpty()
        val tags = parseTags(tagsInput)

        UpdateVariant(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
            title = title,
            tags = tags,
        ).execute()

        val variants = GetVariantSummaries(templateId = templateId).query()
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = templateId).query()
            ?: return ServerResponse.notFound().build()

        return request.htmx {
            fragment("templates/detail", "variants-section") {
                "tenantId" to tenantId
                "template" to template
                "variants" to variants
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

        return request.htmx {
            fragment("templates/detail", "variants-section") {
                "tenantId" to tenantId
                "template" to template
                "variants" to variants
            }
            onNonHtmx { redirect("/tenants/$tenantId/templates/$templateId") }
        }
    }

    private fun parseTags(input: String): Map<String, String> {
        if (input.isBlank()) return emptyMap()
        return input.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("=") }
            .associate { line ->
                val parts = line.split("=", limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim()
            }
    }
}
