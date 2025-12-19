package app.epistola.suite.templates

import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.LocalDateTime

@Component
class DocumentTemplateHandler {
    fun list(request: ServerRequest): ServerResponse {
        val templates = listOf(
            DocumentTemplate(
                id = 1,
                name = "Invoice Template",
                lastModified = LocalDateTime.of(2025, 1, 15, 10, 30),
            ),
            DocumentTemplate(
                id = 2,
                name = "Contract Template",
                lastModified = LocalDateTime.of(2025, 1, 10, 14, 45),
            ),
            DocumentTemplate(
                id = 3,
                name = "Letter Template",
                lastModified = LocalDateTime.of(2025, 1, 5, 9, 0),
            ),
        )
        return ServerResponse.ok().render("templates/list", mapOf("templates" to templates))
    }
}
