package app.epistola.suite.templates

import app.epistola.suite.templates.validation.TemplateSchemaCompatibilityProperties
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class DataContractTabHandler(
    private val detailHelper: TemplateDetailHelper,
    private val templateSchemaCompatibilityProperties: TemplateSchemaCompatibilityProperties,
) {
    fun dataContract(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()

        return detailHelper.renderDetailPage(
            ctx,
            "data-contract",
            mapOf("recentUsageSampleLimit" to templateSchemaCompatibilityProperties.recentUsageSampleLimit),
        )
    }
}
