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
import java.util.UUID

@Component
class DocumentTemplateHandler(
    private val listHandler: ListDocumentTemplatesHandler,
    private val createHandler: CreateDocumentTemplateHandler,
    private val getHandler: GetDocumentTemplateHandler,
) {
    fun list(request: ServerRequest): ServerResponse {
        val templates = listHandler.handle(ListDocumentTemplates())
        return ServerResponse.ok().render("templates/list", mapOf("templates" to templates))
    }

    fun search(request: ServerRequest): ServerResponse {
        val searchTerm = request.param("q").orElse(null)
        val templates = listHandler.handle(ListDocumentTemplates(searchTerm = searchTerm))
        return request.htmx {
            fragment("templates/list", "rows") {
                "templates" to templates
            }
            onNonHtmx { redirect("/templates") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val formData = request.params()
        val command = CreateDocumentTemplate(
            name = formData.getFirst("name") ?: throw IllegalArgumentException("Name is required"),
            content = formData.getFirst("content")?.takeIf { it.isNotBlank() },
        )
        createHandler.handle(command)

        val templates = listHandler.handle(ListDocumentTemplates())
        return request.htmx {
            fragment("templates/list", "rows") {
                "templates" to templates
            }
            trigger("templateCreated")
            onNonHtmx { redirect("/templates") }
        }
    }

    fun edit(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        val template = getHandler.handle(GetDocumentTemplate(id))
            ?: return ServerResponse.notFound().build()

        // Parse existing content or create a default editor template
        val templateJson = if (template.content.isNullOrBlank()) {
            // Create a default editor template structure
            """
            {
                "id": "${UUID.randomUUID()}",
                "name": "${template.name.replace("\"", "\\\"")}",
                "version": 1,
                "pageSettings": {
                    "format": "A4",
                    "orientation": "portrait",
                    "margins": { "top": 20, "right": 20, "bottom": 20, "left": 20 }
                },
                "blocks": []
            }
            """.trimIndent()
        } else {
            template.content
        }

        return ServerResponse.ok().render(
            "templates/edit",
            mapOf(
                "templateId" to id,
                "templateName" to template.name,
                "templateJson" to templateJson,
            ),
        )
    }
}
