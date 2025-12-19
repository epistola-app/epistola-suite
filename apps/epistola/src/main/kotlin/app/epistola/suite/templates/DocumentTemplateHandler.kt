package app.epistola.suite.templates

import app.epistola.suite.htmx.render
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplateHandler
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.ListDocumentTemplatesHandler
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class DocumentTemplateHandler(
    private val listHandler: ListDocumentTemplatesHandler,
    private val createHandler: CreateDocumentTemplateHandler,
) {
    fun list(request: ServerRequest): ServerResponse {
        val templates = listHandler.handle(ListDocumentTemplates())
        return ServerResponse.ok().render("templates/list", mapOf("templates" to templates))
    }

    fun create(request: ServerRequest): ServerResponse {
        val formData = request.params()
        val command = CreateDocumentTemplate(
            name = formData.getFirst("name") ?: throw IllegalArgumentException("Name is required"),
            content = formData.getFirst("content")?.takeIf { it.isNotBlank() },
        )
        createHandler.handle(command)

        val templates = listHandler.handle(ListDocumentTemplates())
        return request.render(
            template = "templates/list",
            fragment = "rows",
            model = mapOf("templates" to templates),
            redirectOnSuccess = "/templates",
        )
    }
}
