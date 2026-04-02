package app.epistola.suite.templates

import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.versions.ListPublishableVersionsByTemplate
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Handles the version comparison overlay for side-by-side PDF preview of two template versions.
 */
@Component
class VersionComparisonHandler {

    /**
     * Returns the comparison dialog fragment with version selectors and example picker.
     */
    fun compareDialog(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()

        val template = GetDocumentTemplate(templateId).query()
            ?: return ServerResponse.notFound().build()
        val publishableVersions = ListPublishableVersionsByTemplate(templateId = templateId).query()

        // Filter versions for this specific variant
        val variantVersions = publishableVersions.filter { it.variantKey == variantId.key }

        return request.htmx {
            fragment("templates/version-comparison", "content") {
                "tenantId" to tenantId.key.value
                "templateId" to templateId.key.value
                "variantId" to variantId.key.value
                "versions" to variantVersions
                "dataExamples" to template.dataExamples.toList()
            }
            onNonHtmx { redirect("/tenants/${tenantId.key.value}/templates/${templateId.key.value}") }
        }
    }
}
