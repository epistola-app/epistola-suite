package app.epistola.suite.templates

import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplateHandler
import app.epistola.suite.templates.model.EditorTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplateHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import tools.jackson.databind.ObjectMapper

@Configuration
class DocumentTemplateApiRoutes(private val handler: DocumentTemplateApiHandler) {
    @Bean
    fun templateApiRoutes(): RouterFunction<ServerResponse> = router {
        "/api/templates".nest {
            GET("/{id}", handler::get)
            PUT("/{id}", handler::update)
        }
    }
}

@Component
class DocumentTemplateApiHandler(
    private val getHandler: GetDocumentTemplateHandler,
    private val updateHandler: UpdateDocumentTemplateHandler,
    private val objectMapper: ObjectMapper,
) {
    fun get(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        val template = getHandler.handle(GetDocumentTemplate(id))
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to template.id,
                    "name" to template.name,
                    "content" to template.content,
                    "createdAt" to template.createdAt,
                    "lastModified" to template.lastModified,
                ),
            )
    }

    fun update(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id").toLongOrNull()
            ?: return ServerResponse.badRequest().build()

        // Read and parse the request body as EditorTemplate
        val body = request.body(String::class.java)
        val editorTemplate = objectMapper.readValue(body, EditorTemplate::class.java)

        val updated = updateHandler.handle(UpdateDocumentTemplate(id, editorTemplate))
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to updated.id,
                    "name" to updated.name,
                    "lastModified" to updated.lastModified,
                ),
            )
    }
}
