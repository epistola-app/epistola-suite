package app.epistola.suite.templates

import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class DataContractTabHandler(
    private val detailHelper: TemplateDetailHelper,
) {
    fun dataContract(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()

        // Data contract tab uses template.dataModel and template.dataExamples from the shared context
        return detailHelper.renderDetailPage(ctx, "data-contract")
    }
}
