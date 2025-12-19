package app.epistola.suite.templates

import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.ListDocumentTemplatesHandler
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class DocumentTemplateHandler(
    private val listHandler: ListDocumentTemplatesHandler,
) {
    fun list(request: ServerRequest): ServerResponse {
        val templates = listHandler.handle(ListDocumentTemplates())
        return ServerResponse.ok().render("templates/list", mapOf("templates" to templates))
    }
}
