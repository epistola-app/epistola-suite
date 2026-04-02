package app.epistola.suite.templates

import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.variantId
import app.epistola.suite.htmx.versionId
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.versions.ListPublishableVersionsByTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Handles the version comparison overlay for side-by-side PDF preview of two template versions.
 */
@Component
class VersionComparisonHandler(
    private val objectMapper: ObjectMapper,
) {

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

    /**
     * Renders a single version as PDF. Called by the comparison JS twice (once per version).
     */
    fun previewVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val variantId = request.variantId(templateId)
            ?: return ServerResponse.badRequest().build()
        val versionId = request.versionId(variantId)
            ?: return ServerResponse.badRequest().build()

        val data: ObjectNode = try {
            val body = request.body(String::class.java)
            if (body.isBlank()) {
                objectMapper.createObjectNode()
            } else {
                objectMapper.readTree(body) as ObjectNode
            }
        } catch (_: Exception) {
            objectMapper.createObjectNode()
        }

        val pdfBytes = try {
            PreviewDocument(
                tenantId = tenantId.key,
                templateId = templateId.key,
                data = data,
                variantId = variantId.key,
                versionId = versionId.key,
            ).query()
        } catch (e: IllegalArgumentException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to (e.message ?: "Validation failed")))
        } catch (_: IllegalStateException) {
            return ServerResponse.notFound().build()
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
            .body(pdfBytes)
    }
}
