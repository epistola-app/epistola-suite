package app.epistola.suite.templates

import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplateHandler
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplateHandler
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.ListDocumentTemplatesHandler
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

@Component
class DocumentTemplateHandler(
    private val listHandler: ListDocumentTemplatesHandler,
    private val createHandler: CreateDocumentTemplateHandler,
    private val getHandler: GetDocumentTemplateHandler,
    private val objectMapper: ObjectMapper,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val templates = listHandler.handle(ListDocumentTemplates(tenantId = tenantId))
        return ServerResponse.ok().render(
            "templates/list",
            mapOf(
                "tenantId" to tenantId,
                "templates" to templates,
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val searchTerm = request.param("q").orElse(null)
        val templates = listHandler.handle(ListDocumentTemplates(tenantId = tenantId, searchTerm = searchTerm))
        return request.htmx {
            fragment("templates/list", "rows") {
                "tenantId" to tenantId
                "templates" to templates
            }
            onNonHtmx { redirect("/tenants/$tenantId/templates") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val formData = request.params()
        val command = CreateDocumentTemplate(
            tenantId = tenantId,
            name = formData.getFirst("name") ?: throw IllegalArgumentException("Name is required"),
        )
        createHandler.handle(command)

        val templates = listHandler.handle(ListDocumentTemplates(tenantId = tenantId))
        return request.htmx {
            fragment("templates/list", "rows") {
                "tenantId" to tenantId
                "templates" to templates
            }
            trigger("templateCreated")
            onNonHtmx { redirect("/tenants/$tenantId/templates") }
        }
    }

    fun edit(request: ServerRequest): ServerResponse {
        val tenantId = resolveTenantId(request)
        val id = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        val template = getHandler.handle(GetDocumentTemplate(tenantId = tenantId, id = id))
            ?: return ServerResponse.notFound().build()

        // Serialize the EditorTemplate content to JSON for the frontend
        val templateJson = template.content?.let { objectMapper.writeValueAsString(it) } ?: "{}"

        return ServerResponse.ok().render(
            "templates/edit",
            mapOf(
                "tenantId" to tenantId,
                "templateId" to id,
                "templateName" to template.name,
                "templateJson" to templateJson,
            ),
        )
    }

    private fun resolveTenantId(request: ServerRequest): Long = request.pathVariable("tenantId").toLongOrNull()
        ?: throw IllegalArgumentException("Invalid tenant ID")
}
