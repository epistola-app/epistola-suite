package app.epistola.suite.templates

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.pathUuid
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.templates.queries.GetDocumentTemplate
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
        val templateId = request.pathUuid("id")
            ?: return ServerResponse.badRequest().build()

        val title = request.params().getFirst("title")?.trim()?.takeIf { it.isNotEmpty() }
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }
        val tagsInput = request.params().getFirst("tags")?.trim().orEmpty()
        val tags = parseTags(tagsInput)

        CreateVariant(
            id = VariantId.generate(),
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            title = title,
            description = description,
            tags = tags,
        ).execute()

        val variants = GetVariantSummaries(templateId = TemplateId.of(templateId)).query()
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(templateId)).query()
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
        val templateId = request.pathUuid("id")
            ?: return ServerResponse.badRequest().build()
        val variantId = request.pathUuid("variantId")
            ?: return ServerResponse.badRequest().build()

        DeleteVariant(
            tenantId = TenantId.of(tenantId),
            templateId = TemplateId.of(templateId),
            variantId = VariantId.of(variantId),
        ).execute()

        val variants = GetVariantSummaries(templateId = TemplateId.of(templateId)).query()
        val template = GetDocumentTemplate(tenantId = TenantId.of(tenantId), id = TemplateId.of(templateId)).query()
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
