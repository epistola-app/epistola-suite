package app.epistola.suite.templates

import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.contracts.GetLatestContractVersion
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class DataContractTabHandler(
    private val detailHelper: TemplateDetailHelper,
) {
    fun dataContract(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()

        // Load contract version data for the data contract tab
        val contractVersion = GetLatestContractVersion(templateId = ctx.templateId).query()

        return detailHelper.renderDetailPage(
            ctx,
            "data-contract",
            mapOf(
                "contractDataModel" to contractVersion?.dataModel,
                "contractDataExamples" to contractVersion?.dataExamples,
            ),
        )
    }
}
