package app.epistola.suite.handlers

import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class ChangelogHandler(
    private val changelogRenderer: ChangelogRenderer,
) {
    fun view(request: ServerRequest): ServerResponse = ServerResponse.ok().render(
        "fragments/changelog :: content",
        mapOf("changelogHtml" to changelogRenderer.renderHtml()),
    )
}
