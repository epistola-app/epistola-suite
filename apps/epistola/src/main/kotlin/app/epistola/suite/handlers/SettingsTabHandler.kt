package app.epistola.suite.templates

import app.epistola.suite.mediator.query
import app.epistola.suite.themes.queries.ListThemes
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class SettingsTabHandler(
    private val detailHelper: TemplateDetailHelper,
) {
    fun settings(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()

        val themes = ListThemes(tenantId = ctx.templateId.tenantId).query()

        return detailHelper.renderDetailPage(ctx, "settings", mapOf("themes" to themes))
    }
}
