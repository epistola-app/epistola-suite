package app.epistola.suite.templates

import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.contracts.GetDraftContractVersion
import app.epistola.suite.templates.queries.contracts.GetLatestContractVersion
import app.epistola.suite.templates.queries.contracts.ListContractVersions
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class DataContractTabHandler(
    private val detailHelper: TemplateDetailHelper,
) {
    fun dataContract(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()

        val contractVersion = GetLatestContractVersion(templateId = ctx.templateId).query()
        val draftContract = GetDraftContractVersion(templateId = ctx.templateId).query()
        val contractVersions = ListContractVersions(templateId = ctx.templateId).query()
        val latestPublishedId = contractVersions.firstOrNull { it.status.name == "PUBLISHED" }?.id?.value

        return detailHelper.renderDetailPage(
            ctx,
            "data-contract",
            mapOf(
                "contractDataModel" to contractVersion?.dataModel,
                "contractDataExamples" to contractVersion?.dataExamples,
                "contractVersionId" to contractVersion?.id?.value,
                "contractVersionStatus" to contractVersion?.status?.name?.lowercase(),
                "hasDraftContract" to (draftContract != null),
                "latestPublishedContractId" to latestPublishedId,
                "contractVersionCount" to contractVersions.size,
            ),
        )
    }
}
